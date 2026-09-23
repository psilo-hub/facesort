package free.svoss.facesort.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves the application configuration as a JSON file.
 *
 * <p>The default config file location is {@code config/facesort-config.json}
 * relative to the application working directory. If the file is absent,
 * {@link #load(Path)} falls back to {@link #getDefault()} values.</p>
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
     * @param configFile path to write the config to
     * @param config the configuration to persist
     * @throws IOException if the file cannot be written
     */
    public static void save(Path configFile, ConfigModel config) throws IOException {
        Path parent = configFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(configFile.toFile(), config);
    }
}