package pet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 极简日志：同时输出到控制台和 logs/pet.log，方便追踪桌宠做了什么。 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Path file;

    private Log() {
    }

    public static void init(Path logFile) {
        file = logFile;
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message + " | " + (t == null ? "-" : t.toString()));
        if (t != null) {
            t.printStackTrace();
        }
    }

    private static void write(String level, String message) {
        String line = "[" + LocalDateTime.now().format(TS) + "] " + level + " " + message;
        System.out.println(line);
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志失败不影响桌宠运行
        }
    }
}
