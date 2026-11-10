package free.svoss.facesort.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility class for image processing operations.
 * Supports JPEG, PNG, BMP, GIF, WebP and ICO (via TwelveMonkeys plugins).
 */
public final class ImageUtils {

    private ImageUtils() {
        // Utility class - not instantiable
    }

    /**
     * Downsizes an image so that neither dimension exceeds maxDimension,
     * preserving the aspect ratio. If the image is already small enough,
     * the original is returned unchanged.
     *
     * @param img          the source image
     * @param maxDimension the maximum width or height
     * @return the downsized image, or the original if no resizing needed
     */
    public static BufferedImage downsize(BufferedImage img, int maxDimension) {
        int width = img.getWidth();
        int height = img.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return img;
        }

        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        int newWidth = (int) Math.round(width * scale);
        int newHeight = (int) Math.round(height * scale);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        return resized;
    }

    /**
     * Encodes a BufferedImage as JPEG bytes with the specified quality.
     *
     * @param img     the image to encode
     * @param quality JPEG quality in range [0.0, 1.0] where 1.0 is maximum quality
     * @return the JPEG-encoded image bytes
     * @throws IOException if encoding fails
     */
    public static byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(baos);
            writer.setOutput(output);
            writer.write(null, new IIOImage(img, null, null), param);
            output.flush();
            output.close();

            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Reads an image from the specified file path.
     * Supports JPEG, PNG, BMP, GIF, WebP and ICO (via TwelveMonkeys plugins).
     * Icon files store the same picture at several sizes; the largest one is
     * returned, so face detection sees the best available resolution.
     *
     * @param file the path to the image file
     * @return the loaded BufferedImage
     * @throws IOException if the file cannot be read or is not a supported image format
     */
    public static BufferedImage readImage(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int orientation = readExifOrientationFromBytes(bytes);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format or cannot read: " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int count = isIconContainer(reader) ? reader.getNumImages(true) : 1;
                BufferedImage largest = null;
                for (int index = 0; index < count; index++) {
                    BufferedImage candidate = reader.read(index);
                    candidate = applyExifOrientation(candidate, orientation);
                    if (largest == null || candidate.getWidth() > largest.getWidth()) {
                        largest = candidate;
                    }
                }
                if (largest == null) {
                    throw new IOException("Unsupported image format or cannot read: " + file);
                }
                return largest;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Parses the EXIF orientation tag (0x0112) from the APP1 segment of a JPEG.
     * Returns 1 (normal) when no EXIF orientation is found or the file is not a JPEG.
     *
     * @param bytes the raw image file bytes
     * @return the EXIF orientation value (1-8), or 1 when not found
     */
    private static int readExifOrientationFromBytes(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != (byte) 0xFF || bytes[1] != (byte) 0xD8) {
            return 1; // not a JPEG
        }
        int i = 2;
        while (i < bytes.length - 1) {
            if (bytes[i] == (byte) 0xFF && bytes[i + 1] == (byte) 0xE1) {
                // APP1 segment - could be EXIF
                int segLen = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                if (i + 4 + segLen <= bytes.length) {
                    // Check for "Exif\0\0" prefix
                    if (bytes[i + 4] == 0x45 && bytes[i + 5] == 0x78 && bytes[i + 6] == 0x69 &&
                            bytes[i + 7] == 0x66 && bytes[i + 8] == 0 && bytes[i + 9] == 0) {
                        // TIFF header starts at i + 10
                        int tiffStart = i + 10;
                        if (tiffStart + 8 <= bytes.length) {
                            // Check byte order (little-endian: II)
                            if (bytes[tiffStart] == 0x49 && bytes[tiffStart + 1] == 0x49) {
                                int ifdOffset = tiffStart + 8;
                                // Read IFD0 entry count (2 bytes, little-endian)
                                if (ifdOffset + 2 <= bytes.length) {
                                    int entryCount = bytes[ifdOffset] & 0xFF | ((bytes[ifdOffset + 1] & 0xFF) << 8);
                                    int entryPos = ifdOffset + 2;
                                    for (int j = 0; j < entryCount; j++) {
                                        if (entryPos + 12 > bytes.length) {
                                            break;
                                        }
                                        int tag = bytes[entryPos] & 0xFF | ((bytes[entryPos + 1] & 0xFF) << 8);
                                        if (tag == 0x0112) {
                                            // Orientation value (2 bytes, little-endian)
                                            return bytes[entryPos + 8] & 0xFF | ((bytes[entryPos + 9] & 0xFF) << 8);
                                        }
                                        entryPos += 12;
                                    }
                                }
                            }
                        }
                    }
                }
                // Skip to next marker
                i += 2 + segLen;
            } else if (bytes[i] == (byte) 0xFF && bytes[i + 1] == (byte) 0xD9) {
                break; // EOI
            } else if (bytes[i] == (byte) 0xFF && bytes[i + 1] != 0x00) {
                // Other marker with length
                int segLen = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                i += 2 + segLen;
            } else {
                i++;
            }
        }
        return 1;
    }

    /**
     * Applies the EXIF orientation rotation/flip to the image.
     * Orientation values: 1=normal, 2=flip H, 3=rotate 180, 4=flip V,
     * 5=transpose, 6=rotate 90 CW, 7=transverse, 8=rotate 90 CCW.
     *
     * @param image     the decoded image
     * @param orientation the EXIF orientation value (1-8)
     * @return the transformed image, or the original when orientation is 1
     */
    private static BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
        if (image == null || orientation == 1 || orientation == 0) {
            return image;
        }
        return rotateImage(image, orientation);
    }

    private static boolean isIconContainer(ImageReader reader) throws IOException {
        String format = reader.getFormatName();
        if (format == null) {
            return false;
        }
        String lowerCase = format.toLowerCase(Locale.ROOT);
        return lowerCase.startsWith("ico") || lowerCase.startsWith("cur");
    }

    /**
     * Reads the EXIF orientation tag from JPEG metadata and applies the
     * corresponding rotation or flip to the image. Orientation values:
     * 1=normal, 2=flip horizontal, 3=rotate 180, 4=flip vertical,
     * 5=transpose, 6=rotate 90 CW, 7=transverse, 8=rotate 90 CCW.
     *
     * @param image  the decoded image
     * @param metadata the image's IIOMetadata, may be null
     * @return the image rotated/flipped according to its EXIF orientation,
     *         or the original image when no orientation metadata is present
     */
    private static BufferedImage applyExifOrientation(BufferedImage image, IIOMetadata metadata) {
        if (metadata == null || image == null) {
            return image;
        }
        int orientation = readExifOrientation(metadata);
        if (orientation == 1 || orientation == 0) {
            return image;
        }
        return rotateImage(image, orientation);
    }

    /**
     * Extracts the EXIF orientation value (tag 0x0112) from IIOMetadata.
     *
     * @param metadata the image metadata
     * @return the orientation value (1-8), or 1 when not found
     */
    private static int readExifOrientation(IIOMetadata metadata) {
        try {
            Node tree = metadata.getAsTree("javax_imageio_1.0");
            if (tree == null) {
                return 1;
            }
            NodeList children = tree.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if ("Dimension".equals(node.getNodeName())) {
                    NamedNodeMap attr = node.getAttributes();
                    if (attr != null) {
                        Node orientation = attr.getNamedItem("orientation");
                        if (orientation != null) {
                            return Integer.parseInt(orientation.getNodeValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore metadata parsing errors; fall back to no rotation
        }
        return 1;
    }

    /**
     * Rotates or flips an image according to an EXIF orientation value.
     *
     * @param image       the source image
     * @param orientation EXIF orientation value (2-8)
     * @return the transformed image
     */
    private static BufferedImage rotateImage(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage result;
        switch (orientation) {
            case 2: // Flip horizontal
                result = new BufferedImage(width, height, image.getType());
                flipHorizontal(image, result);
                break;
            case 3: // Rotate 180
                result = new BufferedImage(width, height, image.getType());
                rotate180(image, result);
                break;
            case 4: // Flip vertical
                result = new BufferedImage(width, height, image.getType());
                flipVertical(image, result);
                break;
            case 5: // Transpose: flip horizontal + rotate 90 CCW
                BufferedImage flippedH = new BufferedImage(width, height, image.getType());
                flipHorizontal(image, flippedH);
                result = rotate90CCW(flippedH);
                break;
            case 6: // Rotate 90 CW
                result = rotate90CW(image);
                break;
            case 7: // Transverse: flip vertical + rotate 90 CCW
                BufferedImage flippedV = new BufferedImage(width, height, image.getType());
                flipVertical(image, flippedV);
                result = rotate90CCW(flippedV);
                break;
            case 8: // Rotate 90 CCW
                result = rotate90CCW(image);
                break;
            default:
                return image;
        }
        return result;
    }

    private static void flipHorizontal(BufferedImage src, BufferedImage dst) {
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, width(src), 0, -width(src), height(src), null);
        } finally {
            g.dispose();
        }
    }

    private static void flipVertical(BufferedImage src, BufferedImage dst) {
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, 0, height(src), width(src), -height(src), null);
        } finally {
            g.dispose();
        }
    }

    private static void rotate180(BufferedImage src, BufferedImage dst) {
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, width(src), height(src), -width(src), -height(src), null);
        } finally {
            g.dispose();
        }
    }

    private static BufferedImage rotate90CW(BufferedImage src) {
        BufferedImage dst = new BufferedImage(height(src), width(src), src.getType());
        Graphics2D g = dst.createGraphics();
        try {
            g.translate(height(src), 0);
            g.rotate(Math.PI / 2.0);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static BufferedImage rotate90CCW(BufferedImage src) {
        BufferedImage dst = new BufferedImage(height(src), width(src), src.getType());
        Graphics2D g = dst.createGraphics();
        try {
            g.translate(0, width(src));
            g.rotate(-Math.PI / 2.0);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static int width(BufferedImage img) {
        return img.getWidth();
    }

    private static int height(BufferedImage img) {
        return img.getHeight();
    }
}
