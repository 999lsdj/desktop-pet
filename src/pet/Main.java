package pet;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    public static void main(String[] args) {
        boolean demo = false;
        boolean selftest = false;
        for (String arg : args) {
            if ("--demo".equals(arg)) {
                demo = true;
            }
            if ("--selftest".equals(arg)) {
                selftest = true;
            }
        }

        Path root = Paths.get("").toAbsolutePath();
        Log.init(root.resolve("logs").resolve("pet.log"));
        Log.info("桌宠启动 | 工作目录=" + root + " | demo=" + demo
                + " | Java=" + System.getProperty("java.version"));

        final boolean demoMode = demo;
        final boolean selfTestMode = selftest;
        SwingUtilities.invokeLater(() -> {
            try {
                PetWindow window = new PetWindow(demoMode);
                window.open();
                if (demoMode) {
                    javax.swing.Timer t = new javax.swing.Timer(1200, e -> window.toggleMenu(true));
                    t.setRepeats(false);
                    t.start();
                }
                if (selfTestMode) {
                    javax.swing.Timer t = new javax.swing.Timer(1200, e -> window.selfTestClick());
                    t.setRepeats(false);
                    t.start();
                }
            } catch (Throwable t) {
                Log.error("启动失败", t);
                System.exit(1);
            }
        });
    }
}
