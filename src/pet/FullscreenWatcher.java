package pet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过一个常驻 PowerShell 子进程检测前台窗口是否全屏。
 * Java 本身拿不到别的进程的窗口信息，所以把这件事交给系统脚本。
 */
public final class FullscreenWatcher {

    private final AtomicBoolean fullscreen = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger ground =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    private FullscreenWatcher() {
    }

    /** 测试用：永远返回"没有全屏程序"。 */
    public static FullscreenWatcher disabled() {
        Log.info("全屏检测已被 --ignorefullscreen 关闭");
        return new FullscreenWatcher();
    }

    public FullscreenWatcher(Path script, String powershell) {
        if (!java.nio.file.Files.exists(script)) {
            Log.info("全屏检测脚本不存在，跳过: " + script);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                        script.toAbsolutePath().toString());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String value = line.trim();
                        if (value.startsWith("GROUND ")) {
                            try {
                                int y = Integer.parseInt(value.substring(7).trim());
                                if (ground.get() != y) {
                                    ground.set(y);
                                    Log.info("地面线更新为 y=" + y
                                            + (y < 1200 ? "（任务栏弹出，她站到任务栏上）" : "（任务栏收起，站屏幕底边）"));
                                }
                            } catch (NumberFormatException ignored) {
                                // 忽略异常行
                            }
                        } else if (value.contains("FULLSCREEN")) {
                            if (!fullscreen.getAndSet(true)) {
                                Log.info("检测到全屏程序，桌宠让路");
                            }
                        } else if (value.contains("NORMAL")) {
                            // 首次轮询总是报一次 NORMAL，只有真的从全屏退出才记一行
                            if (fullscreen.getAndSet(false)) {
                                Log.info("全屏程序已退出，桌宠恢复");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.error("全屏检测线程结束", e);
            }
        }, "fullscreen-watcher");
        thread.setDaemon(true);
        thread.start();
        Log.info("全屏检测已启动: " + script.getFileName());
    }

    public boolean isFullscreen() {
        return fullscreen.get();
    }

    /** 地面线（设备像素）。-1 表示还没探测到。 */
    public int groundY() {
        return ground.get();
    }
}
