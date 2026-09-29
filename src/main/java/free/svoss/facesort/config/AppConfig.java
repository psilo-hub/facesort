package free.svoss.facesort.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves the application configuration as a JSON file.
 *
 * <p>The default config file location is {@code config/facesort-config.json}
 * relative to the application working directory. If the file is absent,
 * {@link #load(Path)} falls back to {@link #getDefault()} values. Unknown keys
 * are ignored, so a config written by a different build (or hand-edited) still
 * loads with the values this build understands plus the defaults for the rest.</p>
 *
 * <p>{@link #save(Path, ConfigModel)} stages the write in a temporary file and
 * moves it into place atomically, so an interrupted save can never leave a
 * truncated config behind — the user always keeps a loadable copy of their
 * settings.</p>
 */
public final class AppConfig {

    /** Default config directory relative to the app directory. */
    public static final String DEFAULT_CONFIG_DIR = "config";

    /** Default config file location relative to the app directory. */
    public static final String DEFAULT_CONFIG_FILE = DEFAULT_CONFIG_DIR + "/facesort-config.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AppConfig() {
        // Utility class: static methods only.
    }

    /**
     * Returns a new {@link ConfigModel} populated with application defaults.
     */
    public static ConfigModel getDefault() {
        return new ConfigModel();
    }

    /**
     * Loads configuration from the given JSON file.
     *
     * <p>Keys this build does not know are ignored rather than rejected, so a
     * config file written by a newer build — or one a user hand-edited — never
     * prevents the application from starting.</p>
     *
     * @param configFile path to the config file
     * @return the parsed {@link ConfigModel}; defaults if the file is absent
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public static ConfigModel load(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            return getDefault();
        }
        ConfigModel config = MAPPER.readValue(configFile.toFile(), ConfigModel.class);
        config.normalize();
        return config;
    }

    /**
     * Writes the given configuration to the given JSON file, creating parent
     * directories as needed.
     *
     * <p>The config is serialized into a temporary file in the same directory
     * and then moved onto the target atomically. A crash, a full disk or a
     * serialization failure therefore leaves the previous config untouched
     * instead of truncating it, so a broken write can never cost the user their
     * settings. Falls back to a non-atomic move where the filesystem does not
     * support atomic moves.</p>
     *
     * @param configFile path to write the config to
     * @param config the configuration to persist
     * @throws IOException if the file cannot be written
     */
    public static void save(Path configFile, ConfigModel config) throws IOException {
        Path parent = configFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "facesort-config", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), config);
            try {
                Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}