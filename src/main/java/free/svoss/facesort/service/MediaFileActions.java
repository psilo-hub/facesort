package free.svoss.facesort.service;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Original-file operations exposed by the services behind the face views:
 * checking whether the original media file or its containing folder is
 * available, and opening either in the desktop.
 */
public interface MediaFileActions {

    /**
     * Tells whether the original file for the given image hash exists on disk.
     *
     * @param imageHash hash of the source image
     * @return {@code true} if at least one stored path exists on disk
     * @throws SQLException on database access failure
     */
    boolean isOriginalAvailable(String imageHash) throws SQLException;

    /**
     * Tells whether the folder containing the original file exists on disk.
     *
     * @param imageHash hash of the source image
     * @return {@code true} if a containing folder can be opened
     * @throws SQLException on database access failure
     */
    boolean isContainingFolderAvailable(String imageHash) throws SQLException;

    /**
     * Opens the original file in the operating system default viewer.
     *
     * @param imageHash hash of the source image
     * @return {@code true} if the file was handed to the default viewer
     * @throws IOException  if the default viewer cannot open the file
     * @throws SQLException on database access failure
     */
    boolean openOriginal(String imageHash) throws IOException, SQLException;

    /**
     * Opens the folder containing the original file in the file manager.
     *
     * @param imageHash hash of the source image
     * @return {@code true} if the folder was handed to the file manager
     * @throws IOException  if the file manager cannot open the folder
     * @throws SQLException on database access failure
     */
    boolean openContainingFolder(String imageHash) throws IOException, SQLException;
}
