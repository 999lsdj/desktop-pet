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
import java.awt.GraphicsEnvironment;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * 桌宠主窗口：透明置顶、沿屏幕底边踱步、微醺摇晃、提灯单摆、躺下休息、点击摊手开菜单。
 *
 * 绘制统一在设备像素空间进行（启动加 -Dsun.java2d.uiScale=1），
 * 精灵按整数倍最近邻放大，因此像素边缘不会被插值糊掉。
 */
public final class PetWindow extends JWindow {

    private enum State { WALK, PAUSE, REST, MENU, HIDDEN }

    /** 设备像素 / 原生像素 */
    static final int SPRITE_SCALE = 2;
    /** 脚下留白，给影子用 */
    private static final int GROUND_PAD = 12;
    /**
     * 休息时身体倾斜的角度。
     * 整身硬转到水平会被大衣、长发和长杖拖成一团乱麻（试过 72 度，观感很差），
     * 所以改成"靠着打盹"：小幅后仰 + 放缓呼吸 + 头顶冒 Z 字。
     * 想要真正的躺姿，需要一张躺下的立绘。
     */
    private static final double LIE_ANGLE = 22;
    /** 菜单三个按钮在圆周上的角度（以胸口为圆心，向斜上方展开成扇形） */
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
    private final double walkSpeed;          // 设备像素/秒
    private final Shape idleShape;
    private final Shape restShape;

    // 运动状态
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
    private double lieAmount;
    private boolean pendingMenu;
    private double menuProgress;
    private long menuAnimStart;
    private boolean menuAnimating;
    private int hoverIndex = -1;
    private boolean hoverBody;
    private double speedFactor = 1.0;
    private String bubble;
    private long bubbleUntil;

    // 单摆驱动用的握手位置历史
    private double gripPrevX;
    private double gripVel;

    private Point pressScreen;
    private Point pressWindow;
    private boolean dragging;

    public PetWindow(Config config, boolean demoMode, boolean selfTest, boolean restTest,
                     boolean ignoreFullscreen) throws Exception {
        this(config, demoMode, selfTest, restTest, ignoreFullscreen, false);
    }

