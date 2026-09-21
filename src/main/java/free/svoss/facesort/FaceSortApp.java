package free.svoss.facesort;

import free.svoss.facesort.config.AppConfig;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotDupeDao;
import free.svoss.facesort.db.VideoDao;
import free.svoss.facesort.i18n.I18n;
import free.svoss.facesort.service.ClusteringService;
import free.svoss.facesort.service.DeduplicationService;
import free.svoss.facesort.service.FaceAiService;
import free.svoss.facesort.service.FaceToNameService;
import free.svoss.facesort.service.ImportService;
import free.svoss.facesort.service.NamingService;
import free.svoss.facesort.service.VideoImportService;
import free.svoss.facesort.service.ViewService;
import free.svoss.facesort.ui.DedupeView;
import free.svoss.facesort.ui.FaceNameView;
import free.svoss.facesort.ui.FeedbackView;
import free.svoss.facesort.ui.ImportView;
import free.svoss.facesort.ui.MainWindow;
import free.svoss.facesort.ui.ModelDownloadView;
import free.svoss.facesort.ui.NameFaceView;
import free.svoss.facesort.ui.RandomNameView;
import free.svoss.facesort.ui.SettingsView;
import free.svoss.facesort.ui.ViewView;
import free.svoss.facesort.update.UpdateChecker;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX application entry point for Face Sort.
 *
 * <p>Loads configuration, opens the database, builds the DAO layer, creates
 * every service, wires the views into the main tab window and shows it.
 * If face recognition initialization fails, an error dialog is shown and the
 * application exits gracefully.</p>
 *
 * <p>On the first start, when the FaceAI models have not been downloaded yet,
 * a model-download frame is shown first so the user sees which file is being
 * downloaded and where it is stored; the main window is built once the models
 * are ready.</p>
 *
 * <p>When the user changes the UI language in the settings, the main window is
 * rebuilt from the same services so every tab reflects the new language while
 * keeping the currently selected tab selected.</p>
 */
public class FaceSortApp extends Application {

    private static final Logger LOG = Logger.getLogger(FaceSortApp.class.getName());

    private static final String APP_TITLE = "Face Sort";
    private static final double DOWNLOAD_SCENE_WIDTH = 680;
    private static final double DOWNLOAD_SCENE_HEIGHT = 520;

    private ConfigModel config;
    private Path configPath;
    private Stage primaryStage;
    private int lastSelectedTabIndex;

