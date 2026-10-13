package pet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 参数配置：全部动画与行为参数都放这里，改 config.properties 重启即生效，
 * 方便不用改代码就能调手感。
 */
public final class Config {

    private final Properties props = new Properties();
    private final Path path;

    public Config(Path path) {
        this.path = path;
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.error("读取配置失败，使用默认值: " + path, e);
            }
        } else {
            Log.info("未找到配置文件，使用默认值: " + path);
        }
    }

    public double d(String key, double fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            Log.info("配置项 " + key + " 无法解析（" + raw + "），用默认值 " + fallback);
            return fallback;
        }
    }

    public int i(String key, int fallback) {
        return (int) Math.round(d(key, fallback));
    }

    public Path path() {
        return path;
    }
}
