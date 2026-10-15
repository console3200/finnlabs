package finnLabs;


import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * finnLabs : Video Player - a lightweight, dependency-free launcher.
 *
 * This used to embed a JavaFX MediaPlayer window. That meant shipping the
 * whole JavaFX SDK (WebKit, GStreamer, native codec libs) just to show a
 * video. Every OS already has a video player with real, well-maintained
 * codec support, so this class just hands the file to it via
 * {@link Desktop#open(File)} - no window, no decoding, no extra runtime,
 * effectively zero startup delay.
 *
 * Kept as a class (rather than a static method) only so existing callers
 * (`new VideoPlayer(file).setVisible(true)`) don't need to change.
 */
public class VideoPlayer {

    /** Extensions this app claims to handle; anything else is refused
     *  rather than blindly handed to Desktop.open(). */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("mp4", "m4v", "mov", "mkv", "avi", "webm");

    private File file;

    public VideoPlayer() {
        this(null);
    }

    public VideoPlayer(File toOpen) {
        this.file = toOpen;
    }

    /** Opens (or replaces) the file this launcher points at. */
    public void open(File toOpen) {
        this.file = toOpen;
    }

    /** Entry point kept for compatibility with existing call sites, e.g.
     *  {@code new VideoPlayer(file).setVisible(true)}. There is no window,
     *  so this just triggers the launch when {@code visible} is true. */
    public void setVisible(boolean visible) {
        if (!visible) return;
        SwingUtilities.invokeLater(() -> {
            File target = file;
            if (target == null) target = promptForFile();
            if (target != null) launch(target);
        });
    }

    /** Convenience one-liner for callers that just want a file played. */
    public static void openStandalone(File file) {
        new VideoPlayer(file).setVisible(true);
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private File promptForFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Video");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Video files", ALLOWED_EXTENSIONS.toArray(new String[0])));
        int result = chooser.showOpenDialog(null);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private void launch(File target) {
        if (!isSafeToOpen(target)) {
            showError("Cannot open \"" + target.getName() + "\": not a recognized video file.");
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showError("No default video player is available on this system.");
            return;
        }
        try {
            Desktop.getDesktop().open(target);
        } catch (IOException | SecurityException ex) {
            showError("Cannot open \"" + target.getName() + "\": " + ex.getMessage());
        }
    }

    /** Security gate: only ever hand off a real, existing, non-hidden file
     *  whose extension is on the video whitelist - never an arbitrary
     *  path, directory, or unexpected file type. */
    private static boolean isSafeToOpen(File f) {
        if (f == null || !f.exists() || !f.isFile()) return false;
        return ALLOWED_EXTENSIONS.contains(extensionOf(f));
    }

    private static String extensionOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Video Player", JOptionPane.ERROR_MESSAGE);
    }
}
