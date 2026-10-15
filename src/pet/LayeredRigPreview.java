package pet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Synthetic offscreen diagnostic, deliberately separate from PetWindow and production motions. */
public final class LayeredRigPreview {
    private static final int FRAMES_PER_CYCLE = 48;
    private static final int CYCLES = 3;
    private static final String[] POSES = {"rest", "knees-ankles", "arms", "hair-sleeves-coat", "reverse-flex", "combined"};

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length != 2) throw new IllegalArgumentException("Usage: pet.LayeredRigPreview <rig-bundle-dir> <output-dir>");
        LayeredRig rig = LayeredRig.load(Path.of(args[0]));
        String rigHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(args[0]).resolve("rig.properties"))));
        Path output = Path.of(args[1]);
        Files.createDirectories(output.resolve("frames"));
        int padding = 16;
        for (String pose : POSES) {
            apply(rig, pose, Math.PI / 2);
            padding = Math.max(padding, requiredPadding(rig));
        }
        for (int i = 0; i < FRAMES_PER_CYCLE; i++) {
            apply(rig, "combined", phase(i));
            padding = Math.max(padding, requiredPadding(rig));
        }
        rig.resetPose();
        BufferedImage rest = rig.restComposite();
        BufferedImage renderedRest = rig.render(0);
        int restDiff = difference(rest, rig.reference());
        int renderDiff = difference(rest, renderedRest);
        ImageIO.write(rest, "png", output.resolve("rest.png").toFile());
        ImageIO.write(diffImage(rest, rig.reference()), "png", output.resolve("rest-difference.png").toFile());

        int scale = 3, tileWidth = (rig.width() + padding * 2) * scale;
        int tileHeight = (rig.height() + padding * 2) * scale + 32;
        BufferedImage sheet = new BufferedImage(tileWidth * 3, tileHeight * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (int i = 0; i < POSES.length; i++) {
            apply(rig, POSES[i], Math.PI / 2);
            BufferedImage frame = rig.render(padding);
            int x = (i % 3) * tileWidth, y = (i / 3) * tileHeight;
            checker(g, x, y, tileWidth, tileHeight);
            g.drawImage(frame, x, y + 28, frame.getWidth() * scale, frame.getHeight() * scale, null);
            g.setColor(new Color(0x22334A));
            g.drawString(POSES[i] + " | synthetic pose", x + 12, y + 19);
        }
        g.dispose();
        ImageIO.write(sheet, "png", output.resolve("poses.png").toFile());

        // Warm up the same deformation and raster code before collecting timings; PNG I/O excluded.
        for (int i = 0; i < 24; i++) { apply(rig, "combined", phase(i)); rig.render(padding); }
        int count = FRAMES_PER_CYCLE * CYCLES;
        double[] updateTimes = new double[count], drawTimes = new double[count];
        Map<String, Double> motion = new LinkedHashMap<>();
        Map<String, Double> boneMotion = new LinkedHashMap<>();
        List<String> frames = new ArrayList<>();
        boolean fits = true;
        BufferedImage first = null;
        int repeatDifference = 0;
        for (int i = 0; i < count; i++) {
            apply(rig, "combined", phase(i % FRAMES_PER_CYCLE));
            fits &= rig.fits(padding);
            updateTimes[i] = rig.lastUpdateMs();
            BufferedImage frame = rig.render(padding);
            drawTimes[i] = rig.lastDrawMs();
            if (i == 0) first = frame;
            if (i % FRAMES_PER_CYCLE == 0) repeatDifference += difference(first, frame);
            String file = String.format(Locale.ROOT, "frames/frame-%03d.png", i);
            ImageIO.write(frame, "png", output.resolve(file).toFile());
            for (LayeredRig.Layer layer : rig.layers()) motion.merge(layer.name, layer.maxDisplacement(), Math::max);
            StringBuilder angles = new StringBuilder(), joints = new StringBuilder();
            for (String bone : rig.skeleton().names()) {
                double delta = rig.skeleton().bone(bone).deltaDeg();
                boneMotion.merge(bone, Math.abs(delta), Math::max);
                if (angles.length() > 0) { angles.append(','); joints.append(','); }
                angles.append(quote(bone)).append(':').append(number(delta));
                Point2D.Double a = rig.skeleton().start(bone), b = rig.skeleton().tip(bone);
                joints.append(quote(bone)).append(": [").append(number(a.x)).append(',').append(number(a.y))
                        .append(',').append(number(b.x)).append(',').append(number(b.y)).append(']');
            }
            frames.add("{\"file\":" + quote(file) + ",\"cycle\":" + (i / FRAMES_PER_CYCLE)
                    + ",\"angles_deg\":{" + angles + "},\"bone_endpoints\":{" + joints + "}}");
        }
        apply(rig, "combined", 0);
        BufferedImage cycleStart = rig.render(padding);
        apply(rig, "combined", Math.PI * 2);
        int closureDiff = difference(cycleStart, rig.render(padding));
        StringBuilder layerReport = new StringBuilder();
        int vertices = 0, triangles = 0, deformableTriangles = 0, rigidLayers = 0;
        for (LayeredRig.Layer layer : rig.layers()) {
            if (layerReport.length() > 0) layerReport.append(",\n");
            vertices += layer.skin.mesh().vertexCount(); triangles += layer.skin.mesh().triangleCount();
            if (layer.bones.size() == 1) rigidLayers++;
            else deformableTriangles += layer.skin.mesh().triangleCount();
            layerReport.append("    ").append(quote(layer.name)).append(": {\"group\":").append(quote(layer.group))
                    .append(",\"bones\":[").append(String.join(",", layer.bones.stream().map(LayeredRigPreview::quote).toList()))
                    .append("],\"nontransparent_pixels\":").append(layer.visiblePixels)
                    .append(",\"vertices\":").append(layer.skin.mesh().vertexCount())
                    .append(",\"max_vertex_displacement_px\":").append(number(motion.get(layer.name))).append('}');
        }
        boolean allMovableLayersExercised = rig.layers().stream().filter(l -> !l.name.equals("torso"))
                .allMatch(l -> l.visiblePixels > 0 && motion.get(l.name) > 0.1);
        String report = "{\n"
                + "  \"status\":\"synthetic_offscreen_preview_requires_art_and_motion_review\",\n"
                + "  \"runtime_ready\":false,\n  \"physics_acceptance\":false,\n"
                + "  \"rig_properties_sha256\":" + quote(rigHash) + ",\n"
                + "  \"driver\":\"periodic analytic joint angles; no inertia, IK or production state machine\",\n"
                + "  \"rest_composition\":\"straight RGBA source-over; stationary layers bypass Java2D premultiplication\",\n"
                + "  \"raster_limitations\":\"Java2D nearest-neighbor; rigid drawImage may differ by 1-3 pixels from triangle rasterization in tested poses; inspect seams and occlusion visually\",\n"
                + "  \"canvas\":[" + rig.width() + "," + rig.height() + "],\n  \"padding_px\":" + padding + ",\n"
                + "  \"rest_rgba_difference_pixels\":" + restDiff + ",\n  \"rest_render_difference_pixels\":" + renderDiff + ",\n"
                + "  \"rendered_cycle_closure_difference_pixels\":" + closureDiff + ",\n"
                + "  \"repeated_cycle_start_difference_pixels\":" + repeatDifference + ",\n"
                + "  \"synthetic_pose_and_velocity_periodic_by_construction\":true,\n"
                + "  \"all_tested_vertices_inside_padding\":" + fits + ",\n"
                + "  \"all_non_torso_layers_exercised\":" + allMovableLayersExercised + ",\n"
                + "  \"frames_per_cycle\":" + FRAMES_PER_CYCLE + ",\n  \"cycles\":" + CYCLES + ",\n"
                + "  \"suggested_fps\":24,\n  \"vertices\":" + vertices + ",\n  \"triangles\":" + triangles + ",\n"
                + "  \"deformable_triangles\":" + deformableTriangles + ",\n  \"rigid_layers\":" + rigidLayers + ",\n"
                + "  \"timing_ms_png_io_excluded\":{\"update\":" + timing(updateTimes) + ",\"draw\":" + timing(drawTimes) + "},\n"
                + "  \"layers\":{\n" + layerReport + "\n  },\n"
                + "  \"bone_max_abs_delta_deg\":{" + String.join(",", boneMotion.entrySet().stream()
                    .map(e -> quote(e.getKey()) + ":" + number(e.getValue())).toList()) + "},\n"
                + "  \"frames\":[\n    " + String.join(",\n    ", frames) + "\n  ]\n}\n";
        Files.writeString(output.resolve("report.json"), report, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "Preview: %s | rest diff=%d, render diff=%d, closure diff=%d, padding=%d, vertices=%d, triangles=%d%n",
                output.toAbsolutePath(), restDiff, renderDiff, closureDiff, padding, vertices, triangles);
        System.out.println("Timing ms (PNG excluded): update=" + timing(updateTimes) + " draw=" + timing(drawTimes));
        System.out.println("SYNTHETIC PREVIEW ONLY: visual asset quality and physical motion are not accepted by these checks.");
        if (restDiff != 0 || renderDiff != 0 || closureDiff != 0 || !fits)
            throw new IllegalStateException("Preview structural check failed; see report and generated images");
    }

    static void apply(LayeredRig rig, String pose, double t) {
        Skeleton s = rig.skeleton();
        for (String bone : s.names()) s.setDelta(bone, 0);
        boolean legs = pose.equals("knees-ankles") || pose.equals("combined");
        boolean arms = pose.equals("arms") || pose.equals("combined");
        boolean flex = pose.contains("flex") || pose.equals("hair-sleeves-coat") || pose.equals("combined");
        if (legs) {
            s.setDelta("thighNear", 17 * Math.sin(t)); s.setDelta("thighFar", -17 * Math.sin(t));
            s.setDelta("shinNear", 17 + 17 * Math.sin(t - 0.5)); s.setDelta("shinFar", 17 - 17 * Math.sin(t - 0.5));
            s.setDelta("footNear", 9 * Math.sin(t + 0.5)); s.setDelta("footFar", -9 * Math.sin(t + 0.5));
        }
        if (arms) {
            s.setDelta("upperArm", 15 * Math.sin(t)); s.setDelta("foreArm", -21 * Math.sin(t + 0.4));
            s.setDelta("upperArmFar", -13 * Math.sin(t)); s.setDelta("foreArmFar", 18 * Math.sin(t - 0.3));
            s.setDelta("hand", 4 * Math.sin(t + 0.2)); s.setDelta("handFar", -4 * Math.sin(t + 0.2));
        }
        if (flex) {
            double direction = pose.equals("reverse-flex") ? -1 : 1;
            for (String bone : s.names()) {
                if (bone.matches("(hair|sleeve|coat)(Near|Far)[0-9]+")) {
                    boolean near = bone.contains("Near"), anchor = bone.endsWith("0");
                    // Sleeve roots stay attached to the forearm; their distal segments bend independently.
                    double amplitude = anchor ? (bone.startsWith("sleeve") ? 0 : 3) : 11;
                    s.setDelta(bone, direction * amplitude * Math.sin(t - (near ? 0.45 : 0.9)) * (near ? 1 : -1));
                }
            }
            s.setDelta("head", 3 * Math.sin(t - 0.2));
            s.setDelta("lantern", 5 * Math.sin(t - 0.7));
        }
        rig.update();
    }

    private static double phase(int index) { return index * Math.PI * 2 / FRAMES_PER_CYCLE; }

    private static int requiredPadding(LayeredRig rig) {
        double padding = 0;
        for (LayeredRig.Layer layer : rig.layers()) {
            MeshWarp mesh = layer.skin.mesh();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                double x = mesh.dstX(i) + layer.x, y = mesh.dstY(i) + layer.y;
                padding = Math.max(padding, Math.max(Math.max(-x, -y), Math.max(x - rig.width(), y - rig.height())));
            }
        }
        return (int) Math.ceil(padding) + 8;
    }

    static int difference(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) throw new IllegalArgumentException("Image sizes differ");
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getRGB(x, y) != b.getRGB(x, y)) count++;
        return count;
    }

    private static BufferedImage diffImage(BufferedImage a, BufferedImage b) {
        BufferedImage result = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (a.getRGB(x, y) != b.getRGB(x, y)) result.setRGB(x, y, 0xffff00ff);
        return result;
    }

    private static void checker(Graphics2D g, int x, int y, int width, int height) {
        for (int iy = 0; iy < height; iy += 18) for (int ix = 0; ix < width; ix += 18) {
            g.setColor(new Color(((ix / 18 + iy / 18) % 2 == 0) ? 0xF1F4F8 : 0xDEE5ED));
            g.fillRect(x + ix, y + iy, Math.min(18, width - ix), Math.min(18, height - iy));
        }
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String timing(double[] samples) {
        double[] sorted = samples.clone(); Arrays.sort(sorted);
        return "{\"mean\":" + number(Arrays.stream(samples).average().orElse(0)) + ",\"p95\":"
                + number(sorted[(int) Math.ceil(sorted.length * 0.95) - 1]) + ",\"max\":" + number(sorted[sorted.length - 1]) + "}";
    }
}