    public PetWindow(Config config, boolean demoMode, boolean selfTest, boolean restTest,
                     boolean ignoreFullscreen, boolean still) throws Exception {
        super();
        this.config = config;
        this.still = still;

        dpiScale = Math.max(1.0, Toolkit.getDefaultToolkit().getScreenResolution() / 96.0);
        menuRadius = (int) Math.round(config.d("menu.radius.logical", 105) * dpiScale);
        buttonDiameter = (int) Math.round(config.d("button.diameter.logical", 56) * dpiScale);
        fontSize = (int) Math.round(13 * dpiScale);
        walkSpeed = config.d("walk.speed.logical", 30) * dpiScale;

        Path spritePath = Paths.get("assets", "sprites", "pet-base.png").toAbsolutePath();
        rig = BodyRig.load(spritePath);
        spriteW = rig.width() * SPRITE_SCALE;
        spriteH = rig.height() * SPRITE_SCALE;

        int headroom = menuRadius + buttonDiameter / 2 + (int) Math.round(14 * dpiScale);
        windowH = headroom + spriteH + GROUND_PAD;
        // 躺下时身体从脚下向右侧伸出（头朝右），窗口右侧必须留出这段长度，否则会被裁掉。
        double lie = Math.toRadians(LIE_ANGLE);
        int rightRoom = (int) Math.ceil(spriteH * Math.sin(lie) + spriteW / 2.0 * Math.cos(lie))
                + (int) Math.round(20 * dpiScale);
        int leftRoom = (int) Math.ceil(Math.max(spriteW / 2.0,
                menuRadius * Math.cos(Math.toRadians(20)) + buttonDiameter / 2.0))
                + (int) Math.round(20 * dpiScale);
        groundX = leftRoom;
        windowW = groundX + rightRoom;
        groundY = headroom + spriteH;
        chestY = groundY - (int) Math.round(spriteH * 0.58);
        groundLine = Toolkit.getDefaultToolkit().getScreenSize().height;

        idleShape = buildIdleShape();
        // 躺下时的可点击范围：从脚下往右铺开
        restShape = new Rectangle2D.Double(groundX - spriteW * 0.35, groundY - spriteH * 0.45,
                spriteH * 1.25, spriteH * 0.5);

        pendulum = new Pendulum(
                config.d("lantern.max.deg", 35),
                config.d("lantern.length.device", 90),
                config.d("lantern.damping", 1.1));

        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(new Dimension(windowW, windowH));
        placeInitially();
        try {
            setShape(idleShape);
        } catch (Exception e) {
            Log.error("窗口裁剪失败（不影响显示）", e);
        }

        PetPanel panel = new PetPanel();
        panel.setOpaque(false);
        setContentPane(panel);
        installMouseHandlers(panel);
        installPopupMenu(panel);
        installTrayIcon();

        watcher = ignoreFullscreen
                ? FullscreenWatcher.disabled()
                : new FullscreenWatcher(Paths.get("tools", "fullscreen-watch.ps1").toAbsolutePath(),
                        "powershell.exe");

        long t = System.currentTimeMillis();
        now = t;
        stateUntil = t + 1200;
        nextWalkEnd = t + 8000;
        nextRestAt = t + (long) (config.d("rest.interval.min.minutes", 2) * 60000 * (0.6 + random.nextDouble() * 0.4));
        nextStumbleAt = t + 15000;

        new Timer(16, e -> onTick()).start();

        Log.info("窗口初始化: " + windowW + "x" + windowH + " | DPI=" + dpiScale
                + " | 精灵=" + rig.width() + "x" + rig.height() + " 显示=" + spriteW + "x" + spriteH
                + " | 地面=(" + groundX + "," + groundY + ") 胸口Y=" + chestY
                + " | 菜单半径=" + menuRadius + " 按钮=" + buttonDiameter
                + " | 走路速度=" + String.format("%.1f", walkSpeed) + " 设备像素/秒");

        if (demoMode) {
            javax.swing.Timer t2 = new javax.swing.Timer(1500, e -> openMenu(true));
            t2.setRepeats(false);
            t2.start();
        }
        if (selfTest) {
            javax.swing.Timer t2 = new javax.swing.Timer(2000, e -> selfTestClick());
            t2.setRepeats(false);
            t2.start();
        }
        if (restTest) {
            javax.swing.Timer t2 = new javax.swing.Timer(1200, e -> enterRest());
            t2.setRepeats(false);
            t2.start();
        }
    }

    public void open() {
        setVisible(true);
        Log.info("桌宠已显示");
    }

