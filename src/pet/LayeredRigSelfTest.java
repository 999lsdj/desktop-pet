package pet;

import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;

/** Headless regression checks; no Swing windows or pet runtime are started. */
public final class LayeredRigSelfTest {
    public static void main(String[] args) throws Exception {
        Skeleton skeleton = Skeleton.empty();
        skeleton.addBone("root", null, 50, 81, -90, 34);
        skeleton.addBone("head", "root", 0, -34, -90, 30);
        skeleton.addBone("staff", "root", -25, -12, 90, 52);
        skeleton.addBone("thighNear", "root", -6, 0, 90, 16);
        skeleton.addBone("shinNear", "thighNear", 0, 16, 90, 11);
        skeleton.addBone("thighFar", "root", 6, 0, 90, 16);
        BufferedImage image = new BufferedImage(105, 123, BufferedImage.TYPE_INT_ARGB);
        Skin staff = restrictedSkin(image, skeleton, List.of("staff"));
        Skin hair = restrictedSkin(image, skeleton, List.of("head"));
        staff.update();
        hair.update();
        assertRest(staff);
        assertRest(hair);
        skeleton.setDelta("thighNear", 25);
        skeleton.setDelta("shinNear", 40);
        skeleton.setDelta("thighFar", -20);
        skeleton.update();
        staff.update();
        hair.update();
        assertRest(staff);
        assertRest(hair);
        System.out.println("PASS: allowed bone sets isolate staff/hair; rest vertices do not drift");
        Skin a = new Skin(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), skeleton,
                4, 4, 16, List.of("thighNear", "shinNear"), 36, 88);
        Skin b = new Skin(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), skeleton,
                4, 4, 16, List.of("thighNear", "shinNear"), 40, 96);
        a.update(); b.update();
        for (int ay = 2; ay <= 4; ay++) {
            for (int ax = 1; ax <= 4; ax++) {
                int ai = ay * 5 + ax, bi = (ay - 2) * 5 + ax - 1;
                near(a.mesh().dstX(ai) + 36, b.mesh().dstX(bi) + 40, "overlapping material X diverged");
                near(a.mesh().dstY(ai) + 88, b.mesh().dstY(bi) + 96, "overlapping material Y diverged");
            }
        }
        testBundle();
        testMeshClipAndTransparency();
        testNonFiniteAssertions(staff);
        System.out.println("PASS: shared material field, exact RGBA rest composition, SHA and topology checks");
        System.out.println("PASS: NaN/infinity poses, coordinates and numeric assertions are rejected");
    }

    private static Skin restrictedSkin(BufferedImage image, Skeleton s, List<String> bones) {
        return new Skin(image, s, 8, 10, 16, bones, 0, 0);
    }

    private static void assertRest(Skin skin) {
        MeshWarp mesh = skin.mesh();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double d = Math.hypot(mesh.dstX(i) - mesh.srcX(i), mesh.dstY(i) - mesh.srcY(i));
            if (!Double.isFinite(d) || d > 1e-8)
                throw new AssertionError("invalid or displaced material: " + d + "px at vertex " + i);
        }
    }

    private static void near(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || !Double.isFinite(expected) || Math.abs(actual - expected) > 1e-8)
            throw new AssertionError(message + ": " + actual + " != " + expected);
    }

    private static void testBundle() throws Exception {
        Files.createDirectories(Path.of("_tmp"));
        Path dir = Files.createTempDirectory(Path.of("_tmp"), "layered-selftest-");
        BufferedImage back = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        back.setRGB(1, 1, 0x7f0e5da2);
        back.setRGB(4, 4, 0xff1e2832);
        back.setRGB(6, 6, 0x80402010);
        BufferedImage front = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        front.setRGB(4, 4, 0xff789abc);
        front.setRGB(5, 5, 0x80593f17);
        front.setRGB(6, 6, 0x80080c10);
        BufferedImage reference = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        reference.setRGB(1, 1, 0x7f0e5da2);
        reference.setRGB(4, 4, 0xff789abc);
        reference.setRGB(5, 5, 0x80593f17);
        reference.setRGB(6, 6, 0xc01b1310);
        ImageIO.write(back, "png", dir.resolve("back.png").toFile());
        ImageIO.write(front, "png", dir.resolve("front.png").toFile());
        ImageIO.write(reference, "png", dir.resolve("reference.png").toFile());
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("canvas", "12,12");
        p.setProperty("grid.step", "4"); p.setProperty("skin.falloff", "16");
        p.setProperty("bones", "root,leg"); p.setProperty("bone.root", ",4,4,4,4");
        p.setProperty("bone.leg", "root,4,4,4,8"); p.setProperty("parts", "back,front");
        for (String name : List.of("back", "front", "reference")) {
            String prefix = name.equals("reference") ? name : "layer." + name;
            p.setProperty(prefix + ".file", name + ".png");
            p.setProperty(prefix + ".sha256", sha(dir.resolve(name + ".png")));
            if (!name.equals("reference")) {
                p.setProperty(prefix + ".group", "cloth"); p.setProperty(prefix + ".bones", "leg");
            }
        }
        save(p, dir);
        LayeredRig rig = LayeredRig.load(dir);
        near(rig.skeleton().start("leg").x, 4, "loaded absolute origin");
        BufferedImage result = rig.render(8);
        for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
            if (result.getRGB(x + 8, y + 8) != reference.getRGB(x, y))
                throw new AssertionError("rest RGBA changed at " + x + "," + y);
        }
        rig.skeleton().setDelta("leg", 35); rig.update();
        double displacement = rig.layers().get(0).maxDisplacement();
        if (!Double.isFinite(displacement) || displacement < 1) throw new AssertionError("invalid or undeformed layer");
        if (!rig.fits(8)) throw new AssertionError("expected safe padding");
        for (double angle : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            rig.skeleton().setDelta("leg", angle); rig.update();
            if (rig.fits(8)) throw new AssertionError("non-finite pose incorrectly fits: " + angle);
        }
        rig.resetPose();
        if (!rig.fits(8)) throw new AssertionError("finite pose did not recover after reset");
        MeshWarp mesh = rig.layers().get(0).skin.mesh();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            mesh.setVertex(0, value, mesh.srcY(0));
            if (rig.fits(8)) throw new AssertionError("non-finite X coordinate incorrectly fits: " + value);
            rig.resetPose();
            mesh.setVertex(0, mesh.srcX(0), value);
            if (rig.fits(8)) throw new AssertionError("non-finite Y coordinate incorrectly fits: " + value);
            rig.resetPose();
        }
        p.setProperty("parts", "back"); save(p, dir);
        LayeredRig rigid = LayeredRig.load(dir);
        for (int degrees = 1; degrees < 70; degrees += 3) {
            rigid.skeleton().setDelta("leg", degrees); rigid.update();
            BufferedImage expected = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = expected.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            AffineTransform transform = AffineTransform.getTranslateInstance(8, 8);
            transform.rotate(Math.toRadians(degrees), 4, 4);
            g.drawImage(back, transform, null); g.dispose();
            if (LayeredRigPreview.difference(expected, rigid.render(8)) != 0)
                throw new AssertionError("single-bone layer differs from rigid image at " + degrees + " degrees");
        }
        p.setProperty("parts", "back,front");
        p.setProperty("layer.front.bones", "root"); save(p, dir);
        rejects(dir, "Inconsistent material group");
        p.setProperty("layer.front.bones", "leg"); p.setProperty("bones", "leg,root"); save(p, dir);
        rejects(dir, "Parent must precede child");
        p.setProperty("bones", "root,leg"); save(p, dir);
        Files.write(dir.resolve("front.png"), new byte[]{1, 2, 3});
        rejects(dir, "SHA-256 mismatch");
    }

    private static void testMeshClipAndTransparency() {
        BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(7, 0, 0xff112233);
        MeshWarp mesh = new MeshWarp(source, 1, 1);
        BufferedImage canvas = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setClip(new Rectangle(0, 0, 2, 2));
        mesh.draw(g, 0, 0);
        if (canvas.getRGB(7, 0) != 0) throw new AssertionError("mesh ignored the caller's clip");
        g.setColor(Color.RED); g.fillRect(0, 0, 12, 12); g.dispose();
        if (canvas.getRGB(7, 7) != 0) throw new AssertionError("mesh failed to restore the caller's clip");
        if (mesh.triangleCount() != 1) throw new AssertionError("transparent triangle was not culled");
        BufferedImage transparent = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        if (new MeshWarp(transparent, 2, 2).triangleCount() != 0)
            throw new AssertionError("empty mesh retains triangles");
        source.setRGB(4, 4, 0xff123456);
        if (new MeshWarp(source, 1, 1).triangleCount() != 2)
            throw new AssertionError("pixel crossing the diagonal was incorrectly culled");
    }

    private static void testNonFiniteAssertions(Skin skin) {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectsAssertion(() -> near(value, 0, "invalid actual"));
            expectsAssertion(() -> near(0, value, "invalid expected"));
            expectsAssertion(() -> near(value, value, "invalid pair"));
            skin.mesh().setVertex(0, value, skin.mesh().srcY(0));
            expectsAssertion(() -> assertRest(skin));
            skin.mesh().setVertex(0, skin.mesh().srcX(0), value);
            expectsAssertion(() -> assertRest(skin));
            skin.update();
            assertRest(skin);
        }
    }

    private static void expectsAssertion(Runnable check) {
        try { check.run(); }
        catch (AssertionError expected) { return; }
        throw new AssertionError("non-finite value passed a numeric assertion");
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void save(Properties p, Path dir) throws Exception {
        try (var writer = Files.newBufferedWriter(dir.resolve("rig.properties"), StandardCharsets.UTF_8)) {
            p.store(writer, "controlled test fixture");
        }
    }

    private static void rejects(Path path, String expected) throws Exception {
        try { LayeredRig.load(path); }
        catch (java.io.IOException e) {
            if (e.getMessage().contains(expected)) return;
            throw e;
        }
        throw new AssertionError("accepted invalid bundle: " + expected);
    }
}
