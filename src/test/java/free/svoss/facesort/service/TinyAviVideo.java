package free.svoss.facesort.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a tiny Motion-JPEG AVI video entirely in Java, so the end-to-end
 * ffmpeg import tests always have a real, deterministic video file to chew on
 * without shipping a binary fixture or depending on machine-local samples.
 *
 * <p>The AVI is written without the trailing {@code idx1} index (leaf chunks
 * only), at one frame per second with distinct frame content. ffmpeg's demuxer
 * scans the {@code movi} list directly, so seeking works exactly like on any
 * normal file.</p>
 */
final class TinyAviVideo {

    private static final String AVI = "AVI ";
    private static final String MJPG = "MJPG";
    private static final String FOURCC_DC = "00dc";

    private TinyAviVideo() {
        // utility class
    }

    /**
     * Writes a 1 fps MJPEG AVI of {@code frameCount} distinct frames.
     *
     * @return the written file
     */
    static Path write(Path file, int width, int height, int frameCount) throws IOException {
        List<byte[]> jpegs = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            jpegs.add(jpegFrame(width, height, i));
        }
        Files.write(file, buildAvi(width, height, frameCount, jpegs));
        return file;
    }

    /** A gray frame with a moving white marker bar, so consecutive frames differ. */
    private static byte[] jpegFrame(int width, int height, int index) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            int gray = 30 + (index * 50) % 180;
            g.setColor(new Color(gray, gray, gray));
            g.fillRect(0, 0, width, height);
            g.setColor(Color.WHITE);
            g.fillRect((index * 10) % Math.max(1, width - 4), 0, 4, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg", out)) {
            throw new IOException("no JPEG writer available");
        }
        return out.toByteArray();
    }

    private static byte[] buildAvi(int width, int height, int frameCount,
                                   List<byte[]> jpegs) {
        ByteArrayOutputStream hdrl = new ByteArrayOutputStream();
        chunk(hdrl, "avih", avih(frameCount, width, height));
        ByteArrayOutputStream strl = new ByteArrayOutputStream();
        chunk(strl, "strh", strh(width, height, frameCount));
        chunk(strl, "strf", strf(width, height));
        list(hdrl, "strl", strl.toByteArray());

        ByteArrayOutputStream moviInner = new ByteArrayOutputStream();
        for (byte[] jpeg : jpegs) {
            chunk(moviInner, FOURCC_DC, jpeg);
        }
        ByteArrayOutputStream movi = new ByteArrayOutputStream();
        list(movi, "movi", moviInner.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fourCc(out, "RIFF");
        leInt(out, 4 + hdrl.size() + movi.size());
        fourCc(out, AVI);
        out.writeBytes(hdrl.toByteArray());
        out.writeBytes(movi.toByteArray());
        return out.toByteArray();
    }

    /** The 56-byte MainAVIHeader. */
    private static byte[] avih(int frameCount, int width, int height) {
        ByteBuffer bb = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(1_000_000);         // dwMicroSecPerFrame: 1 second per frame
        bb.putInt(0);                 // dwMaxBytesPerSec
        bb.putInt(0);                 // dwPaddingGranularity
        bb.putInt(0);                 // dwFlags: no index chunk attached
        bb.putInt(frameCount);        // dwTotalFrames
        bb.putInt(0);                 // dwInitialFrames
        bb.putInt(1);                 // dwStreams
        bb.putInt(0);                 // dwSuggestedBufferSize
        bb.putInt(width);             // dwWidth
        bb.putInt(height);            // dwHeight
        bb.putInt(0);                 // dwReserved[0..3]
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        return bb.array();
    }

    /** The 56-byte AVIStreamHeader for a video stream. */
    private static byte[] strh(int width, int height, int frameCount) {
        ByteBuffer bb = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(fourCcInt("vids")); // fccType
        bb.putInt(fourCcInt(MJPG));   // fccHandler
        bb.putInt(0);                 // dwFlags
        bb.putShort((short) 0);       // wPriority
        bb.putShort((short) 0);       // wLanguage
        bb.putInt(0);                 // dwInitialFrames
        bb.putInt(1);                 // dwScale
        bb.putInt(1);                 // dwRate -> 1 frame per second
        bb.putInt(0);                 // dwStart
        bb.putInt(frameCount);        // dwLength
        bb.putInt(0);                 // dwSuggestedBufferSize
        bb.putInt(0);                 // dwQuality
        bb.putInt(0);                 // dwSampleSize
        bb.putShort((short) 0);       // rcFrame: left
        bb.putShort((short) 0);       // rcFrame: top
        bb.putShort((short) width);   // rcFrame: right
        bb.putShort((short) height);  // rcFrame: bottom
        return bb.array();
    }

    /** The 40-byte BITMAPINFOHEADER declaring the MJPG codec. */
    private static byte[] strf(int width, int height) {
        ByteBuffer bb = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(40);                // biSize
        bb.putInt(width);             // biWidth
        bb.putInt(height);            // biHeight
        bb.putShort((short) 1);       // biPlanes
        bb.putShort((short) 24);      // biBitCount
        bb.putInt(fourCcInt(MJPG));   // biCompression
        bb.putInt(0);                 // biSizeImage
        bb.putInt(0);                 // biXPelsPerMeter
        bb.putInt(0);                 // biYPelsPerMeter
        bb.putInt(0);                 // biClrUsed
        bb.putInt(0);                 // biClrImportant
        return bb.array();
    }

    /** Writes a LIST chunk (fourcc + size + list type + members). */
    private static void list(ByteArrayOutputStream out, String listType, byte[] members) {
        fourCc(out, "LIST");
        leInt(out, 4 + members.length);
        fourCc(out, listType);
        out.writeBytes(members);
    }

    /** Writes a leaf chunk, padded to an even length like RIFF requires. */
    private static void chunk(ByteArrayOutputStream out, String fourcc, byte[] payload) {
        fourCc(out, fourcc);
        leInt(out, payload.length);
        out.writeBytes(payload);
        if (payload.length % 2 != 0) {
            out.write(0);
        }
    }

    private static void fourCc(ByteArrayOutputStream out, String fourcc) {
        out.writeBytes(fourcc.getBytes(StandardCharsets.US_ASCII));
    }

    private static int fourCcInt(String fourcc) {
        int value = 0;
        byte[] bytes = fourcc.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            value |= (bytes[i] & 0xFF) << (8 * i);
        }
        return value;
    }

    private static void leInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}