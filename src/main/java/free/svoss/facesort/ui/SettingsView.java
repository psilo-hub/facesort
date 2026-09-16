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

/**
 * The Settings tab: edit detection, clustering, and HNSW parameters.
 *
 * <p>This is a pure form bound to a {@link ConfigModel} — no database access.
 * "Save" copies the current control values into the configuration and persists
 * it via {@link AppConfig#save(Path, ConfigModel)}; "Reset to defaults"
 * repopulates the form from {@link AppConfig#getDefault()} without persisting,
 * so the user can review the defaults before saving.</p>
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
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;
        addRow(grid, row++, "Min bounding box size:", minBoundingBoxSize);
        addRow(grid, row++, "Min confidence:", minConfidence);
        addRow(grid, row++, "Max faces per image:", maxFacesPerImage);
        addRow(grid, row++, "Clustering threshold:", clusteringThreshold);
        addRow(grid, row++, "HNSW M:", hnswM);
        addRow(grid, row++, "HNSW efConstruction:", hnswEfConstruction);
        addRow(grid, row++, "HNSW efSearch:", hnswEfSearch);
        addRow(grid, row++, "KNN K:", knnK);

        addRow(grid, row++, "FaceAI cache dir:", faceaiCacheDir);
        faceaiCacheDir.setPromptText("Leave blank for the default cache location");

        addRow(grid, row, "Thumbnail size:", thumbnailSize);

        addRow(grid, ++row, "Max import threads:", maxImportThreads);
        maxImportThreads.setTooltip(new Tooltip(
                "Number of parallel workers used when importing images; applies to the next import."));

        addRow(grid, ++row, "Min similarity for adding to a name:", minNameSimilarity);
        minNameSimilarity.setTooltip(new Tooltip(
                "When adding faces to an existing name, only faces whose similarity "
                        + "to the name's average embedding is at least this value are offered."));

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
     * Adds a labeled row to the settings grid.
     *
     * @param grid  the grid to extend
     * @param row   the row index
     * @param label the control label
     * @param field the input control
     */
    private static void addRow(GridPane grid, int row, String label, Region field) {
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
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
     * Repopulates the form from the built-in defaults without persisting —
     * the user still has to press Save.
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
        statusLabel.setText("Defaults loaded - press Save to persist");
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