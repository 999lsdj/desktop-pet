package pet;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    public static void main(String[] args) {
        boolean demo = false;
        boolean selftest = false;
        boolean restTest = false;
        boolean ignoreFullscreen = false;
        boolean still = false;
        for (String arg : args) {
            if ("--demo".equals(arg)) {
                demo = true;
            }
            if ("--selftest".equals(arg)) {
                selftest = true;
            }
            if ("--resttest".equals(arg)) {
                restTest = true;
            }
            if ("--ignorefullscreen".equals(arg)) {
                ignoreFullscreen = true;
            }
            if ("--still".equals(arg)) {
                still = true;
            }
        }

        Path root = Paths.get("").toAbsolutePath();
        Log.init(root.resolve("logs").resolve("pet.log"));
        Log.info("桌宠启动 | 工作目录=" + root + " | demo=" + demo
                + " | Java=" + System.getProperty("java.version"));

        final boolean demoMode = demo;
        final boolean selfTestMode = selftest;
        final boolean restTestMode = restTest;
        final boolean ignoreFullscreenMode = ignoreFullscreen;
        final boolean stillMode = still;
        SwingUtilities.invokeLater(() -> {
            try {
                Config config = new Config(root.resolve("config.properties"));
                PetWindow window = new PetWindow(config, demoMode, selfTestMode, restTestMode,
                        ignoreFullscreenMode, stillMode);
                window.open();
            } catch (Throwable t) {
                Log.error("启动失败", t);
                System.exit(1);
            }
        });
    }
}
