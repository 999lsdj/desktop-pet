package pet;

import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * 桌宠主窗口：沿屏幕底边踱步、微醺摇晃、提灯单摆、放下挂灯靠着打盹、点击停下展开入口。
 *
 * 两个关键实现点：
 * 1) 绘制在设备像素空间进行（启动加 -Dsun.java2d.uiScale=1），像素图按整数倍放大不发虚。
 * 2) 窗口裁剪形状由"动作范围包络"生成：把角色在极限姿态下各渲染一次并取并集，
 *    这样身体倾斜、提灯甩动、迈步时都不会被自己的窗口形状切掉。
 */
public final class PetWindow extends JWindow {

    private enum State { WALK, PAUSE, REST, MENU, HIDDEN }

    static final int SPRITE_SCALE = 2;
    private static final int GROUND_PAD = 12;
    /** 累的时候向后靠的角度（负数＝朝提灯那一侧） */
    private static final double REST_LEAN = -13;
    /** 放下后提灯自己立在地上的倾斜角 */
    private static final double PLACED_LEAN = 12;
    /** 放下时提灯相对握点额外挪开的距离与位置 */
    private static final double PLACED_DX = -26;
    /** 身体左右摇摆的极限（度），用于生成窗口形状包络 */
    private static final double TILT_ENVELOPE = 16;
    /** 提灯摆动的极限（度），用于生成窗口形状包络 */
    private static final double STAFF_ENVELOPE = 35;

    private static final double[] MENU_ANGLES = {200, 270, 340};
    private static final String[] BUTTON_NAMES = {"计划表", "常用文件", "视频网站"};
    private static final PixelIcon[] BUTTON_ICONS =
            {PixelIcon.PLAN, PixelIcon.FILES, PixelIcon.VIDEO};

    private final Config config;
    private final boolean still;
    private final BodyRig rig;
    private final Random random = new Random();
    private final FullscreenWatcher watcher;
    private final Pendulum pendulum;

    private final double dpiScale;
    private final int spriteW;
    private final int spriteH;
    private final int windowW;
    private final int windowH;
    private final int groundX;
    private final int groundY;
    private final int chestY;
    private final int menuRadius;
    private final int buttonDiameter;
    private final int fontSize;
    private final double walkSpeed;
    private final Shape envelopeShape;

    private State state = State.PAUSE;
    private double posX;
    private double groundLine;
    private double dir = 1;
    private boolean mirrored;
    private long now;
    private long stateUntil;
    private long nextWalkEnd;
    private long nextRestAt;
    private long nextStumbleAt;
    private long stumbleUntil;
    private double walkPhase;
    private double restAmount;      // 0 站着，1 靠着睡
    private double staffPlace;      // 0 握在手里，1 立在身边
    private boolean pendingMenu;
    private double menuProgress;
    private long menuAnimStart;
    private boolean menuAnimating;
    private int hoverIndex = -1;
    private boolean hoverBody;
    private double speedFactor = 1.0;
    private String bubble;
    private long bubbleUntil;

    private double gripPrevX;
    private double gripVel;
    private double lastStepPhase;

    private Point pressScreen;
    private Point pressWindow;
    private boolean dragging;