    private Database database;
    private FaceAiService faceAiService;
    private ImportService importService;
    private VideoImportService videoImportService;
    private ClusteringService clusteringService;
    private NamingService namingService;
    private FaceToNameService faceToNameService;
    private DeduplicationService dedupService;
    private ViewService viewService;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        try {
            // 0. Register the bundled fonts (tab titles render identically
            //    on every system).
            loadBundledFonts();

            // 0a. Load configuration (falls back to defaults when the file is absent).
            configPath = Path.of(AppConfig.DEFAULT_CONFIG_FILE);
            config = AppConfig.load(configPath);
            I18n.setLocale(I18n.localeFor(config.getLanguage()));

            // 1. Background update check on a daemon thread; never blocks startup.
            //    Skipped when the user disabled it in the settings.
            if (config.isUpdateCheckEnabled()) {
                new UpdateChecker(Path.of("config")).startInBackground();
            }

            // 2. On the very first start the FaceAI models are not cached yet.
            //    Download them first and show a frame so the user can watch progress.
            if (FaceAiService.modelsDownloaded(config)) {
                showMainWindow(primaryStage);
            } else {
                startModelDownload(primaryStage);
            }
        } catch (Exception e) {
            showStartupError(I18n.format("app.startupFailed", e.getMessage()), e);
            Platform.exit();
        }
    }

    /**
     * Shows the model-download frame and runs the FaceAI model download in the
     * background. When it finishes the main window is built and shown; on
     * failure an error dialog is shown and the application exits.
     *
     * @param primaryStage the primary stage to display the frame in
     */
    private void startModelDownload(Stage primaryStage) {
        String destination = FaceAiService.toFaceAIConfig(config)
                .resolvedCacheDir().getAbsolutePath();
        ModelDownloadView downloadView = new ModelDownloadView(destination);

        Scene downloadScene = new Scene(downloadView, DOWNLOAD_SCENE_WIDTH, DOWNLOAD_SCENE_HEIGHT);
        var cssResource = getClass().getResource("/css/styles.css");
        if (cssResource != null) {
            downloadScene.getStylesheets().add(cssResource.toExternalForm());
        }

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(downloadScene);
        primaryStage.show();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                FaceAiService.downloadModelsIfNecessary(config, downloadView.listener());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            try {
                showMainWindow(primaryStage);
            } catch (Exception ex) {
                showStartupError(I18n.format("app.initFailed", ex.getMessage()), ex);
                Platform.exit();
            }
        });
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            showStartupError(I18n.format("app.modelDownloadFailed",
                    error != null ? error.getMessage() : I18n.get("app.unknownError")), error);
            Platform.exit();
        });

        Thread thread = new Thread(task, "model-download");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Opens the database and builds the service layer once. Must be called on
     * the JavaFX application thread.
     *
     * @param primaryStage the primary stage to display the main window in
     */
    private void showMainWindow(Stage primaryStage) throws Exception {
        // 2. Open the database under config/, creating the directory if needed.
        Path configDir = Path.of("config");
        Files.createDirectories(configDir);
        Path dbPath = configDir.resolve(Path.of(config.getDbName()));
        database = new Database(dbPath);
        Connection connection = database.getConnection();

        // 3. DAO layer.
        ImageDao imageDao = new ImageDao(connection);
        FaceDao faceDao = new FaceDao(connection);
        NameDao nameDao = new NameDao(connection);
        NotDupeDao notDupeDao = new NotDupeDao(connection);
        VideoDao videoDao = new VideoDao(connection);

        // 4. FaceAI engine. Models are already cached at this point (downloaded
        //    on first start); they are loaded lazily on first use.
        try {
            faceAiService = new FaceAiService(config);
        } catch (Exception e) {
            showStartupError(I18n.format("app.faceaiInitFailed", e.getMessage()), e);
            Platform.exit();
            return;
        }

        // 5. Services. One FaceAI service per import worker; the shared
        //    faceAiService stays dedicated to the other services. The video
        //    import reuses the same worker services because its phase always
        //    runs after the photo phase inside one Task, so the two never
        //    detect concurrently.
        List<FaceAiService> importAiServices = new ArrayList<>();
        for (int i = 0; i < ConfigModel.MAX_IMPORT_THREADS; i++) {
            importAiServices.add(new FaceAiService(config));
        }
        importService = new ImportService(imageDao, faceDao, importAiServices, config);
        videoImportService = new VideoImportService(imageDao, faceDao, videoDao,
                importAiServices, config);
        clusteringService = new ClusteringService(faceAiService, faceDao, config);
        namingService = new NamingService(clusteringService, faceAiService, faceDao, nameDao, imageDao, videoDao, config);
        faceToNameService = new FaceToNameService(faceAiService, faceDao, nameDao, imageDao, videoDao, config);
        dedupService = new DeduplicationService(faceAiService, faceDao, nameDao, notDupeDao, imageDao, videoDao);
        viewService = new ViewService(faceAiService, faceDao, nameDao, imageDao, videoDao);

        buildMainWindowUi();
    }

    /**
     * Rebuilds the main tab window from the existing services. Used on startup
     * and whenever the UI language changes, so every tab picks up the new
     * language. Must be called on the JavaFX application thread.
     */
    private void buildMainWindowUi() {
        // 6. Views.
        ImportView importView = new ImportView(importService, videoImportService, config);
        NameFaceView nameFaceView = new NameFaceView(namingService);
        RandomNameView randomNameView = new RandomNameView(namingService);
        FaceNameView faceNameView = new FaceNameView(faceToNameService, config);
        DedupeView dedupeView = new DedupeView(dedupService);
        ViewView viewView = new ViewView(viewService);
        SettingsView settingsView = new SettingsView(config, configPath, this::changeLanguage);
        FeedbackView feedbackView = new FeedbackView();

        // 7. Main window with the eight tabs.
        MainWindow mainWindow = new MainWindow(
                tab("tab.import", importView),
                tab("tab.nameFace", nameFaceView),
                tab("tab.randomName", randomNameView),
                tab("tab.faceName", faceNameView),
                tab("tab.deduplicate", dedupeView),
                tab("tab.view", viewView),
                tab("tab.settings", settingsView),
                tab("tab.feedback", feedbackView));

        int selected = lastSelectedTabIndex;
        mainWindow.getSelectionModel().selectedIndexProperty().addListener(
                (obs, oldIndex, newIndex) -> lastSelectedTabIndex = newIndex.intValue());

        Scene scene = new Scene(mainWindow, 1200, 800);
        var cssResource = getClass().getResource("/css/styles.css");
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        }

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        if (selected > 0 && selected < mainWindow.getTabs().size()) {
            mainWindow.getSelectionModel().select(selected);
        }
        primaryStage.show();
    }

    /**
     * Loads the fonts bundled with the application ({@code Noto Sans} for the
     * tab text and {@code Noto Emoji} for the tab emoji glyphs) so the tab
     * headers look the same on every system, independent of the fonts the
     * platform happens to ship with.
     */
    private static void loadBundledFonts() {
        loadBundledFont("/fonts/notosans/NotoSans.ttf");
        loadBundledFont("/fonts/notoemoji/NotoEmoji.ttf");
    }

    private static void loadBundledFont(String resource) {
        try (InputStream in = FaceSortApp.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.log(Level.WARNING, "Bundled font resource missing: {0}", resource);
                return;
            }
            if (Font.loadFont(in, 14) == null) {
                LOG.log(Level.WARNING, "Bundled font could not be loaded: {0}", resource);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Bundled font not readable: {0} ({1})",
                    new Object[]{resource, e.getMessage()});
        }
    }

    /**
     * Builds a tab whose title is drawn with the bundled fonts — the emoji
     * glyph in {@code Noto Emoji}, the label text in {@code Noto Sans} — so the
     * tab headers look identical on every system. Falls back to a single label
     * with the full title when the translated string has no emoji prefix.
     *
     * @param titleKey i18n key of the tab title ("emoji text")
     * @param content  the tab content
     * @return the tab
     */
    private static Tab tab(String titleKey, Node content) {
        String title = I18n.get(titleKey);
        String[] parts = title.split(" ", 2);
        Tab tab = new Tab(null, content);
        if (parts.length < 2) {
            Label text = new Label(title);
            text.getStyleClass().add("tab-title-text");
            tab.setGraphic(text);
            return tab;
        }
        Label emoji = new Label(parts[0]);
        emoji.getStyleClass().add("tab-title-emoji");
        // The emoji is the label graphic so it stays baseline-aligned with the
        // text; each part is drawn with its bundled font.
        Label titleLabel = new Label(parts[1], emoji);
        titleLabel.getStyleClass().add("tab-title-text");
        titleLabel.setContentDisplay(ContentDisplay.LEFT);
        titleLabel.setGraphicTextGap(8);
        tab.setGraphic(titleLabel);
        return tab;
    }

    /**
     * Rebuilds the main window after the user changed the UI language. The
     * language is already saved and applied to {@link I18n} by the settings
     * view before this callback fires.
     */
    private void changeLanguage() {
        buildMainWindowUi();
    }

    @Override
    public void stop() throws Exception {
        // videoImportService shares the import worker FaceAI services with
        // importService, so closing importService releases them exactly once.
        if (importService != null) {
            importService.close();
        }
        if (faceAiService != null) {
            faceAiService.close();
        }
        if (database != null) {
            database.close();
        }
    }

    /**
     * Shows a startup error dialog before the UI is available.
     */
    private void showStartupError(String message, Throwable cause) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(APP_TITLE);
        alert.setHeaderText(I18n.get("app.startupFailedHeader"));
        alert.setContentText(message + System.lineSeparator() + cause);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}