    private void placeInitially() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        posX = screen.width - windowW - 80;
        groundLine = screen.height;
        setLocation((int) Math.round(posX), (int) Math.round(groundLine) - groundY);
        Log.info("初始位置: x=" + getX() + " y=" + getY() + " | 屏幕=" + screen.width + "x" + screen.height);
    }

    // ------------------------------------------------------------- 状态机

    private void onTick() {
        long previous = now;
        now = System.currentTimeMillis();
        double dt = Math.min(0.05, Math.max(0.001, (now - previous) / 1000.0));

        boolean fullscreen = watcher.isFullscreen();
        if (fullscreen && state != State.HIDDEN) {
            state = State.HIDDEN;
            setOpacity(0.15f);
        } else if (!fullscreen && state == State.HIDDEN) {
            setOpacity(1.0f);
            state = State.PAUSE;
            stateUntil = now + 1000;
        }

        if (state != State.HIDDEN) {
            updateState(dt);
            updateMotion(dt);
        } else {
            repaint();
        }
        if (bubble != null && now > bubbleUntil) {
            bubble = null;
        }
    }

    private void updateState(double dt) {
        updateGround(dt);
        switch (state) {
            case WALK -> {
                posX += dir * walkSpeed * speedFactor * dt;
                walkPhase += dt * 7.0 * speedFactor;
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                if (posX < 0) {
                    posX = 0;
                    turn();
                } else if (posX > screen.width - windowW) {
                    posX = screen.width - windowW;
                    turn();
                }
                setLocation((int) Math.round(posX), getY());
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
                lieAmount = Math.min(1.0, lieAmount + dt / 0.9);
                pendulum.setDamping(config.d("lantern.damping", 1.1) * 3.2);
                if (now > stateUntil && lieAmount > 0.98) {
                    wakeUp(false);
                }
            }
            case MENU -> {
                lieAmount = Math.max(0.0, lieAmount - dt / 0.9);
            }
            default -> {
            }
        }

        if (pendingMenu && lieAmount < 0.02) {
            pendingMenu = false;
            openMenu(true);
        }

        if ((state == State.WALK || state == State.PAUSE) && now > nextRestAt) {
            enterRest();
        }
        if (now > nextStumbleAt && state != State.REST) {
            stumbleUntil = now + 500;
            nextStumbleAt = now + randSeconds("stumble.interval.min.sec", 20,
                    "stumble.interval.max.sec", 40) * 1000L;
            pendulum.kick(1.6 * (random.nextBoolean() ? 1 : -1));
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
    }

    /** 让地面线平滑跟随任务栏：任务栏弹出时她抬脚站上去，收起时再落回屏幕底边。 */
    private void updateGround(double dt) {
        int target = watcher.groundY();
        if (target < 0) {
            return;
        }
        if (Math.abs(groundLine - target) < 0.5) {
            groundLine = target;
        } else {
            groundLine += (target - groundLine) * Math.min(1.0, dt * 6);
        }
        setLocation(getX(), (int) Math.round(groundLine) - groundY);
    }

    private void updateMotion(double dt) {
        double swayTilt = config.d("sway.tilt.deg", 8)
                * Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5))
                * (1 - 0.7 * lieAmount);
        double stumble = 0;
        if (now < stumbleUntil) {
            stumble = config.d("stumble.tilt.deg", 15) * Math.sin(Math.PI * (1 - (stumbleUntil - now) / 500.0));
        }
        double bodyAngle = swayTilt + stumble + lieAmount * LIE_ANGLE;

        double bob = Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5) * 2)
                * config.d("bob.device.px", 4) * (0.6 + 0.4 * (state == State.WALK ? 1 : 0));

        // 握点的世界坐标 → 用它算出水平加速度去驱动单摆
        double gripLocalX = (rig.grip().x - rig.width() / 2.0) * SPRITE_SCALE;
        double gripLocalY = (rig.grip().y - rig.height()) * SPRITE_SCALE;
        double rad = Math.toRadians(bodyAngle);
        double gripWorldX = getX() + groundX + gripLocalX * Math.cos(rad) - gripLocalY * Math.sin(rad);
        double vel = (gripWorldX - gripPrevX) / dt;
        double accel = (vel - gripVel) / dt;
        gripPrevX = gripWorldX;
        gripVel = vel;
        if (state == State.MENU || state == State.REST) {
            pendulum.setDamping(config.d("lantern.damping", 1.1) * (state == State.REST ? 3.2 : 1.6));
        } else {
            pendulum.setDamping(config.d("lantern.damping", 1.1));
        }
        pendulum.update(dt, accel, 900);

        repaint();
    }

    private void turn() {
        dir = -dir;
        mirrored = !mirrored;
        pendulum.kick(1.2 * dir);
    }

    private void enterWalk() {
        if (still) {
            state = State.PAUSE;
            stateUntil = now + 600000;
            return;
        }
        state = State.WALK;
        walkPhase = 0;
        nextWalkEnd = now + randSeconds("walk.stretch.min.sec", 8, "walk.stretch.max.sec", 20) * 1000L;
        pendulum.kick(1.4);
        Log.info("开始踱步（" + (dir > 0 ? "向右" : "向左") + "）");
    }

    private void enterPause() {
        state = State.PAUSE;
        stateUntil = now + randSeconds("walk.pause.min.sec", 3, "walk.pause.max.sec", 10) * 1000L;
        pendulum.kick(-1.0);
        Log.info("停下歇一会");
    }

    private void enterRest() {
        if (state == State.REST) {
            return;
        }
        state = State.REST;
        stateUntil = now + randSeconds("rest.duration.min.sec", 20, "rest.duration.max.sec", 60) * 1000L;
        nextRestAt = now + randSeconds("rest.interval.min.minutes", 2, "rest.interval.max.minutes", 5) * 60000L;
        pendulum.kick(-1.5);
        Log.info("躺下休息");
    }

    private void wakeUp(boolean immediate) {
        state = State.PAUSE;
        stateUntil = now + (immediate ? 200 : 800);
        pendulum.setDamping(config.d("lantern.damping", 1.1));
        Log.info("起身");
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
            Log.info("收起任务入口，继续踱步");
            state = State.PAUSE;
            stateUntil = now + 1500;
        }
        applyShape();
    }

    private long randSeconds(String minKey, double minDefault, String maxKey, double maxDefault) {
        double lo = config.d(minKey, minDefault);
        double hi = config.d(maxKey, maxDefault);
        if (hi < lo) {
            hi = lo;
        }
        return Math.round(lo + random.nextDouble() * (hi - lo));
    }

    private static double ease(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    // ------------------------------------------------------------- 形状

    private Shape buildIdleShape() {
        Area area = new Area();
        java.awt.image.BufferedImage full = rig.original();
        int w = full.getWidth();
        int h = full.getHeight();
        int halo = (int) Math.round(4 * dpiScale);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((full.getRGB(x, y) >>> 24) > 8) {
                    area.add(new Area(new Rectangle2D.Double(
                            groundX - spriteW / 2.0 + x * SPRITE_SCALE - halo,
                            groundY - spriteH + y * SPRITE_SCALE - halo,
                            SPRITE_SCALE + halo * 2, SPRITE_SCALE + halo * 2)));
                }
            }
        }
        return area;
    }

    private void applyShape() {
        Area area = new Area(lieAmount > 0.15 ? restShape : idleShape);
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

    /** 点击命中角色本体（用宽松包围盒，躺下时换一条横着的） */
    private boolean bodyAt(Point p) {
        if (lieAmount > 0.5) {
            return p.x > groundX - spriteW * 0.4 && p.x < groundX + spriteH * 1.1
                    && p.y > groundY - spriteH * 0.45 && p.y < groundY + 6;
        }
        return Math.abs(p.x - groundX) < spriteW * 0.42
                && p.y > groundY - spriteH + 6 && p.y < groundY + 6;
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
                    Log.info("拖到 (" + getX() + "," + getY() + ")");
                    posX = getX();
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
        } else if (state == State.REST || lieAmount > 0.1) {
            wakeUp(true);
            pendingMenu = true;
            Log.info("被点醒，准备起身展示入口");
        } else {
            pendulum.kick(2.0);
            openMenu(true);
        }
    }

    private void showBubble(String text) {
        bubble = text;
        bubbleUntil = now + 2400;
        repaint();
    }

    /** 自检：用真实鼠标事件点一下角色，验证点击链路。 */
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
            drawPet(g);
            drawMenu(g);
            if (bubble != null) {
                drawBubble(g);
            }
            g.dispose();
        }
    }

    private void drawPet(Graphics2D g) {
        double swayTilt = config.d("sway.tilt.deg", 8)
                * Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5))
                * (1 - 0.7 * lieAmount);
        double stumble = 0;
        if (now < stumbleUntil) {
            stumble = config.d("stumble.tilt.deg", 15) * Math.sin(Math.PI * (1 - (stumbleUntil - now) / 500.0));
        }
        double bodyAngle = swayTilt + stumble + lieAmount * LIE_ANGLE;
        double bob = Math.sin(2 * Math.PI * now / 1000.0 / config.d("sway.period.sec", 1.5) * 2)
                * config.d("bob.device.px", 4) * (state == State.WALK ? 1.0 : 0.6);

        // 影子：贴地，随身体倾斜拉长
        double shadowW = spriteW * (0.75 + 0.55 * lieAmount);
        double shadowH = 14 * dpiScale;
        g.setColor(new Color(20, 25, 45, 62));
        g.fill(new Ellipse2D.Double(groundX - shadowW / 2, groundY - shadowH / 2 + 2, shadowW, shadowH));

        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(groundX, groundY + bob);
        // 躺下时固定头朝左侧，这样只需要在窗口左侧留出空间
        if (mirrored && lieAmount < 0.5) {
            g2.scale(-1, 1);
        }
        g2.rotate(Math.toRadians(bodyAngle));

        drawLegs(g2);
        drawBody(g2);
        drawStaff(g2);
        g2.dispose();

        if (lieAmount > 0.25) {
            drawSleepMarks(g);
        }
    }

    /** 休息时头顶冒的 Z 字，随呼吸节奏变大变小。 */
    private void drawSleepMarks(Graphics2D g) {
        double phase = (now % 2400) / 2400.0;
        double alpha = lieAmount * Math.min(1.0, Math.sin(phase * Math.PI) * 1.6);
        if (alpha <= 0.02) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
        g2.setColor(new Color(0x2E3A5C));
        for (int i = 0; i < 3; i++) {
            int size = (int) Math.round((11 + i * 4) * dpiScale);
            int x = groundX + (int) Math.round((18 + i * 16) * dpiScale);
            int y = groundY - spriteH + (int) Math.round((10 - i * 22) * dpiScale + (now % 2400) * 0.006);
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, size));
            g2.drawString("z", x, y);
        }
        g2.dispose();
    }

    private void drawBody(Graphics2D g) {
        int x = -spriteW / 2;
        int y = -spriteH;
        scaleUp(g, rig.body(), x, y);
    }

    private void drawStaff(Graphics2D g) {
        Point origin = rig.staffOrigin();
        double localX = (origin.x - rig.width() / 2.0) * SPRITE_SCALE;
        double localY = (origin.y - rig.height()) * SPRITE_SCALE;
        double pivotX = (rig.grip().x - rig.width() / 2.0) * SPRITE_SCALE;
        double pivotY = (rig.grip().y - rig.height()) * SPRITE_SCALE;
        // 镜像绘制时局部旋转方向会反过来，这里把符号扳回去，让摆向和物理一致
        double angle = mirrored ? -pendulum.angle() : pendulum.angle();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.rotate(angle + menuProgress * Math.toRadians(-16), pivotX, pivotY);
        scaleUp(g2, rig.staff(), localX, localY);
        g2.dispose();
    }

    private void drawLegs(Graphics2D g) {
        double swing = state == State.WALK
                ? Math.toRadians(7) * Math.sin(walkPhase) * speedFactor
                : 0;
        for (int i = 0; i < 2; i++) {
            Point origin = rig.legOrigin(i);
            double localX = (origin.x - rig.width() / 2.0) * SPRITE_SCALE;
            double localY = (origin.y - rig.height()) * SPRITE_SCALE;
            double pivotX = (rig.hip(i).x - rig.width() / 2.0) * SPRITE_SCALE;
            double pivotY = (rig.hip(i).y - rig.height()) * SPRITE_SCALE;
            double angle = lieAmount > 0.1 ? 0 : (i == 0 ? swing : -swing);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.rotate(angle, pivotX, pivotY);
            scaleUp(g2, rig.leg(i), localX, localY);
            g2.dispose();
        }
    }

    private void scaleUp(Graphics2D g, java.awt.image.BufferedImage img, double x, double y) {
        g.drawImage(img, (int) Math.round(x), (int) Math.round(y),
                img.getWidth() * SPRITE_SCALE, img.getHeight() * SPRITE_SCALE, null);
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

    // ------------------------------------------------------------- 托盘 / 右键

    private void installPopupMenu(javax.swing.JComponent panel) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem about = new javax.swing.JMenuItem("关于桌宠");
        about.addActionListener(e -> showBubble("桌宠 v0.2 · 会走路的版本"));
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
            Log.info("托盘图标已就绪");
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