    public PetWindow(Config config, boolean demo, boolean selfTest, boolean restTest,
                     boolean ignoreFullscreen, boolean still) throws Exception {
        super();
        this.config = config;
        this.still = still;

        dpiScale = Math.max(1.0, Toolkit.getDefaultToolkit().getScreenResolution() / 96.0);
        menuRadius = (int) Math.round(config.d("menu.radius.logical", 105) * dpiScale);
        buttonDiameter = (int) Math.round(config.d("button.diameter.logical", 56) * dpiScale);
        fontSize = (int) Math.round(13 * dpiScale);
        walkSpeed = config.d("walk.speed.logical", 30) * dpiScale;

        rig = BodyRig.load(Paths.get("assets", "sprites", "pet-base.png").toAbsolutePath());
        spriteW = rig.width() * SPRITE_SCALE;
        spriteH = rig.height() * SPRITE_SCALE;

        int headroom = menuRadius + buttonDiameter / 2 + (int) Math.round(16 * dpiScale);
        windowH = headroom + spriteH + GROUND_PAD;
        // 窗口要同时容得下"站着的角色"和"立在身边的提灯"
        int leftRoom = (int) Math.ceil(Math.max(spriteW / 2.0,
                menuRadius * Math.cos(Math.toRadians(20)) + buttonDiameter / 2.0))
                + (int) Math.round(30 * dpiScale);
        int rightRoom = spriteW / 2 + (int) Math.round(30 * dpiScale);
        groundX = leftRoom;
        windowW = groundX + rightRoom;
        groundY = headroom + spriteH;
        chestY = groundY - (int) Math.round(spriteH * 0.58);

        envelopeShape = buildEnvelopeShape();

        pendulum = new Pendulum(config.d("lantern.max.deg", 35),
                config.d("lantern.length.device", 70),
                config.d("lantern.damping", 0.9));

        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(new Dimension(windowW, windowH));
        placeInitially();
        try {
            setShape(envelopeShape);
        } catch (Exception e) {
            Log.error("窗口裁剪失败", e);
        }

        PetPanel panel = new PetPanel();
        panel.setOpaque(false);
        setContentPane(panel);
        installMouseHandlers(panel);
        installPopupMenu(panel);
        installTrayIcon();

        watcher = ignoreFullscreen ? FullscreenWatcher.disabled()
                : new FullscreenWatcher(Paths.get("tools", "fullscreen-watch.ps1").toAbsolutePath(),
                        "powershell.exe");

        now = System.currentTimeMillis();
        stateUntil = now + 1200;
        nextWalkEnd = now + 8000;
        nextRestAt = now + (long) (config.d("rest.interval.min.minutes", 2) * 60000
                * (0.6 + random.nextDouble() * 0.4));
        nextStumbleAt = now + 15000;
        new Timer(16, e -> onTick()).start();

        Log.info("窗口初始化: " + windowW + "x" + windowH + " | DPI=" + dpiScale
                + " | 精灵=" + rig.width() + "x" + rig.height() + " 显示=" + spriteW + "x" + spriteH
                + " | 地面=(" + groundX + "," + groundY + ")"
                + " | 走路=" + String.format("%.1f", walkSpeed) + " 设备像素/秒");

        if (demo) {
            later(1500, () -> openMenu(true));
        }
        if (selfTest) {
            later(2000, this::selfTestClick);
        }
        if (restTest) {
            later(1200, this::enterRest);
        }
    }

    private void later(int ms, Runnable action) {
        Timer t = new Timer(ms, e -> action.run());
        t.setRepeats(false);
        t.start();
    }

    public void open() {
        setVisible(true);
        Log.info("桌宠已显示");
    }

