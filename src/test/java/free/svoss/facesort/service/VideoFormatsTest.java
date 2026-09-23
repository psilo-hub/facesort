package free.svoss.facesort.service;

import free.svoss.facesort.util.VideoFormats;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link VideoFormats}, the single source of truth for the video
 * file extensions the app supports and the ffmpeg demuxer that opens them.
 */
class VideoFormatsTest {

    @Test
    void supportedExtensions_areExactlyTheVideoImportServiceSet() {
        assertEquals(VideoImportService.SUPPORTED_EXTENSIONS, VideoFormats.supportedExtensions(),
                "the import walk and the frame source must agree on the supported extensions");
    }

    @Test
    void supportedExtensions_coverEveryKnownVideoFormat() {
        assertEquals(Set.of("mp4", "m4v", "mkv", "mov", "avi", "webm", "flv",
                        "mpg", "mpeg", "3gp", "ts", "wmv"),
                VideoFormats.supportedExtensions());
    }

    @Test
    void demuxerName_mapsEachExtensionToItsFfmpegFormat() {
        assertEquals("mp4", VideoFormats.demuxerName("mp4"));
        assertEquals("mp4", VideoFormats.demuxerName("m4v"));
        assertEquals("matroska", VideoFormats.demuxerName("mkv"));
        assertEquals("mov", VideoFormats.demuxerName("mov"));
        assertEquals("avi", VideoFormats.demuxerName("avi"));
        assertEquals("webm", VideoFormats.demuxerName("webm"));
        assertEquals("flv", VideoFormats.demuxerName("flv"));
        assertEquals("mpeg", VideoFormats.demuxerName("mpg"));
        assertEquals("mpeg", VideoFormats.demuxerName("mpeg"));
        assertEquals("3gp", VideoFormats.demuxerName("3gp"));
        assertEquals("mpegts", VideoFormats.demuxerName("ts"));
        assertEquals("asf", VideoFormats.demuxerName("wmv"));
    }

    @Test
    void demuxerName_unknownExtension_throws() {
        assertThrows(IllegalArgumentException.class, () -> VideoFormats.demuxerName("ogv"),
                "unknown extensions must be rejected with a clear message");
        assertThrows(IllegalArgumentException.class, () -> VideoFormats.demuxerName(""),
                "a missing extension must be rejected like any other unknown one");
    }
}