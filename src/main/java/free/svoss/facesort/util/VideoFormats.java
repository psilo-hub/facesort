package free.svoss.facesort.util;

import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the video file formats the app supports.
 *
 * <p>Both the import walk ({@code VideoImportService}) and the ffmpeg frame
 * source ({@code FfmpegVideoFrameSource}) derive their extension knowledge from
 * this one map, so the set of importable extensions and the demuxer that opens
 * them can no longer drift apart.</p>
 */
public final class VideoFormats {

    /** ffmpeg demuxer format name per supported extension (lowercase, dot-less). */
    private static final Map<String, String> DEMUXER_BY_EXTENSION = Map.ofEntries(
            Map.entry("mp4", "mp4"),
            Map.entry("m4v", "mp4"),
            Map.entry("mkv", "matroska"),
            Map.entry("mov", "mov"),
            Map.entry("avi", "avi"),
            Map.entry("webm", "webm"),
            Map.entry("flv", "flv"),
            Map.entry("mpg", "mpeg"),
            Map.entry("mpeg", "mpeg"),
            Map.entry("3gp", "3gp"),
            Map.entry("ts", "mpegts"),
            Map.entry("wmv", "asf"));

    private VideoFormats() {
    }

    /** Supported video file extensions (case-insensitive, without the leading dot). */
    public static Set<String> supportedExtensions() {
        return DEMUXER_BY_EXTENSION.keySet();
    }

    /**
     * Returns the ffmpeg demuxer format name for a supported video extension.
     *
     * @param extension lowercase file extension without the leading dot
     * @return the ffmpeg demuxer format name
     * @throws IllegalArgumentException if the extension is not supported
     */
    public static String demuxerName(String extension) {
        String demuxer = DEMUXER_BY_EXTENSION.get(extension);
        if (demuxer == null) {
            throw new IllegalArgumentException("unsupported video extension: " + extension);
        }
        return demuxer;
    }
}