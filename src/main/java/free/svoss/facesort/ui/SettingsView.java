package free.svoss.facesort.ui;

import free.svoss.facesort.config.AppConfig;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.i18n.I18n;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
    private final Runnable onLanguageChanged;

    private final ComboBox<String> languageBox = new ComboBox<>();
    private final Spinner<Integer> minBoundingBoxSize = intSpinner(20, 500, 10);
    private final Spinner<Double> minConfidence = doubleSpinner(0.1, 1.0, 0.05);
    private final Spinner<Integer> maxFacesPerImage = intSpinner(1, 100, 1);
    private final Spinner<Integer> maxDetectionDimension = intSpinner(400, 4000, 100);
    private final Spinner<Double> clusteringThreshold = doubleSpinner(0.1, 1.0, 0.05);
    private final Spinner<Integer> hnswM = intSpinner(4, 128, 4);
    private final Spinner<Integer> hnswEfConstruction = intSpinner(50, 1000, 50);
    private final Spinner<Integer> hnswEfSearch = intSpinner(10, 500, 10);
    private final Spinner<Integer> knnK = intSpinner(1, 100, 1);
    private final TextField faceaiCacheDir = new TextField();
    private final Spinner<Integer> thumbnailSize = intSpinner(64, 1024, 32);
    private final Spinner<Integer> maxImportThreads = intSpinner(1, ConfigModel.MAX_IMPORT_THREADS, 1);
    private final TextField minNameSimilarity = new TextField();
    private final TextField faceNameMaxImages = new TextField();
    private final CheckBox updateCheckEnabled = new CheckBox();

    private final Label statusLabel = new Label("");

    /**
     * Creates the Settings tab.
     *
     * @param config           the configuration object to edit and persist; must not be null
     * @param configPath       path of the JSON config file to write on save; must not be null
     * @param onLanguageChanged callback fired after the user picked a new UI language; the
     *                          language selection has been applied and saved by then
     */
    public SettingsView(ConfigModel config, Path configPath, Runnable onLanguageChanged) {
        this.config = config;
        this.configPath = configPath;
        this.onLanguageChanged = onLanguageChanged;
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

        languageBox.getItems().setAll(I18n.supportedLanguages().stream()
                .map(I18n.Language::displayName).toList());
        languageBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                onLanguageSelected();
            }
        });
        addRow(grid, 0, I18n.get("settings.language"), languageBox,
                I18n.get("settings.tooltip.language"));

        int row = 1;
        addRow(grid, row++, I18n.get("settings.minBoundingBoxSize"), minBoundingBoxSize,
                I18n.format("settings.tooltip.minBoundingBoxSize",
                        defaults.getMinBoundingBoxSize()));

        addRow(grid, row++, I18n.get("settings.minConfidence"), minConfidence,
                I18n.format("settings.tooltip.minConfidence",
                        format(defaults.getMinConfidence())));

        addRow(grid, row++, I18n.get("settings.maxFacesPerImage"), maxFacesPerImage,
                I18n.format("settings.tooltip.maxFacesPerImage",
                        defaults.getMaxFacesPerImage()));

        addRow(grid, row++, I18n.get("settings.clusteringThreshold"), clusteringThreshold,
                I18n.format("settings.tooltip.clusteringThreshold",
                        format(defaults.getClusteringThreshold())));

        addRow(grid, row++, I18n.get("settings.hnswM"), hnswM,
                I18n.format("settings.tooltip.hnswM", defaults.getHnswM()));

        addRow(grid, row++, I18n.get("settings.hnswEfConstruction"), hnswEfConstruction,
                I18n.format("settings.tooltip.hnswEfConstruction",
                        defaults.getHnswEfConstruction()));

        addRow(grid, row++, I18n.get("settings.hnswEfSearch"), hnswEfSearch,
                I18n.format("settings.tooltip.hnswEfSearch", defaults.getHnswEfSearch()));

        addRow(grid, row++, I18n.get("settings.knnK"), knnK,
                I18n.format("settings.tooltip.knnK", defaults.getKnnK()));

        addRow(grid, row++, I18n.get("settings.faceaiCacheDir"), faceaiCacheDir,
                I18n.get("settings.tooltip.faceaiCacheDir"));
        faceaiCacheDir.setPromptText(I18n.get("settings.faceaiCacheDirPrompt"));

        addRow(grid, row++, I18n.get("settings.maxDetectionDimension"), maxDetectionDimension,
                I18n.format("settings.tooltip.maxDetectionDimension",
                        defaults.getMaxDetectionDimension()));

        addRow(grid, row++, I18n.get("settings.thumbnailSize"), thumbnailSize,
                I18n.format("settings.tooltip.thumbnailSize", defaults.getThumbnailSize()));

        addRow(grid, row++, I18n.get("settings.maxImportThreads"), maxImportThreads,
                I18n.format("settings.tooltip.maxImportThreads",
                        defaults.getMaxImportThreads()));

        addRow(grid, row++, I18n.get("settings.minNameSimilarity"), minNameSimilarity,
                I18n.format("settings.tooltip.minNameSimilarity",
                        format(defaults.getMinNameSimilarity())));

        addRow(grid, row++, I18n.get("settings.faceNameMaxImages"), faceNameMaxImages,
                I18n.format("settings.tooltip.faceNameMaxImages",
                        defaults.getFaceNameMaxImages()));

        addRow(grid, row++, I18n.get("settings.updateCheckEnabled"), updateCheckEnabled,
                I18n.format("settings.tooltip.updateCheckEnabled",
                        defaults.isUpdateCheckEnabled()
                                ? I18n.get("settings.enabled")
                                : I18n.get("settings.disabled")));

        Button saveButton = new Button(I18n.get("settings.save"));
        saveButton.setDefaultButton(true);
        saveButton.setOnAction(e -> onSave());
        Button resetButton = new Button(I18n.get("settings.reset"));
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
        String current = config.getLanguage() == null
                ? ConfigModel.DEFAULT_LANGUAGE : config.getLanguage();
        I18n.supportedLanguages().stream()
                .filter(lang -> lang.code().equals(current))
                .findFirst()
                .ifPresent(lang -> languageBox.setValue(lang.displayName()));
        minBoundingBoxSize.getValueFactory().setValue(config.getMinBoundingBoxSize());
        minConfidence.getValueFactory().setValue(config.getMinConfidence());
        maxFacesPerImage.getValueFactory().setValue(config.getMaxFacesPerImage());
        maxDetectionDimension.getValueFactory().setValue(config.getMaxDetectionDimension());
        clusteringThreshold.getValueFactory().setValue(config.getClusteringThreshold());
        hnswM.getValueFactory().setValue(config.getHnswM());
        hnswEfConstruction.getValueFactory().setValue(config.getHnswEfConstruction());
        hnswEfSearch.getValueFactory().setValue(config.getHnswEfSearch());
        knnK.getValueFactory().setValue(config.getKnnK());
        faceaiCacheDir.setText(config.getFaceaiCacheDir() == null ? "" : config.getFaceaiCacheDir());
        thumbnailSize.getValueFactory().setValue(config.getThumbnailSize());
        maxImportThreads.getValueFactory().setValue(config.getMaxImportThreads());
        minNameSimilarity.setText(String.valueOf(config.getMinNameSimilarity()));
        faceNameMaxImages.setText(String.valueOf(config.getFaceNameMaxImages()));
        updateCheckEnabled.setSelected(config.isUpdateCheckEnabled());
    }

    /**
     * Applies a newly selected UI language: stores it in the configuration,
     * persists it best-effort, switches {@link I18n} and rebuilds the window.
     */
    private void onLanguageSelected() {
        String selected = languageBox.getValue();
        String code = I18n.supportedLanguages().stream()
                .filter(lang -> lang.displayName().equals(selected))
                .map(I18n.Language::code)
                .findFirst()
                .orElse(ConfigModel.DEFAULT_LANGUAGE);
        String current = config.getLanguage() == null
                ? ConfigModel.DEFAULT_LANGUAGE : config.getLanguage();
        if (code.equals(current)) {
            return;
        }
        config.setLanguage(code);
        I18n.setLocale(I18n.localeFor(code));
        try {
            AppConfig.save(configPath, config);
        } catch (IOException e) {
            statusLabel.setText(I18n.format("settings.saveFailed", e.getMessage()));
        }
        if (onLanguageChanged != null) {
            onLanguageChanged.run();
        }
        statusLabel.setText(I18n.get("settings.saved"));
    }

    /**
     * Copies the current form values into the configuration and persists it.
     * Numeric text fields are validated before saving; on invalid input the
     * form is left untouched and an error is shown in the status bar.
     */
    private void onSave() {
        Double minSimilarity = parseUnitSimilarity(minNameSimilarity.getText());
        if (minSimilarity == null) {
            statusLabel.setText(I18n.get("settings.error.minSimilarity"));
            return;
        }
        Integer maxImages = parsePositiveInteger(faceNameMaxImages.getText());
        if (maxImages == null) {
            statusLabel.setText(I18n.get("settings.error.maxImages"));
            return;
        }

        config.setMinBoundingBoxSize(minBoundingBoxSize.getValue());
        config.setMinConfidence(minConfidence.getValue());
        config.setMaxFacesPerImage(maxFacesPerImage.getValue());
        config.setMaxDetectionDimension(maxDetectionDimension.getValue());
        config.setClusteringThreshold(clusteringThreshold.getValue());
        config.setHnswM(hnswM.getValue());
        config.setHnswEfConstruction(hnswEfConstruction.getValue());
        config.setHnswEfSearch(hnswEfSearch.getValue());
        config.setKnnK(knnK.getValue());
        String cacheDir = faceaiCacheDir.getText().trim();
        config.setFaceaiCacheDir(cacheDir.isEmpty() ? null : cacheDir);
        config.setThumbnailSize(thumbnailSize.getValue());
        config.setMaxImportThreads(maxImportThreads.getValue());
        config.setMinNameSimilarity(minSimilarity);
        config.setFaceNameMaxImages(maxImages);
        config.setUpdateCheckEnabled(updateCheckEnabled.isSelected());

        try {
            AppConfig.save(configPath, config);
            statusLabel.setText(I18n.get("settings.saved"));
        } catch (IOException e) {
            statusLabel.setText(I18n.format("settings.saveFailed", e.getMessage()));
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
        config.setMaxDetectionDimension(defaults.getMaxDetectionDimension());
        config.setClusteringThreshold(defaults.getClusteringThreshold());
        config.setHnswM(defaults.getHnswM());
        config.setHnswEfConstruction(defaults.getHnswEfConstruction());
        config.setHnswEfSearch(defaults.getHnswEfSearch());
        config.setKnnK(defaults.getKnnK());
        config.setFaceaiCacheDir(defaults.getFaceaiCacheDir());
        config.setThumbnailSize(defaults.getThumbnailSize());
        config.setMaxImportThreads(defaults.getMaxImportThreads());
        config.setMinNameSimilarity(defaults.getMinNameSimilarity());
        config.setFaceNameMaxImages(defaults.getFaceNameMaxImages());
        config.setUpdateCheckEnabled(defaults.isUpdateCheckEnabled());
        populateFromConfig();

        try {
            AppConfig.save(configPath, config);
            statusLabel.setText(I18n.get("settings.resetDone"));
        } catch (IOException e) {
            statusLabel.setText(I18n.format("settings.resetFailed", e.getMessage()));
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

    /**
     * Parses the given text as a {@code double} in the closed range [0.0, 1.0].
     *
     * @param text the text to parse
     * @return the parsed value, or {@code null} if the text is not a number in range
     */
    private static Double parseUnitSimilarity(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            if (value < 0.0 || value > 1.0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the given text as a positive {@code int}.
     *
     * @param text the text to parse
     * @return the parsed value, or {@code null} if not a positive integer
     */
    private static Integer parsePositiveInteger(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value <= 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}