package free.svoss.facesort;

import free.svoss.facesort.config.AppConfig;
import free.svoss.facesort.config.ConfigModel;
import free.svoss.facesort.db.Database;
import free.svoss.facesort.db.FaceDao;
import free.svoss.facesort.db.ImageDao;
import free.svoss.facesort.db.NameDao;
import free.svoss.facesort.db.NotDupeDao;
import free.svoss.facesort.service.ClusteringService;
import free.svoss.facesort.service.DeduplicationService;
import free.svoss.facesort.service.FaceAiService;
import free.svoss.facesort.service.FaceToNameService;
import free.svoss.facesort.service.ImportService;
import free.svoss.facesort.service.NamingService;
import free.svoss.facesort.service.ViewService;
import free.svoss.facesort.ui.DedupeView;
import free.svoss.facesort.ui.FaceNameView;
import free.svoss.facesort.ui.ImportView;
import free.svoss.facesort.ui.MainWindow;
import free.svoss.facesort.ui.NameFaceView;
import free.svoss.facesort.ui.RandomNameView;
import free.svoss.facesort.ui.SettingsView;
import free.svoss.facesort.ui.ViewView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX application entry point for Face Sort.
 *
 * <p>Loads configuration, opens the database, builds the DAO layer, creates
 * every service, wires the views into the main tab window and shows it.
 * If face recognition initialization fails, an error dialog is shown and the
 * application exits gracefully.</p>
 */
public class FaceSortApp extends Application {

    private static final String APP_TITLE = "Face Sort";

    private ConfigModel config;
    private Database database;
    private FaceAiService faceAiService;
    private ImportService importService;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 1. Load configuration (falls back to defaults when the file is absent).
        Path configPath = Path.of(AppConfig.DEFAULT_CONFIG_FILE);
        config = AppConfig.load(configPath);

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

        // 4. FaceAI engine. May download models on first run; fail with a dialog.
        try {
            faceAiService = new FaceAiService(config);
        } catch (Exception e) {
            showStartupError("FaceAI initialization failed: " + e.getMessage(), e);
            Platform.exit();
            return;
        }

        // 5. Services. One FaceAI service per import worker; the shared
        //    faceAiService stays dedicated to the other services.
        List<FaceAiService> importAiServices = new ArrayList<>();
        for (int i = 0; i < ConfigModel.MAX_IMPORT_THREADS; i++) {
            importAiServices.add(new FaceAiService(config));
        }
        importService = new ImportService(imageDao, faceDao, importAiServices, config);
        ClusteringService clusteringService = new ClusteringService(faceAiService, faceDao, config);
        NamingService namingService = new NamingService(clusteringService, faceAiService, faceDao, nameDao, imageDao, config);
        FaceToNameService faceToNameService = new FaceToNameService(faceAiService, faceDao, nameDao);
        DeduplicationService dedupService = new DeduplicationService(faceAiService, faceDao, nameDao, notDupeDao);
        ViewService viewService = new ViewService(faceAiService, faceDao, nameDao, imageDao);

        // 6. Views.
        ImportView importView = new ImportView(importService, config);
        NameFaceView nameFaceView = new NameFaceView(namingService);
        RandomNameView randomNameView = new RandomNameView(namingService);
        FaceNameView faceNameView = new FaceNameView(faceToNameService);
        DedupeView dedupeView = new DedupeView(dedupService);
        ViewView viewView = new ViewView(viewService);
        SettingsView settingsView = new SettingsView(config, configPath);

        // 7. Main window with the seven tabs.
        MainWindow mainWindow = new MainWindow(
                new Tab("Import", importView),
                new Tab("Name Face", nameFaceView),
                new Tab("Random Tag", randomNameView),
                new Tab("Face Name", faceNameView),
                new Tab("Deduplicate", dedupeView),
                new Tab("View", viewView),
                new Tab("Settings", settingsView));

        Scene scene = new Scene(mainWindow, 1200, 800);
        var cssResource = getClass().getResource("/css/styles.css");
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        }

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() throws Exception {
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
        alert.setHeaderText("Startup failed");
        alert.setContentText(message + System.lineSeparator() + cause);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}