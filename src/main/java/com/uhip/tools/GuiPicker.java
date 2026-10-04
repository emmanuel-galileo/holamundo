package com.uhip.tools;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native Windows/OS file and folder picker utilities with headless fallback.
 */
public final class GuiPicker {

    private GuiPicker() {}

    /**
     * Orchestrator: Attempts to open a native file selection dialog for images.
     * Returns null if running headless, cancelled, or unavailable.
     */
    public static Path pickImageFile() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return tryNativeFileDialog();
    }

    /**
     * Orchestrator: Attempts to open a native folder selection dialog.
     * Returns null if running headless, cancelled, or unavailable.
     */
    public static Path pickDirectory() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return tryFolderChooser();
    }

    // --- Sub-functions (Single-responsibility) ---

    private static Path tryNativeFileDialog() {
        try {
            AtomicReference<Path> selected = new AtomicReference<>(null);
            invokeOnAwt(() -> {
                Frame frame = new Frame();
                frame.setAlwaysOnTop(true);
                FileDialog dialog = new FileDialog(frame, "Seleccione la Imagen Masiva (PNG, PSB, TIFF, JPG)", FileDialog.LOAD);
                dialog.setFile("*.png;*.tif;*.tiff;*.psb;*.psd;*.jpg;*.jpeg");
                dialog.setVisible(true);
                String file = dialog.getFile();
                String dir = dialog.getDirectory();
                dialog.dispose();
                frame.dispose();
                if (file != null && dir != null) {
                    selected.set(Path.of(dir, file));
                }
            });
            return selected.get();
        } catch (Exception ignored) {
            return trySwingFallbackFileChooser();
        }
    }

    private static Path trySwingFallbackFileChooser() {
        try {
            AtomicReference<Path> selected = new AtomicReference<>(null);
            invokeOnAwt(() -> {
                JFileChooser chooser = new JFileChooser(new File("."));
                chooser.setDialogTitle("Seleccione la Imagen Masiva (PNG, PSB, TIFF, JPG)");
                chooser.setFileFilter(new FileNameExtensionFilter(
                        "Imágenes Gigapíxel / Masivas (*.png, *.tif, *.psb, *.jpg)",
                        "png", "tif", "tiff", "psb", "psd", "jpg", "jpeg"
                ));
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    selected.set(chooser.getSelectedFile().toPath());
                }
            });
            return selected.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path tryFolderChooser() {
        try {
            AtomicReference<Path> selected = new AtomicReference<>(null);
            invokeOnAwt(() -> {
                JFileChooser chooser = new JFileChooser(new File("."));
                chooser.setDialogTitle("Seleccione el Directorio de Teselas");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    selected.set(chooser.getSelectedFile().toPath());
                }
            });
            return selected.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void invokeOnAwt(Runnable task) throws Exception {
        if (java.awt.EventQueue.isDispatchThread()) {
            task.run();
        } else {
            java.awt.EventQueue.invokeAndWait(task);
        }
    }
}
