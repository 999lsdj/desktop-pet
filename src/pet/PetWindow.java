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
import java.nio.file.Paths;
import java.util.Random;

/**
 * 桌宠主窗口：沿屏幕底边踱步、微醺摇晃、提灯单摆、放下挂灯靠着打盹、点击停下展开入口。
 *
 * 两个关键实现点：
 * 1) 绘制在设备像素空间进行（启动加 -Dsun.java2d.uiScale=1），像素图按整数倍放大不发虚。
 * 2) 裁剪与命中读取正式动画同一帧的 alpha 行段，并为骨架动态预留画布边距。
 */
public final class PetWindow extends JWindow {

    private enum State { WALK, PAUSE, REST, MENU, HIDDEN }

    static final int SPRITE_SCALE = 2;
    private static final int GROUND_PAD = PetAnimation.PADDING * SPRITE_SCALE;
    /** 累的时候向后靠的角度（负数＝朝提灯那一侧） */
    private static final double REST_LEAN = -13;
    private static final double[] MENU_ANGLES = {200, 270, 340};
    private static final String[] BUTTON_NAMES = {"计划表", "常用文件", "视频网站"};
    private static final PixelIcon[] BUTTON_ICONS =
            {PixelIcon.PLAN, PixelIcon.FILES, PixelIcon.VIDEO};

    private final Config config;
    private final boolean still;
    private final LayeredRig rig;
    private final PetAnimation animation;
    private final Random random = new Random();
    private final FullscreenWatcher watcher;

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
    private double restAmount;      // 0 站着，1 靠着睡
    private boolean pendingMenu;
    private double menuProgress;
    private long menuAnimStart;
    private boolean menuAnimating;
    private double menuFrom, menuTarget;
    private int hoverIndex = -1;
    private boolean hoverBody;
    private double speedFactor = 1.0;
    private String bubble;
    private long bubbleUntil;

    private double animationTravel;
    private long nextAnimationLog;

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

        rig = LayeredRig.load(Paths.get("assets", "rig", "q-chibi-refined").toAbsolutePath());
        animation = new PetAnimation(rig, config);
        spriteW = rig.width() * SPRITE_SCALE;
        spriteH = rig.height() * SPRITE_SCALE;

        int headroom = menuRadius + buttonDiameter / 2 + (int) Math.round(16 * dpiScale);
        windowH = headroom + spriteH + GROUND_PAD;
        // 窗口要同时容得下"站着的角色"和"立在身边的提灯"
        int leftRoom = (int) Math.ceil(Math.max(spriteW / 2.0,
                menuRadius * Math.cos(Math.toRadians(20)) + buttonDiameter / 2.0))
                + PetAnimation.PADDING * SPRITE_SCALE;
        int rightRoom = spriteW / 2 + PetAnimation.PADDING * SPRITE_SCALE;
        groundX = leftRoom;
        windowW = groundX + rightRoom;
        groundY = headroom + spriteH;
        chestY = groundY - (int) Math.round(spriteH * 0.58);

        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(new Dimension(windowW, windowH));
        placeInitially();
        try {
            setShape(animation.shape(groundX, groundY, SPRITE_SCALE, mirrored));
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
                if (!dragging) posX += dir * walkSpeed * speedFactor * dt;
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
                if (now > stateUntil && restAmount > 0.98) {
                    wakeUp(false);
                }
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
            Log.info("踉跄一下");
        }
        if (menuAnimating) {
            double t = Math.min(1.0, (now - menuAnimStart) / 180.0);
            menuProgress = menuFrom + (menuTarget - menuFrom) * ease(t);
            if (t >= 1.0) {
                menuProgress = menuTarget;
                menuAnimating = false;
            }
        }
        if (!dragging) setLocation((int) Math.round(posX), (int) Math.round(groundLine) - groundY);
        if (state != State.REST) {
            restAmount = Math.max(0, restAmount - dt / 0.8);
        }
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