    private void placeInitially() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        groundLine = screen.height;
        posX = screen.width - windowW - 80;
        setLocation((int) Math.round(posX), (int) Math.round(groundLine) - groundY);
    }

    // ------------------------------------------------------------- 状态机

    private void onTick() {
        long previous = now;
        now = System.currentTimeMillis();
        double dt = Math.min(0.05, Math.max(0.001, (now - previous) / 1000.0));

        if (watcher.isFullscreen()) {
            if (isVisible()) {
                setVisible(false);
                Log.info("前台是全屏程序，桌宠完全隐藏");
            }
            return;
        }
        if (!isVisible()) {
            setVisible(true);
            groundLine = Toolkit.getDefaultToolkit().getScreenSize().height;
            Log.info("全屏结束，桌宠重新出现");
        }

        updateGround(dt);
        updateState(dt);
        updateMotion(dt);
        if (bubble != null && now > bubbleUntil) {
            bubble = null;
            repaint();
        }
    }

    private void updateGround(double dt) {
        int target = watcher.groundY();
        if (target < 0) {
            return;
        }
        groundLine += (target - groundLine) * Math.min(1.0, dt * 6);
        if (Math.abs(target - groundLine) < 0.4) {
            groundLine = target;
        }
    }

    private void updateState(double dt) {
        switch (state) {
            case WALK -> {
                posX += dir * walkSpeed * speedFactor * dt;
                walkPhase += dt * 5.2 * speedFactor;
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                if (posX < 0) {
                    posX = 0;
                    turn();
                } else if (posX > screen.width - windowW) {
                    posX = screen.width - windowW;
                    turn();
                }
                if (now > nextWalkEnd) {
                    enterPause();
                }
            }
            case PAUSE -> {
                if (now > stateUntil) {
                    enterWalk();
                }
            }
            case REST -> {
                restAmount = Math.min(1.0, restAmount + dt / 1.1);
                staffPlace = Math.min(1.0, staffPlace + dt / 0.7);
                if (now > stateUntil && restAmount > 0.98) {
                    wakeUp(false);
                }
            }
            case MENU -> {
                restAmount = Math.max(0.0, restAmount - dt / 0.8);
                staffPlace = Math.max(0.0, staffPlace - dt / 0.5);
            }
            default -> {
            }
        }

        if (pendingMenu && restAmount < 0.03) {
            pendingMenu = false;
            openMenu(true);
        }
        if ((state == State.WALK || state == State.PAUSE) && now > nextRestAt) {
            enterRest();
        }
        // 踉跄幅度设成 0 就整个关掉，不再打日志也不再踢提灯
        if (config.d("stumble.tilt.deg", 15) > 0.01 && now > nextStumbleAt && state != State.REST) {
            stumbleUntil = now + 520;
            nextStumbleAt = now + randSeconds("stumble.interval.min.sec", 20,
                    "stumble.interval.max.sec", 40) * 1000L;
            pendulum.kick(1.4 * (random.nextBoolean() ? 1 : -1));
            Log.info("踉跄一下");
        }
        if (menuAnimating) {
            double t = Math.min(1.0, (now - menuAnimStart) / 180.0);
            menuProgress = menuProgress > 0.5 ? ease(t) : 1 - ease(t);
            if (t >= 1.0) {
                menuProgress = menuProgress > 0.5 ? 1 : 0;
                menuAnimating = false;
            }
            applyShape();
        }
        setLocation((int) Math.round(posX), (int) Math.round(groundLine) - groundY);
    }

    private double currentTilt() {
        double sway = config.d("sway.tilt.deg", 8)
                * Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5))
                * (1 - 0.75 * restAmount);
        double stumble = 0;
        if (now < stumbleUntil) {
            stumble = config.d("stumble.tilt.deg", 15)
                    * Math.sin(Math.PI * (1 - (stumbleUntil - now) / 520.0));
        }
        return sway + stumble + restAmount * REST_LEAN;
    }

    private double currentBob() {
        if (state == State.WALK) {
            // 步频与迈步同相，走起来才有踩地的节奏
            return -Math.abs(Math.sin(walkPhase)) * config.d("bob.device.px", 4);
        }
        return Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5))
                * config.d("bob.device.px", 4) * 0.5 * (1 - 0.6 * restAmount);
    }

    private void updateMotion(double dt) {
        double tilt = currentTilt();
        double gripLocalX = (rig.grip().x - rig.width() / 2.0) * SPRITE_SCALE;
        double gripLocalY = (rig.grip().y - rig.height()) * SPRITE_SCALE;
        double rad = Math.toRadians(tilt);
        double flipSign = mirrored ? -1 : 1;
        double gripWorldX = getX() + groundX + flipSign * (gripLocalX * Math.cos(rad) - gripLocalY * Math.sin(rad));
        double velocity = (gripWorldX - gripPrevX) / dt;
        double accel = (velocity - gripVel) / dt;
        gripPrevX = gripWorldX;
        gripVel = velocity;

        if (restAmount > 0.05 || staffPlace > 0.05) {
            // 放下了就不再甩
            pendulum.setDamping(config.d("lantern.damping", 0.9) * 2.5);
        } else {
            pendulum.setDamping(config.d("lantern.damping", 0.9));
            // 走路的每一步都给提灯一个推力，这是灯甩起来的主要来源
            if (state == State.WALK && walkPhase - lastStepPhase > Math.PI) {
                lastStepPhase = walkPhase;
                pendulum.kick((random.nextBoolean() ? 1 : -1) * 0.32);
            }
            if (state != State.WALK) {
                lastStepPhase = walkPhase;
            }
        }
        double gain = config.d("lantern.drive.gain", 5.0);
        pendulum.update(dt, accel * gain, config.d("lantern.gravity", 900));
        repaint();
    }

    private void turn() {
        dir = -dir;
        mirrored = !mirrored;
        pendulum.kick(1.1 * dir);
    }

    private void enterWalk() {
        if (still) {
            state = State.PAUSE;
            stateUntil = now + 600000;
            return;
        }
        state = State.WALK;
        walkPhase = 0;
        lastStepPhase = 0;
        nextWalkEnd = now + randSeconds("walk.stretch.min.sec", 8, "walk.stretch.max.sec", 20) * 1000L;
        pendulum.kick(1.2);
        Log.info("开始踱步（" + (dir > 0 ? "向右" : "向左") + "）");
    }

    private void enterPause() {
        state = State.PAUSE;
        stateUntil = now + randSeconds("walk.pause.min.sec", 3, "walk.pause.max.sec", 10) * 1000L;
        pendulum.kick(-0.9);
        Log.info("停下歇一会");
    }

    private void enterRest() {
        if (state == State.REST) {
            return;
        }
        state = State.REST;
        stateUntil = now + randSeconds("rest.duration.min.sec", 20, "rest.duration.max.sec", 60) * 1000L;
        nextRestAt = now + randSeconds("rest.interval.min.minutes", 2,
                "rest.interval.max.minutes", 5) * 60000L;
        Log.info("放下挂灯，靠着歇一会");
    }

    private void wakeUp(boolean immediate) {
        state = State.PAUSE;
        stateUntil = now + (immediate ? 200 : 900);
        Log.info("拿起挂灯起身");
    }

    private void openMenu(boolean open) {
        if (open) {
            state = State.MENU;
            menuAnimStart = now;
            menuAnimating = true;
            menuProgress = 0.001;
            Log.info("停下，摊开手展示任务入口");
        } else {
            menuAnimStart = now;
            menuAnimating = true;
            state = State.PAUSE;
            stateUntil = now + 1500;
            Log.info("收起任务入口，继续踱步");
        }
        applyShape();
    }

    private long randSeconds(String minKey, double minDefault, String maxKey, double maxDefault) {
        double lo = config.d(minKey, minDefault);
        double hi = Math.max(lo, config.d(maxKey, maxDefault));
        return Math.round(lo + random.nextDouble() * (hi - lo));
    }

    private static double ease(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    // ------------------------------------------------------------- 形状

    /**
     * 动作范围包络：把角色在几个极限姿态下各渲染一次，取所有不透明像素的并集。
     * 静态形状，不需要每帧重算，也不会在倾斜/甩灯/迈步时把角色切掉。
     */
    private Shape buildEnvelopeShape() {
        Area area = new Area();
        double[][] poses = {
                {TILT_ENVELOPE, STAFF_ENVELOPE},
                {-TILT_ENVELOPE, -STAFF_ENVELOPE},
                {TILT_ENVELOPE, -STAFF_ENVELOPE},
                {-TILT_ENVELOPE, STAFF_ENVELOPE},
                {REST_LEAN, PLACED_LEAN},
        };
        for (double[] pose : poses) {
            for (boolean flip : new boolean[]{false, true}) {
                BufferedImage frame = new BufferedImage(windowW, windowH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = frame.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.translate(groundX, groundY);
                renderRig(g, pose[0], pose[1], 0, 0, flip, 0);
                g.dispose();
                addAlphaTo(area, frame);
            }
        }
        // 放下提灯后的姿态
        for (boolean flip : new boolean[]{false, true}) {
            BufferedImage frame = new BufferedImage(windowW, windowH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = frame.createGraphics();
            g.translate(groundX, groundY);
            renderRig(g, REST_LEAN, 0, 1, 0, flip, 0);
            g.dispose();
            addAlphaTo(area, frame);
        }
        Log.info("窗口裁剪包络生成完成：" + poses.length * 2 + 2 + " 个姿态");
        return area;
    }

    /** 逐行取不透明像素的连续段，并向外扩 halo 像素，避免边缘被切。 */
    private void addAlphaTo(Area area, BufferedImage img) {
        int halo = (int) Math.round(3 * dpiScale);
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            int start = -1;
            for (int x = 0; x < w; x++) {
                boolean solid = (img.getRGB(x, y) >>> 24) > 8;
                if (solid && start < 0) {
                    start = x;
                }
                if (start >= 0 && (!solid || x == w - 1)) {
                    int end = solid ? x : x - 1;
                    area.add(new Area(new Rectangle2D.Double(
                            Math.max(0, start - halo), Math.max(0, y - halo),
                            end - start + 1 + halo * 2, 1 + halo * 2)));
                    start = -1;
                }
            }
        }
    }

    private void applyShape() {
        Area area = new Area(envelopeShape);
        if (menuProgress > 0.02) {
            for (int i = 0; i < MENU_ANGLES.length; i++) {
                Point c = buttonCenter(i);
                double d = buttonDiameter * (0.55 + 0.45 * menuProgress);
                area.add(new Area(new Ellipse2D.Double(c.x - d / 2, c.y - d / 2, d, d)));
            }
        }
        try {
            setShape(area);
        } catch (Exception e) {
            Log.error("设置窗口形状失败", e);
        }
    }

    private Point buttonCenter(int index) {
        double rad = Math.toRadians(MENU_ANGLES[index]);
        double r = menuRadius * menuProgress;
        return new Point(
                (int) Math.round(groundX + Math.cos(rad) * r),
                (int) Math.round(chestY + Math.sin(rad) * r));
    }

    private int buttonAt(Point p) {
        if (menuProgress < 0.75) {
            return -1;
        }
        for (int i = 0; i < MENU_ANGLES.length; i++) {
            Point c = buttonCenter(i);
            if (Math.hypot(p.x - c.x, p.y - c.y) <= buttonDiameter * 0.6) {
                return i;
            }
        }
        return -1;
    }

    private boolean bodyAt(Point p) {
        return p.x > groundX - spriteW * 0.55 && p.x < groundX + spriteW * 0.55
                && p.y > groundY - spriteH - 8 && p.y < groundY + 8;
    }

    // ------------------------------------------------------------- 绘制

    private final class PetPanel extends javax.swing.JPanel {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            double tilt = currentTilt();
            double bob = currentBob();
            double legSwing = state == State.WALK
                    ? Math.toRadians(13) * Math.sin(walkPhase) * speedFactor : 0;
            double lift = state == State.WALK ? 5 * dpiScale * speedFactor : 0;
            double staffAngle = staffPlace > 0.05 ? 0 : pendulum.angle();

            // 影子
            double shadowW = spriteW * 0.78;
            g.setColor(new Color(20, 25, 45, 62));
            g.fill(new Ellipse2D.Double(groundX - shadowW / 2, groundY - 7 * dpiScale,
                    shadowW, 14 * dpiScale));

            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(groundX, groundY + bob);
            renderRig(g2, tilt, staffAngle, staffPlace, legSwing, mirrored, lift);
            g2.dispose();

            drawMenu(g);
            if (restAmount > 0.3) {
                drawSleepMarks(g);
            }
            if (bubble != null) {
                drawBubble(g);
            }
            g.dispose();
        }
    }

    /**
     * 画出角色。传入的姿态参数都可以独立指定，这样同一套代码既能画实时画面，
     * 也能在启动时渲染极限姿态用来生成窗口形状。
     *
     * @param tilt      整体倾斜（度）
     * @param staffAngle 提灯相对身体的摆角（度）
     * @param place     提灯放下程度 0–1
     * @param legSwing  双腿前后摆角（弧度）
     * @param flip      是否水平镜像
     * @param lift      抬脚高度（设备像素）
     */
    private void renderRig(Graphics2D g, double tilt, double staffAngle, double place,
                           double legSwing, boolean flip, double lift) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.rotate(Math.toRadians(tilt));
        if (flip) {
            g2.scale(-1, 1);
        }

        for (int i = 0; i < 2; i++) {
            Point origin = rig.legOrigin(i);
            double lx = (origin.x - rig.width() / 2.0) * SPRITE_SCALE;
            double ly = (origin.y - rig.height()) * SPRITE_SCALE;
            double px = (rig.hip(i).x - rig.width() / 2.0) * SPRITE_SCALE;
            double py = (rig.hip(i).y - rig.height()) * SPRITE_SCALE;
            double angle = i == 0 ? legSwing : -legSwing;
            double footLift = Math.max(0, Math.sin(i == 0 ? walkPhase : walkPhase + Math.PI)) * lift;
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.rotate(angle, px, py);
            drawScaled(g3, rig.leg(i), lx, ly - footLift);
            g3.dispose();
        }

        drawScaled(g2, rig.body(), -spriteW / 2.0, -spriteH);

        Point origin = rig.staffOrigin();
        double localX = (origin.x - rig.width() / 2.0) * SPRITE_SCALE;
        double localY = (origin.y - rig.height()) * SPRITE_SCALE;
        double gripX = (rig.grip().x - rig.width() / 2.0) * SPRITE_SCALE;
        double gripY = (rig.grip().y - rig.height()) * SPRITE_SCALE;
        // 提灯底部在本地坐标里的位置（放在地上时绕它旋转）
        double footX = (rig.staffFoot().x - rig.width() / 2.0) * SPRITE_SCALE;
        double footY = (rig.staffFoot().y - rig.height()) * SPRITE_SCALE;

        double pivotX = gripX + (footX - gripX) * place;
        double pivotY = gripY + (footY - gripY) * place;
        double angle = staffAngle * (1 - place) + PLACED_LEAN * place;
        Graphics2D g4 = (Graphics2D) g2.create();
        g4.translate(PLACED_DX * place, 0);
        g4.rotate(Math.toRadians(angle), pivotX, pivotY);
        drawScaled(g4, rig.staff(), localX, localY);
        g4.dispose();
        g2.dispose();
    }

    private void drawScaled(Graphics2D g, BufferedImage img, double x, double y) {
        g.drawImage(img, (int) Math.round(x), (int) Math.round(y),
                img.getWidth() * SPRITE_SCALE, img.getHeight() * SPRITE_SCALE, null);
    }

    private void drawSleepMarks(Graphics2D g) {
        double phase = (now % 2600) / 2600.0;
        float alpha = (float) (restAmount * Math.min(1.0, Math.sin(phase * Math.PI) * 1.7));
        if (alpha <= 0.03f) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.setColor(new Color(0x2E3A5C));
        for (int i = 0; i < 3; i++) {
            int size = (int) Math.round((11 + i * 4) * dpiScale);
            int x = groundX + (int) Math.round((16 + i * 15) * dpiScale);
            int y = groundY - spriteH + (int) Math.round((26 - i * 20) * dpiScale);
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, size));
            g2.drawString("z", x, y);
        }
        g2.dispose();
    }

    private void drawMenu(Graphics2D g) {
        if (menuProgress <= 0.02) {
            return;
        }
        for (int i = 0; i < MENU_ANGLES.length; i++) {
            Point c = buttonCenter(i);
            double d = buttonDiameter * (0.55 + 0.45 * menuProgress);
            boolean hover = i == hoverIndex;
            double drawD = hover ? d * 1.08 : d;
            float alpha = (float) Math.max(0.0, Math.min(1.0, menuProgress));
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.setColor(new Color(30, 40, 70, 26));
            g2.fill(new Ellipse2D.Double(c.x - drawD / 2 - 1, c.y - drawD / 2, drawD + 2, drawD + 3));
            g2.setColor(new Color(30, 40, 70, 52));
            g2.fill(new Ellipse2D.Double(c.x - drawD / 2 + 1, c.y - drawD / 2 + 3, drawD, drawD));
            g2.setColor(new Color(0xF7FAFF));
            g2.fill(new Ellipse2D.Double(c.x - drawD / 2, c.y - drawD / 2, drawD, drawD));
            double iconSize = drawD * 0.56;
            BUTTON_ICONS[i].render(g2, c.x - iconSize / 2, c.y - iconSize / 2, iconSize / 16.0);
            g2.dispose();
        }
    }

    private void drawBubble(Graphics2D g) {
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, fontSize));
        FontMetrics fm = g.getFontMetrics();
        int padX = (int) Math.round(12 * dpiScale);
        int w = fm.stringWidth(bubble) + padX * 2;
        int h = fm.getHeight() + (int) Math.round(8 * dpiScale);
        int x = Math.min(Math.max(4, groundX - w / 2), windowW - w - 4);
        int y = Math.max(4, chestY - menuRadius - h - 6);
        g.setColor(new Color(0x23263A));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setColor(new Color(0xF7F9FF));
        g.drawString(bubble, x + padX, y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    // ------------------------------------------------------------- 交互

    private void installMouseHandlers(PetPanel panel) {
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    return;
                }
                pressScreen = e.getLocationOnScreen();
                pressWindow = getLocation();
                dragging = false;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    return;
                }
                if (dragging) {
                    dragging = false;
                    pressScreen = null;
                    setCursor(Cursor.getDefaultCursor());
                    posX = getX();
                    Log.info("拖到 (" + getX() + "," + getY() + ")");
                    return;
                }
                pressScreen = null;
                handleClick(e.getPoint());
            }
        });

        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null) {
                    return;
                }
                Point p = e.getLocationOnScreen();
                int dx = p.x - pressScreen.x;
                int dy = p.y - pressScreen.y;
                if (!dragging && Math.abs(dx) + Math.abs(dy) > 5) {
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                if (dragging) {
                    setLocation(pressWindow.x + dx, pressWindow.y + dy);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = buttonAt(e.getPoint());
                boolean body = bodyAt(e.getPoint());
                if (idx != hoverIndex || body != hoverBody) {
                    hoverIndex = idx;
                    hoverBody = body;
                    repaint();
                }
                // 盯着她看的时候脚步放慢、提灯甩得更欢
                speedFactor = body ? config.d("hover.speed.factor", 0.3) : 1.0;
                setCursor(idx >= 0 || body
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
    }

    private void handleClick(Point p) {
        if (menuProgress > 0.75) {
            int idx = buttonAt(p);
            if (idx >= 0) {
                Log.info("按钮点击: " + BUTTON_NAMES[idx] + "（待后续步骤实现）");
                showBubble(BUTTON_NAMES[idx] + " · 待后续步骤实现");
                openMenu(false);
                return;
            }
        }
        if (!bodyAt(p)) {
            return;
        }
        if (state == State.MENU) {
            openMenu(false);
        } else if (state == State.REST || restAmount > 0.1) {
            wakeUp(true);
            pendingMenu = true;
        } else {
            pendulum.kick(1.8);
            openMenu(true);
        }
    }

    private void showBubble(String text) {
        bubble = text;
        bubbleUntil = now + 2400;
        repaint();
    }

    public void selfTestClick() {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            int tx = getX() + groundX;
            int ty = getY() + groundY - spriteH / 2;
            Log.info("自检：点击角色 (" + tx + "," + ty + ")");
            robot.mouseMove(tx, ty);
            robot.delay(200);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        } catch (Exception e) {
            Log.error("自检点击失败", e);
        }
    }

    private void installPopupMenu(javax.swing.JComponent panel) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem about = new javax.swing.JMenuItem("关于桌宠");
        about.addActionListener(e -> showBubble("桌宠 v0.3 · 会走路的版本"));
        javax.swing.JMenuItem rest = new javax.swing.JMenuItem("让她休息");
        rest.addActionListener(e -> enterRest());
        javax.swing.JMenuItem quit = new javax.swing.JMenuItem("退出");
        quit.addActionListener(e -> exit());
        menu.add(about);
        menu.add(rest);
        menu.addSeparator();
        menu.add(quit);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(panel, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(panel, e.getX(), e.getY());
                }
            }
        });
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            Image icon = rig.body().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
            PopupMenu popup = new PopupMenu();
            MenuItem showItem = new MenuItem("显示 / 隐藏");
            showItem.addActionListener(e -> setVisible(!isVisible()));
            MenuItem restItem = new MenuItem("让她休息");
            restItem.addActionListener(e -> enterRest());
            MenuItem quitItem = new MenuItem("退出");
            quitItem.addActionListener(e -> exit());
            popup.add(showItem);
            popup.add(restItem);
            popup.addSeparator();
            popup.add(quitItem);
            TrayIcon trayIcon = new TrayIcon(icon, "桌宠", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> setVisible(true));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            Log.error("托盘图标创建失败", e);
        }
    }

    private void exit() {
        Log.info("退出桌宠");
        dispose();
        System.exit(0);
    }
}
