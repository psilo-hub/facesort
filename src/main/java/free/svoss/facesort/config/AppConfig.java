package free.svoss.facesort.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;

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
        return ConfigModel.defaults();
    }

    /**
     * Loads configuration from the given JSON file.
     *
     * <p>Keys this build does not know are ignored rather than rejected, and
     * keys the file does not contain keep their defaults, so a config file
     * written by a newer build — or one a user hand-edited — never prevents
     * the application from starting.</p>
     *
     * @param configFile path to the config file
     * @return the parsed {@link ConfigModel}; defaults if the file is absent
     * @throws IOException if the file exists but cannot be read or is not a JSON object
     */
    public static ConfigModel load(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            return getDefault();
        }
        JsonNode document = MAPPER.readTree(configFile.toFile());
        if (document == null || !document.isObject()) {
            throw new IOException("Config file does not contain a JSON object: " + configFile);
        }
        return MAPPER.treeToValue(withDefaults((ObjectNode) document), ConfigModel.class)
                .normalized();
    }

    /**
     * Overlays the file's values on top of the application defaults.
     *
     * <p>{@link ConfigModel} is a record, so a key the file leaves out arrives
     * as the type's zero value instead of the configured default. Merging the
     * file into a copy of the defaults keeps "absent" distinguishable from
     * "explicitly set to zero", which the clamp in
     * {@link ConfigModel#normalized()} cannot do. Explicit {@code null}
     * values are treated the same as an absent key.</p>
     *
     * @param fromFile the parsed config file; never null
     * @return a node holding the file's values on top of the defaults
     */
    private static ObjectNode withDefaults(ObjectNode fromFile) {
        ObjectNode merged = MAPPER.valueToTree(getDefault());
        Iterator<Map.Entry<String, JsonNode>> fields = fromFile.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isNull()) {
                merged.set(field.getKey(), field.getValue());
            }
        }
        return merged;
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
        saveContent(configFile, () -> MAPPER.writeValueAsBytes(config));
    }

    /**
     * Stages the content produced by {@code content} in a temporary file in
     * the same directory and then moves it onto the target atomically. A crash,
     * a full disk or a serialization failure therefore leaves the previous
     * config untouched instead of truncating it, so a broken write can never
     * cost the user their settings. Falls back to a non-atomic move where the
     * filesystem does not support atomic moves.
     *
     * @param configFile path to write to
     * @param content produces the bytes to write; runs after the staging file
     *                exists, so a failure it throws still leaves the target alone
     * @throws IOException if the file cannot be written
     */
    static void saveContent(Path configFile, ContentWriter content) throws IOException {
        Path parent = configFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "facesort-config", ".tmp");
        try {
            Files.write(temp, content.write());
            try {
                Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Produces the bytes of one config file write.
     */
    @FunctionalInterface
    interface ContentWriter {
        byte[] write() throws IOException;
    }
}
