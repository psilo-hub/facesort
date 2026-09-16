package free.svoss.facesort.ui;

import free.svoss.facesort.config.AppConfig;
import free.svoss.facesort.config.ConfigModel;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The Settings tab: edit detection, clustering, and HNSW parameters.
 *
 * <p>This is a pure form bound to a {@link ConfigModel} — no database access.
 * "Save" copies the current control values into the configuration and persists
 * it via {@link AppConfig#save(Path, ConfigModel)}. Every label shows a hover
 * tooltip explaining the setting, its default value and the effect of higher or
 * lower values. "Reset to defaults" restores the configuration to the built-in
 * defaults from {@link AppConfig#getDefault()} and persists them.</p>
 */
public class SettingsView extends BorderPane {

    private final ConfigModel config;
    private final Path configPath;

    private final Spinner<Integer> minBoundingBoxSize = intSpinner(20, 500, 10);
    private final Spinner<Double> minConfidence = doubleSpinner(0.1, 1.0, 0.05);
    private final Spinner<Integer> maxFacesPerImage = intSpinner(1, 100, 1);
    private final Spinner<Double> clusteringThreshold = doubleSpinner(0.1, 1.0, 0.05);
    private final Spinner<Integer> hnswM = intSpinner(4, 128, 4);
    private final Spinner<Integer> hnswEfConstruction = intSpinner(50, 1000, 50);
    private final Spinner<Integer> hnswEfSearch = intSpinner(10, 500, 10);
    private final Spinner<Integer> knnK = intSpinner(1, 100, 1);
    private final TextField faceaiCacheDir = new TextField();
    private final Spinner<Integer> thumbnailSize = intSpinner(64, 1024, 32);
    private final Spinner<Integer> maxImportThreads = intSpinner(1, ConfigModel.MAX_IMPORT_THREADS, 1);
    private final Spinner<Double> minNameSimilarity = doubleSpinner(0.0, 1.0, 0.05);

    private final Label statusLabel = new Label("");

    /**
     * Creates the Settings tab.
     *
     * @param config     the configuration object to edit and persist; must not be null
     * @param configPath path of the JSON config file to write on save; must not be null
     */
    public SettingsView(ConfigModel config, Path configPath) {
        this.config = config;
        this.configPath = configPath;
        buildUi();
        populateFromConfig();
    }

    /**
     * Builds the form grid and the Save / Reset action bar.
     */
    private void buildUi() {
        ConfigModel defaults = AppConfig.getDefault();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;
        addRow(grid, row++, "Min bounding box size:", minBoundingBoxSize,
                "Minimum side length (in pixels) of a detected face box; "
                        + "faces smaller than this are discarded during import.\n\n"
                        + "Default: " + defaults.getMinBoundingBoxSize() + " px.\n\n"
                        + "Higher values keep only larger (usually nearer) faces, so fewer "
                        + "false positives but small or distant faces are missed. "
                        + "Lower values also keep small or distant faces, which adds noise "
                        + "and slows processing.");

        addRow(grid, row++, "Min confidence:", minConfidence,
                "Minimum confidence score a detected face must have to be stored; "
                        + "detections scoring below this are discarded during import.\n\n"
                        + "Default: " + format(defaults.getMinConfidence()) + ".\n\n"
                        + "Higher values only accept very certain detections, cutting false "
                        + "positives but possibly skipping genuine faces. Lower values keep "
                        + "more faces, including uncertain ones.");

        addRow(grid, row++, "Max faces per image:", maxFacesPerImage,
                "Maximum number of faces stored per imported image. When an image "
                        + "contains more faces, only the most confident ones are kept.\n\n"
                        + "Default: " + defaults.getMaxFacesPerImage() + ".\n\n"
                        + "Higher values keep more faces from group photos at the cost of "
                        + "more data. Lower values store fewer faces and can drop people in "
                        + "crowded pictures.");

        addRow(grid, row++, "Clustering threshold:", clusteringThreshold,
                "Minimum similarity between two unnamed faces for them to be grouped "
                        + "into the same cluster in the \"Name Face\" tab.\n\n"
                        + "Default: " + format(defaults.getClusteringThreshold()) + ".\n\n"
                        + "Higher values group only very similar faces, giving smaller and "
                        + "more precise clusters, but a single person may be split into "
                        + "several clusters. Lower values merge more faces and can mix "
                        + "different people together.");

        addRow(grid, row++, "HNSW M:", hnswM,
                "Number of links kept per node in the HNSW index used to find similar "
                        + "faces during clustering.\n\n"
                        + "Default: " + defaults.getHnswM() + ".\n\n"
                        + "Higher values improve neighbor-search accuracy at the cost of "
                        + "more memory and a slower index build. Lower values build faster "
                        + "and use less memory but can miss true neighbors.");

        addRow(grid, row++, "HNSW efConstruction:", hnswEfConstruction,
                "How many candidate nodes HNSW considers while building each node's "
                        + "links. Trades index build time against search quality.\n\n"
                        + "Default: " + defaults.getHnswEfConstruction() + ".\n\n"
                        + "Higher values build a more accurate index but take longer and "
                        + "use more memory. Lower values build more quickly with a less "
                        + "accurate index.");

        addRow(grid, row++, "HNSW efSearch:", hnswEfSearch,
                "How many candidate nodes HNSW examines during each neighbor search "
                        + "performed at clustering time.\n\n"
                        + "Default: " + defaults.getHnswEfSearch() + ".\n\n"
                        + "Higher values give more accurate searches but run slower. "
                        + "Lower values run faster but can miss true neighbors.");

        addRow(grid, row++, "KNN K:", knnK,
                "Number of nearest neighbors queried for each face when linking faces "
                        + "into clusters.\n\n"
                        + "Default: " + defaults.getKnnK() + ".\n\n"
                        + "Higher values can connect more distant faces into a cluster, "
                        + "which is slower and may merge different people. Lower values "
                        + "run faster but only link very close faces.");

        addRow(grid, row++, "FaceAI cache dir:", faceaiCacheDir,
                "Directory where FaceAI stores the downloaded face-detection and "
                        + "recognition models. Models are fetched on first use and reused "
                        + "from this folder afterwards.\n\n"
                        + "Default: blank, which uses FaceAI's built-in cache location "
                        + "(e.g. ~/.djl.ai/cache).\n\n"
                        + "This setting has no higher/lower range: leave it blank for the "
                        + "default location or enter a path to control where models live.");
        faceaiCacheDir.setPromptText("Leave blank for the default cache location");

        addRow(grid, row, "Thumbnail size:", thumbnailSize,
                "Maximum width/height (in pixels) of the full-image thumbnail stored "
                        + "for each imported image.\n\n"
                        + "Default: " + defaults.getThumbnailSize() + " px.\n\n"
                        + "Higher values give sharper previews but larger database files. "
                        + "Lower values keep the database smaller at the cost of preview "
                        + "quality.");

        addRow(grid, ++row, "Max import threads:", maxImportThreads,
                "Number of parallel worker threads used when importing image files. "
                        + "Applies to the next import.\n\n"
                        + "Default: " + defaults.getMaxImportThreads() + ".\n\n"
                        + "Higher values import faster on multi-core machines but "
                        + "increase CPU and memory use. Lower values are gentler on the "
                        + "system but slower.");

        addRow(grid, ++row, "Min similarity for adding to a name:", minNameSimilarity,
                "Minimum similarity to a name's average embedding for a face to be "
                        + "offered when adding faces to an existing name in the "
                        + "\"Face Name\" tab.\n\n"
                        + "Default: " + format(defaults.getMinNameSimilarity()) + ".\n\n"
                        + "Higher values only allow very similar faces, avoiding wrong "
                        + "additions. Lower values offer more candidates but risk adding "
                        + "a different person.");

        Button saveButton = new Button("Save");
        saveButton.setDefaultButton(true);
        saveButton.setOnAction(e -> onSave());
        Button resetButton = new Button("Reset to defaults");
        resetButton.setOnAction(e -> onReset());

        HBox actions = new HBox(8, saveButton, resetButton, statusLabel);
        actions.setPadding(new Insets(0, 10, 10, 10));
        actions.setAlignment(Pos.CENTER_LEFT);

        setCenter(grid);
        setBottom(actions);
    }

    /**
     * Adds a labeled row to the settings grid. When {@code tooltipText} is
     * provided it is installed on the label so hovering shows the explanation.
     *
     * @param grid        the grid to extend
     * @param row         the row index
     * @param label       the control label
     * @param field       the input control
     * @param tooltipText the hover explanation; may be null
     */
    private static void addRow(GridPane grid, int row, String label, Region field,
                               String tooltipText) {
        Label labelNode = new Label(label);
        if (tooltipText != null && !tooltipText.isBlank()) {
            labelNode.setTooltip(tooltip(tooltipText));
        }
        grid.add(labelNode, 0, row);
        grid.add(field, 1, row);
    }

    /**
     * Builds a wrapped tooltip for a settings explanation.
     *
     * @param text the tooltip text
     * @return a tooltip that wraps its text within a maximum width
     */
    private static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }

    /**
     * Formats a decimal value without trailing zeros, using the root locale so
     * the decimal point is unambiguous.
     *
     * @param value the value to format
     * @return the formatted value
     */
    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Copies the current configuration values into the form controls.
     */
    private void populateFromConfig() {
        minBoundingBoxSize.getValueFactory().setValue(config.getMinBoundingBoxSize());
        minConfidence.getValueFactory().setValue(config.getMinConfidence());
        maxFacesPerImage.getValueFactory().setValue(config.getMaxFacesPerImage());
        clusteringThreshold.getValueFactory().setValue(config.getClusteringThreshold());
        hnswM.getValueFactory().setValue(config.getHnswM());
        hnswEfConstruction.getValueFactory().setValue(config.getHnswEfConstruction());
        hnswEfSearch.getValueFactory().setValue(config.getHnswEfSearch());
        knnK.getValueFactory().setValue(config.getKnnK());
        faceaiCacheDir.setText(config.getFaceaiCacheDir() == null ? "" : config.getFaceaiCacheDir());
        thumbnailSize.getValueFactory().setValue(config.getThumbnailSize());
        maxImportThreads.getValueFactory().setValue(config.getMaxImportThreads());
        minNameSimilarity.getValueFactory().setValue(config.getMinNameSimilarity());
    }

    /**
     * Copies the current form values into the configuration and persists it.
     */
    private void onSave() {
        config.setMinBoundingBoxSize(minBoundingBoxSize.getValue());
        config.setMinConfidence(minConfidence.getValue());
        config.setMaxFacesPerImage(maxFacesPerImage.getValue());
        config.setClusteringThreshold(clusteringThreshold.getValue());
        config.setHnswM(hnswM.getValue());
        config.setHnswEfConstruction(hnswEfConstruction.getValue());
        config.setHnswEfSearch(hnswEfSearch.getValue());
        config.setKnnK(knnK.getValue());
        String cacheDir = faceaiCacheDir.getText().trim();
        config.setFaceaiCacheDir(cacheDir.isEmpty() ? null : cacheDir);
        config.setThumbnailSize(thumbnailSize.getValue());
        config.setMaxImportThreads(maxImportThreads.getValue());
        config.setMinNameSimilarity(minNameSimilarity.getValue());

        try {
            AppConfig.save(configPath, config);
            statusLabel.setText("Saved");
        } catch (IOException e) {
            statusLabel.setText("Save failed: " + e.getMessage());
        }
    }

    /**
     * Restores the configuration to the built-in defaults and persists them.
     */
    private void onReset() {
        ConfigModel defaults = AppConfig.getDefault();
        config.setMinBoundingBoxSize(defaults.getMinBoundingBoxSize());
        config.setMinConfidence(defaults.getMinConfidence());
        config.setMaxFacesPerImage(defaults.getMaxFacesPerImage());
        config.setClusteringThreshold(defaults.getClusteringThreshold());
        config.setHnswM(defaults.getHnswM());
        config.setHnswEfConstruction(defaults.getHnswEfConstruction());
        config.setHnswEfSearch(defaults.getHnswEfSearch());
        config.setKnnK(defaults.getKnnK());
        config.setFaceaiCacheDir(defaults.getFaceaiCacheDir());
        config.setThumbnailSize(defaults.getThumbnailSize());
        config.setMaxImportThreads(defaults.getMaxImportThreads());
        config.setMinNameSimilarity(defaults.getMinNameSimilarity());
        populateFromConfig();

        try {
            AppConfig.save(configPath, config);
            statusLabel.setText("Reset to defaults and saved");
        } catch (IOException e) {
            statusLabel.setText("Reset failed to save: " + e.getMessage());
        }
    }

    /**
     * Builds a bounded integer spinner with the given range and step.
     *
     * @param min  lowest value
     * @param max  highest value
     * @param step increment between values
     * @return a configured integer spinner
     */
    private static Spinner<Integer> intSpinner(int min, int max, int step) {
        Spinner<Integer> spinner = new Spinner<>(min, max, min, step);
        spinner.setEditable(true);
        return spinner;
    }

    /**
     * Builds a bounded double spinner with the given range and step.
     *
     * @param min  lowest value
     * @param max  highest value
     * @param step increment between values
     * @return a configured double spinner
     */
    private static Spinner<Double> doubleSpinner(double min, double max, double step) {
        Spinner<Double> spinner = new Spinner<>(min, max, min, step);
        spinner.setEditable(true);
        return spinner;
    }
}