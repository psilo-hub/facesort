package free.svoss.facesort.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Holds the application's current {@link ConfigModel} and publishes changes to
 * it atomically.
 *
 * <p>{@link ConfigModel} is immutable, so replacing the reference is the only
 * way a setting changes. Readers call {@link #get()} and always receive a
 * complete configuration — the one before the change or the one after it —
 * even while the settings view is swapping in a new value. Read-modify-write
 * changes go through {@link #updateAndGet(UnaryOperator)}, which applies the
 * change to the latest published value, so two concurrent changes cannot
 * overwrite each other.</p>
 */
public final class ConfigStore {

    private final AtomicReference<ConfigModel> config;

    /**
     * Creates a store publishing the given configuration.
     *
     * @param initial the configuration to start with; must not be null
     */
    public ConfigStore(ConfigModel initial) {
        this.config = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /**
     * Returns the currently published configuration.
     *
     * @return the current config; never null
     */
    public ConfigModel get() {
        return config.get();
    }

    /**
     * Publishes the given configuration, replacing the current one.
     *
     * @param value the configuration to publish; must not be null
     */
    public void set(ConfigModel value) {
        config.set(Objects.requireNonNull(value, "value"));
    }

    /**
     * Applies the given change to the latest published configuration and
     * publishes the result.
     *
     * @param change derives the new configuration from the current one; must
     *               not be null and must not return null
     * @return the configuration that is now published
     */
    public ConfigModel updateAndGet(UnaryOperator<ConfigModel> change) {
        Objects.requireNonNull(change, "change");
        return config.updateAndGet(current ->
                Objects.requireNonNull(change.apply(current), "change result"));
    }
}
