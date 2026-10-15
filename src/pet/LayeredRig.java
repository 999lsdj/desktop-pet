package pet;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;

/** A hash-checked, material-constrained rig used only by the offscreen preview. */
public final class LayeredRig {
    public static final class Layer {
        public final String name;
        public final String group;
        public final List<String> bones;
        public final Skin skin;
        public final int x;
        public final int y;
        public final int visiblePixels;
        private final BufferedImage image;

        private Layer(String name, String group, List<String> bones, BufferedImage image,
                      Skeleton skeleton, int step, double falloff) {
            this.name = name;
            this.group = group;
            this.bones = List.copyOf(bones);
            this.image = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                    ((DataBufferInt) this.image.getRaster().getDataBuffer()).getData(), 0, image.getWidth());
            int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1, count = 0;
            for (int iy = 0; iy < image.getHeight(); iy++) {
                for (int ix = 0; ix < image.getWidth(); ix++) {
                    if ((image.getRGB(ix, iy) >>> 24) != 0) {
                        minX = Math.min(minX, ix); minY = Math.min(minY, iy);
                        maxX = Math.max(maxX, ix); maxY = Math.max(maxY, iy); count++;
                    }
                }
            }
            visiblePixels = count;
            // Global grid alignment gives overlapping material layers the exact same triangles.
            x = count == 0 ? 0 : (minX / step) * step;
            y = count == 0 ? 0 : (minY / step) * step;
            int width = count == 0 ? step : ((maxX + 1 + step - 1) / step) * step - x;
            int height = count == 0 ? step : ((maxY + 1 + step - 1) / step) * step - y;
            BufferedImage crop = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int iy = 0; iy < height && y + iy < image.getHeight(); iy++) {
                for (int ix = 0; ix < width && x + ix < image.getWidth(); ix++) {
                    crop.setRGB(ix, iy, image.getRGB(x + ix, y + iy));
                }
            }
            skin = new Skin(crop, skeleton, width / step, height / step, falloff, bones, x, y);
        }

        public double maxDisplacement() {
            MeshWarp mesh = skin.mesh();
            double max = 0;
            for (int i = 0; i < mesh.vertexCount(); i++) {
                max = Math.max(max, Math.hypot(mesh.dstX(i) - mesh.srcX(i), mesh.dstY(i) - mesh.srcY(i)));
            }
            return max;
        }
    }

    private final int width;
    private final int height;
    private final Skeleton skeleton;
    private final List<Layer> layers;
    private final BufferedImage reference;
    private double lastUpdateMs;
    private double lastDrawMs;

    private LayeredRig(int width, int height, Skeleton skeleton, List<Layer> layers,
                       BufferedImage reference) {
        this.width = width; this.height = height; this.skeleton = skeleton;
        this.layers = List.copyOf(layers); this.reference = reference;
        update();
    }

    public static LayeredRig load(Path directory) throws IOException {
        Path base = directory.toRealPath();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(base.resolve("rig.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!required(properties, "version").equals("1")) throw new IOException("Unsupported rig version");
        String[] canvas = required(properties, "canvas").split(",");
        if (canvas.length != 2) throw new IOException("Invalid canvas");
        int width = positiveInt(canvas[0]), height = positiveInt(canvas[1]);
        int step = positiveInt(required(properties, "grid.step"));
        double falloff = finite(required(properties, "skin.falloff"));
        if (falloff <= 0 || width > 4096 || height > 4096 || step > 64) throw new IOException("Invalid rig size/falloff");
        Skeleton skeleton = Skeleton.empty();
        Map<String, double[]> starts = new HashMap<>();
        for (String name : names(properties, "bones")) {
            String[] bone = required(properties, "bone." + name).split(",", -1);
            if (bone.length != 5) throw new IOException("Invalid bone: " + name);
            String parent = bone[0].isEmpty() ? null : bone[0];
            double x = finite(bone[1]), y = finite(bone[2]), endX = finite(bone[3]), endY = finite(bone[4]);
            double[] origin = parent == null ? new double[]{0, 0} : starts.get(parent);
            if (origin == null) throw new IOException("Parent must precede child: " + name);
            skeleton.addBone(name, parent, x - origin[0], y - origin[1],
                    Math.toDegrees(Math.atan2(endY - y, endX - x)), Math.hypot(endX - x, endY - y));
            starts.put(name, new double[]{x, y});
        }
        List<Layer> layers = new ArrayList<>();
        Map<String, List<String>> groupBones = new HashMap<>();
        for (String name : names(properties, "parts")) {
            String key = "layer." + name;
            List<String> bones = names(properties, key + ".bones");
            if (!starts.keySet().containsAll(bones)) throw new IOException("Unknown layer bone: " + name);
            String group = required(properties, key + ".group");
            List<String> previous = groupBones.putIfAbsent(group, bones);
            if (previous != null && !previous.equals(bones)) throw new IOException("Inconsistent material group: " + group);
            BufferedImage image = verifiedImage(base, properties, key, width, height);
            layers.add(new Layer(name, group, bones, image, skeleton, step, falloff));
        }
        BufferedImage reference = verifiedImage(base, properties, "reference", width, height);
        return new LayeredRig(width, height, skeleton, layers, reference);
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing property: " + key);
        return value.trim();
    }

    private static List<String> names(Properties p, String key) throws IOException {
        List<String> result = Arrays.asList(required(p, key).split(",", -1));
        if (result.stream().anyMatch(s -> !s.matches("[A-Za-z][A-Za-z0-9]*"))
                || result.stream().distinct().count() != result.size()) throw new IOException("Invalid names: " + key);
        return result;
    }

    private static int positiveInt(String value) throws IOException {
        try {
            int result = Integer.parseInt(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) { throw new IOException("Invalid positive integer: " + value, e); }
    }

    private static double finite(String value) throws IOException {
        try {
            double result = Double.parseDouble(value);
            if (!Double.isFinite(result)) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) { throw new IOException("Invalid finite number: " + value, e); }
    }

    private static BufferedImage verifiedImage(Path base, Properties p, String key, int w, int h) throws IOException {
        Path path = base.resolve(required(p, key + ".file")).normalize().toRealPath();
        if (!path.startsWith(base)) throw new IOException("Image path escape: " + path);
        byte[] bytes = Files.readAllBytes(path);
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equalsIgnoreCase(required(p, key + ".sha256"))) throw new IOException("SHA-256 mismatch: " + key);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != w || image.getHeight() != h) throw new IOException("Invalid image: " + key);
        return image;
    }

    public void resetPose() {
        for (String bone : skeleton.names()) skeleton.setDelta(bone, 0);
        update();
    }

    public void update() {
        long start = System.nanoTime();
        skeleton.update();
        for (Layer layer : layers) layer.skin.update();
        lastUpdateMs = (System.nanoTime() - start) / 1e6;
    }

    public BufferedImage restComposite() {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (Layer layer : layers) composite(result, layer.image, 0, 0);
        return result;
    }

    /** Rasterize each material separately, then compose once to avoid Java2D premultiply rounding at rest. */
    public BufferedImage render(int padding) {
        if (padding < 0) throw new IllegalArgumentException("Negative padding");
        long start = System.nanoTime();
        BufferedImage result = new BufferedImage(width + 2 * padding, height + 2 * padding, BufferedImage.TYPE_INT_ARGB);
        BufferedImage scratch = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (Layer layer : layers) {
            if (layer.maxDisplacement() < 1e-8) {
                composite(result, layer.image, padding, padding);
            } else {
                Arrays.fill(((DataBufferInt) scratch.getRaster().getDataBuffer()).getData(), 0);
                Graphics2D g = scratch.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                layer.skin.draw(g, padding + layer.x, padding + layer.y);
                g.dispose();
                composite(result, scratch, 0, 0);
            }
        }
        lastDrawMs = (System.nanoTime() - start) / 1e6;
        return result;
    }

    private static void composite(BufferedImage dst, BufferedImage src, int dx, int dy) {
        int[] source = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] destination = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        int sw = src.getWidth(), dw = dst.getWidth();
        for (int y = 0; y < src.getHeight(); y++) {
            int sourceRow = y * sw, destinationRow = (y + dy) * dw + dx;
            for (int x = 0; x < sw; x++) {
                int s = source[sourceRow + x], sa = s >>> 24;
                if (sa == 0) continue;
                int position = destinationRow + x;
                int d = destination[position], da = d >>> 24;
                if (sa == 255 || da == 0) { destination[position] = s; continue; }
                long alpha = sa * 255L + da * (255L - sa);
                int result = (int) ((alpha + 127) / 255) << 24;
                for (int shift = 0; shift <= 16; shift += 8) {
                    long channel = ((s >> shift) & 255) * sa * 255L
                            + ((d >> shift) & 255) * da * (255L - sa);
                    result |= (int) ((channel + alpha / 2) / alpha) << shift;
                }
                destination[position] = result;
            }
        }
    }

    public boolean fits(int padding) {
        for (Layer layer : layers) {
            MeshWarp mesh = layer.skin.mesh();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                double x = mesh.dstX(i) + layer.x, y = mesh.dstY(i) + layer.y;
                if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
                if (x < -padding + 1 || y < -padding + 1 || x > width + padding - 1 || y > height + padding - 1) return false;
            }
        }
        return true;
    }

    public int width() { return width; }
    public int height() { return height; }
    public Skeleton skeleton() { return skeleton; }
    public List<Layer> layers() { return layers; }
    public BufferedImage reference() { return reference; }
    public double lastUpdateMs() { return lastUpdateMs; }
    public double lastDrawMs() { return lastDrawMs; }
}
