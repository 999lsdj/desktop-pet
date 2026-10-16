package pet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/** 运行 PetWindow 相同的求值和绘制器，不创建桌面窗口。 */
public final class ProductionAnimationPreview {
    /** 9.1：极限摆位离画布边还剩多少原生像素；0 就是被裁到。 */
    private static int envelopeMin = Integer.MAX_VALUE;
    private static int envelopePoses;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        String[] modes = {"walk", "pause", "sway", "stumble", "rest", "menu"};
        int w = 338, h = 374;
        BufferedImage sheet = new BufferedImage(w * 3, (h + 24) * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D page = sheet.createGraphics();
        page.setColor(new Color(0xE8EDF3)); page.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        StringBuilder report = new StringBuilder("{\n  \"driver\":\"PetAnimation (same as PetWindow)\",\n"
                + "  \"desktop_window_started\":false,\n  \"user_visual_acceptance\":false,\n  \"modes\":{\n");
        for (int mode = 0; mode < modes.length; mode++) {
            LayeredRig rig = LayeredRig.load(Path.of(args[0]));
            Config config = new Config(Path.of("config.properties"));
            PetAnimation animation = new PetAnimation(rig, config);
            Path frames = out.resolve(modes[mode]); Files.createDirectories(frames);
            double speed = 18;
            GroundedGait measure = new GroundedGait(rig.skeleton(), 12, 0.6);
            double dt = mode == 0 ? measure.stride() / speed / 48 : 2.0 / 48;
            double travel = 0, time = 0, maxDraw = 0, sumDraw = 0, maxFlex = 0;
            boolean fits = true;
            for (int i = -96; i < 144; i++) {
                time += dt;
                boolean walking = mode == 0 || (mode == 1 && i < 0);
                double rest = mode == 4 ? 1 : 0;
                double tilt = switch (mode) {
                    case 2 -> 5 * Math.sin(time * Math.PI);
                    case 3 -> 12 * Math.pow(Math.sin(time * Math.PI), 3);
                    case 4 -> -13;
                    default -> 0;
                };
                if (walking) travel += speed * dt;
                animation.update(dt, walking, speed, tilt, rest, mode == 5 ? 1 : 0, travel);
                if (i < 0) continue;
                note(margin(animation.frame()));
                fits &= rig.fits(PetAnimation.PADDING) && envelopeMin >= 1;
                maxDraw = Math.max(maxDraw, rig.lastDrawMs()); sumDraw += rig.lastDrawMs();
                maxFlex = Math.max(maxFlex, animation.flexibleMotion());
                BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(new Color(0xE8EDF3)); g.fillRect(0, 0, w, h);
                g.setColor(new Color(0xB8C6D5)); g.drawLine(20, 310, w - 20, 310);
                animation.draw(g, w / 2, 312, 2, false);
                g.dispose();
                ImageIO.write(frame, "png", frames.resolve(String.format(Locale.ROOT, "%03d.png", i)).toFile());
                if (i == 12) {
                    int x = mode % 3 * w, y = mode / 3 * (h + 24);
                    page.drawImage(frame, x, y + 24, null);
                    page.setColor(new Color(0x24354A)); page.drawString(modes[mode], x + 10, y + 18);
                }
            }
            if (mode > 0) report.append(",\n");
            report.append(String.format(Locale.ROOT,
                    "    \"%s\":{\"frames\":144,\"frame_seconds\":%.9f,\"fits\":%s,"
                    + "\"raster_mean_ms\":%.3f,\"raster_max_ms\":%.3f,\"flex_peak_deg\":%.3f}",
                    modes[mode], dt, fits, sumDraw / 144, maxDraw, maxFlex));
            Log.info("正式动画离屏状态 " + modes[mode] + " 144 frames fits=" + fits);
            if (!fits) throw new AssertionError("Clipped mode: " + modes[mode]);
        }
        page.dispose();
        ImageIO.write(sheet, "png", out.resolve("states.png").toFile());
        // 极限摆位也算进包络统计，所以先跑完再写报告。
        extremes(Path.of(args[0]), out);
        report.append("\n  },\n");
        report.append(String.format(Locale.ROOT,
                "  \"envelope_poses_checked\":%d,\n  \"envelope_min_margin_native_px\":%d\n}\n",
                envelopePoses, envelopeMin));
        Files.writeString(out.resolve("report.json"), report, StandardCharsets.UTF_8);
        if (envelopeMin < 1) throw new AssertionError("Extreme pose clipped: margin=" + envelopeMin);
        Log.info("极限摆位检查 " + envelopePoses + " 个姿势，最小留边 " + envelopeMin + " 原生像素");
        System.out.println(out.toAbsolutePath());
    }

    private static void note(int margin) {
        envelopePoses++;
        envelopeMin = Math.min(envelopeMin, margin);
    }

    /** 可见像素到画布四边的最小距离。 */
    private static int margin(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) <= 8) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return 0;
        return Math.min(Math.min(minX, minY),
                Math.min(image.getWidth() - 1 - maxX, image.getHeight() - 1 - maxY));
    }

    private static void extremes(Path bundle, Path out) throws Exception {
        LayeredRig rig = LayeredRig.load(bundle);
        String[] bones = {"head", "upperArm", "foreArm", "hand", "upperArmFar", "foreArmFar", "handFar",
                "thighNear", "shinNear", "footNear", "thighFar", "shinFar", "footFar"};
        double[][] ranges = {{-15,15},{-70,25},{-80,25},{-25,25},{-25,35},{-35,35},{-25,25},
                {-25,25},{-10,45},{-20,20},{-25,25},{-10,45},{-20,20}};
        int w = rig.width() + PetAnimation.PADDING * 2, h = rig.height() + PetAnimation.PADDING * 2 + 20;
        BufferedImage sheet = new BufferedImage(w * 6, h * 5, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xE8EDF3)); g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        for (int j = 0; j < bones.length; j++) for (int edge = 0; edge < 2; edge++) {
            rig.resetPose();
            rig.skeleton().setDelta(bones[j], ranges[j][edge]); rig.update();
            BufferedImage posed = rig.render(PetAnimation.PADDING);
            note(margin(posed));
            if (!rig.fits(PetAnimation.PADDING) || envelopeMin < 1)
                throw new AssertionError("Extreme clip: " + bones[j]);
            int i = j * 2 + edge, x = i % 6 * w, y = i / 6 * h;
            g.drawImage(posed, x, y + 20, null);
            g.setColor(new Color(0x24354A)); g.drawString(bones[j] + " " + ranges[j][edge], x + 4, y + 15);
        }
        rig.resetPose();
        PetAnimation prepared = new PetAnimation(rig, new Config(Path.of("config.properties")));
        prepared.update(1.0/120, false, 0, 0, 1, 0, 0);
        rig.setVariant("handOpen");
        rig.skeleton().setDelta("upperArm", -45); rig.skeleton().setDelta("foreArm", -35); rig.update();
        BufferedImage openHand = rig.render(PetAnimation.PADDING);
        note(margin(openHand));
        g.drawImage(openHand, 2 * w, 4 * h + 20, null);
        g.drawString("open hand (binding only)", 2 * w + 4, 4 * h + 15);
        g.dispose(); ImageIO.write(sheet,"png",out.resolve("extremes.png").toFile());
    }
}
