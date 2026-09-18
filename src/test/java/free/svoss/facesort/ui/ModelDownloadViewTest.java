package free.svoss.facesort.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the pure formatting helper of {@link ModelDownloadView}.
 */
class ModelDownloadViewTest {

    @Test
    void formatBytesHandlesBytes() {
        assertEquals("0 B", ModelDownloadView.formatBytes(0));
        assertEquals("1023 B", ModelDownloadView.formatBytes(1023));
    }

    @Test
    void formatBytesHandlesKiB() {
        assertEquals("1.0 KB", ModelDownloadView.formatBytes(1024));
        assertEquals("512.0 KB", ModelDownloadView.formatBytes(512 * 1024));
    }

    @Test
    void formatBytesHandlesMiB() {
        assertEquals("1.0 MB", ModelDownloadView.formatBytes(1024 * 1024));
        assertEquals("4.5 MB", ModelDownloadView.formatBytes(4 * 1024 * 1024 + 512 * 1024));
    }

    @Test
    void formatBytesHandlesGiB() {
        assertEquals("1.0 GB", ModelDownloadView.formatBytes(1024L * 1024 * 1024));
        assertEquals("2.0 GB", ModelDownloadView.formatBytes(2 * 1024L * 1024 * 1024));
    }
}