    private void updateMotion(double dt) {
        double speed = state == State.WALK && !dragging ? walkSpeed * speedFactor / SPRITE_SCALE : 0;
        animationTravel += speed * dt;
        animation.update(dt, state == State.WALK && !dragging, speed, currentTilt(), restAmount,
                menuProgress, animationTravel);
        applyShape();
        if (now >= nextAnimationLog) {
            Log.info(String.format(java.util.Locale.ROOT,
                    "分层动画 state=%s phase=%.3f blend=%.3f eval=%.3fms raster=%.3fms",
                    state, animation.phase(), animation.walkBlend(), rig.lastUpdateMs(), rig.lastDrawMs()));
            nextAnimationLog = now + 5000;
        }
        repaint();
    }

    private void turn() {
        dir = -dir;
        mirrored = !mirrored;
    }

    private void enterWalk() {
        if (still) {
            state = State.PAUSE;
            stateUntil = now + 600000;
            return;
        }
        state = State.WALK;
        nextWalkEnd = now + randSeconds("walk.stretch.min.sec", 8, "walk.stretch.max.sec", 20) * 1000L;
        Log.info("开始踱步（" + (dir > 0 ? "向右" : "向左") + "）");
    }

    private void enterPause() {
        state = State.PAUSE;
        stateUntil = now + randSeconds("walk.pause.min.sec", 3, "walk.pause.max.sec", 10) * 1000L;
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
        menuFrom = menuProgress;
        menuTarget = open ? 1 : 0;
        if (open) {
            state = State.MENU;
            menuAnimStart = now;
            menuAnimating = true;
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

    private void applyShape() {
        Area area = animation.shape(groundX, groundY, SPRITE_SCALE, mirrored);
        if (menuProgress > 0.02) {
            for (int i = 0; i < MENU_ANGLES.length; i++) {
                Point c = buttonCenter(i);
                double d = buttonDiameter * (0.55 + 0.45 * menuProgress);
                area.add(new Area(new Ellipse2D.Double(c.x - d / 2, c.y - d / 2, d, d)));
            }
        }
        // 文本和阴影也属于可见窗口；不再依赖旧身体包络意外包含它们。
        double shadowW = spriteW * 0.78;
        area.add(new Area(new Ellipse2D.Double(groundX - shadowW / 2, groundY - 7 * dpiScale,
                shadowW, 14 * dpiScale)));
        if (restAmount > 0.3) area.add(new Area(new Rectangle2D.Double(
                groundX + 10 * dpiScale, groundY - spriteH - 40 * dpiScale, 66 * dpiScale, 75 * dpiScale)));
        if (bubble != null) area.add(new Area(bubbleBounds()));
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
        return animation.hit(p.x + 0.5, p.y + 0.5, groundX, groundY, SPRITE_SCALE, mirrored);
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

            // 影子
            double shadowW = spriteW * 0.78;
            g.setColor(new Color(20, 25, 45, 62));
            g.fill(new Ellipse2D.Double(groundX - shadowW / 2, groundY - 7 * dpiScale,
                    shadowW, 14 * dpiScale));

            animation.draw(g, groundX, groundY, SPRITE_SCALE, mirrored);

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

    private Rectangle2D bubbleBounds() {
        FontMetrics fm = getFontMetrics(new Font("Microsoft YaHei", Font.PLAIN, fontSize));
        int w = fm.stringWidth(bubble) + (int) Math.round(24 * dpiScale);
        int h = fm.getHeight() + (int) Math.round(8 * dpiScale);
        int x = Math.min(Math.max(4, groundX - w / 2), windowW - w - 4);
        int y = Math.max(4, chestY - menuRadius - h - 6);
        return new Rectangle2D.Double(x, y, w, h);
    }

    private void drawBubble(Graphics2D g) {
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, fontSize));
        FontMetrics fm = g.getFontMetrics();
        Rectangle2D r = bubbleBounds();
        g.setColor(new Color(0x23263A));
        g.fill(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 12, 12));
        g.setColor(new Color(0xF7F9FF));
        g.drawString(bubble, (int) r.getX() + (int) Math.round(12 * dpiScale),
                (int) (r.getY() + (r.getHeight() - fm.getHeight()) / 2 + fm.getAscent()));
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
            openMenu(true);
        }
    }

    private void showBubble(String text) {
        bubble = text;
        bubbleUntil = now + 2400;
        applyShape();
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
        about.addActionListener(e -> showBubble("桌宠 v0.4 · 分层骨骼动画"));
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
            Image icon = rig.reference().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
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
