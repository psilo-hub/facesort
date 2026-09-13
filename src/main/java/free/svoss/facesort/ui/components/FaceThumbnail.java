package free.svoss.facesort.ui.components;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * A reusable control that displays a face's JPEG thumbnail.
 *
 * <p>Sub-images of faces are stored in the database as JPEG byte arrays (see
 * {@code faces.sub_image_jpg}). This control decodes those bytes into a
 * JavaFX {@link Image} and renders it scaled to a fixed size, preserving the
 * aspect ratio.</p>
 *
 * <p>The control carries the {@code face-thumbnail} CSS style class and
 * toggles the {@code selected} style class to highlight the currently
 * selected face (styled via {@code styles.css}).</p>
 */
public class FaceThumbnail extends StackPane {

    private static final String STYLE_CLASS = "face-thumbnail";
    private static final String SELECTED_STYLE_CLASS = "selected";

    private final ImageView imageView;
    private boolean selected;

    /**
     * Creates a thumbnail by decoding the given JPEG bytes.
     *
     * <p>The bytes are decoded synchronously; empty or undecodable input
     * raises an {@link IllegalArgumentException} immediately.</p>
     *
     * @param jpegBytes JPEG-encoded image bytes; must not be null or empty
     * @param size      the maximum width/height in pixels; must be positive
     */
    public FaceThumbnail(byte[] jpegBytes, double size) {
        this(decode(jpegBytes), size);
    }

    /**
     * Creates a thumbnail displaying the given image.
     *
     * @param image the face image; must not be null
     * @param size  the maximum width/height in pixels; must be positive
     */
    public FaceThumbnail(Image image, double size) {
        this.imageView = new ImageView(Objects.requireNonNull(image, "image"));
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, got " + size);
        }
        getStyleClass().add(STYLE_CLASS);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitWidth(size);
        imageView.setFitHeight(size);
        getChildren().add(imageView);
    }

    /**
     * Replaces the displayed thumbnail with the given JPEG bytes.
     *
     * @param jpegBytes new JPEG-encoded image bytes; must not be null or empty
     */
    public void setImageBytes(byte[] jpegBytes) {
        imageView.setImage(decode(jpegBytes));
    }

    /**
     * Toggles the selected visual state.
     *
     * <p>When selected, the {@code selected} style class is added (drawn
     * with a highlighted border per {@code styles.css}); when deselected,
     * the class is removed.</p>
     *
     * @param selected {@code true} to highlight, {@code false} to clear
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
        if (selected) {
            if (!getStyleClass().contains(SELECTED_STYLE_CLASS)) {
                getStyleClass().add(SELECTED_STYLE_CLASS);
            }
        } else {
            getStyleClass().remove(SELECTED_STYLE_CLASS);
        }
    }

    /**
     * Returns whether this thumbnail is currently in the selected state.
     *
     * @return {@code true} if selected
     */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Decodes a JPEG byte array into a JavaFX {@link Image}.
     * Synchronous decoding ensures errors are raised immediately.
     *
     * @param jpegBytes JPEG-encoded bytes; must not be null
     * @return the decoded image
     * @throws IllegalArgumentException if bytes are empty or undecodable
     */
    private static Image decode(byte[] jpegBytes) {
        Objects.requireNonNull(jpegBytes, "jpegBytes");
        if (jpegBytes.length == 0) {
            throw new IllegalArgumentException("jpegBytes must not be empty");
        }
        // InputStream constructors always decode synchronously (foreground).
        Image image = new Image(
                new ByteArrayInputStream(jpegBytes),
                -1, -1,   // natural size — ImageView handles scaling
                true,      // preserveRatio
                true       // smooth
        );
        if (image.isError()) {
            throw new IllegalArgumentException(
                    "Failed to decode face image: " + image.getException(),
                    image.getException());
        }
        return image;
    }
}