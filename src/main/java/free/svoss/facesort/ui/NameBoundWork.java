package free.svoss.facesort.ui;

import free.svoss.facesort.model.NameRecord;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Binds background work to the name a view had selected when the work started.
 *
 * <p>A view's selected name is mutable state: the user can pick a different
 * person from the list while a tagging, untagging or renaming task is still
 * queued or running. Work that reads the view's field from the background
 * thread therefore operates on whichever name happens to be selected by the
 * time it runs — untagging, tagging or even <em>renaming</em> the wrong
 * person. Reading the field once, on the calling thread, before the task is
 * created freezes the target: the selection may change freely afterwards
 * without redirecting work that has already been requested.</p>
 *
 * <p>This is deliberately free of {@link javafx.concurrent.Task}, so the
 * behaviour can be pinned by a headless test — no JavaFX toolkit is needed to
 * bind work and run it later.</p>
 */
final class NameBoundWork {

    private NameBoundWork() {
    }

    /**
     * Work to perform for one selected name.
     *
     * @param <T> the work's result type
     */
    @FunctionalInterface
    interface Work<T> {

        /**
         * Performs the work.
         *
         * @param name the name captured when this work was bound
         * @return the result of the work
         * @throws Exception if the work fails; propagated to the caller
         */
        T apply(NameRecord name) throws Exception;
    }

    /**
     * Work bound to a single name, together with that name, so a success
     * handler can report and refresh it without re-reading the view's
     * selection.
     *
     * @param name the captured name
     * @param work the work to perform for that name
     * @param <T>  the work's result type
     */
    record Bound<T>(NameRecord name, Callable<T> work) {
    }

    /**
     * Captures the currently selected name and binds the given work to it. The
     * selection is read exactly once, on the calling thread; {@code work} is
     * not invoked.
     *
     * @param selected supplies the selected name, read once, now
     * @param work     the work to perform for that name
     * @param <T>      the work's result type
     * @return the bound work and the name it was bound to
     */
    static <T> Bound<T> forSelectedName(Supplier<NameRecord> selected, Work<T> work) {
        NameRecord name = selected.get();
        Callable<T> bound = () -> work.apply(name);
        return new Bound<>(name, bound);
    }
}
