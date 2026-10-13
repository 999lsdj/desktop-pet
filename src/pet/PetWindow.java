package pet;

import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
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
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 桌宠主窗口：透明置顶、按角色轮廓裁剪、点击角色展开三角形菜单。
 *
 * 第一步只实现交互外壳：窗口显示、拖动、点击展开三角形菜单、按钮占位反馈、右键退出。
 * 渲染统一在设备像素空间进行（启动时加 -Dsun.java2d.uiScale=1），
 * 这样精灵图按整数倍最近邻放大，125% 缩放下也不会被插值糊掉。
 */
public final class PetWindow extends JWindow {

    /** 三角形菜单外接圆半径（逻辑像素，会按系统 DPI 换算成设备像素） */
    static final int MENU_RADIUS_LOGICAL = 110;
    /** 单个按钮直径（逻辑像素） */
    static final int BUTTON_DIAMETER_LOGICAL = 56;
    /** 精灵放大倍数（设备像素 / 精灵像素） */
    static final int SPRITE_SCALE = 2;
    /** 展开/收起动画时长（毫秒） */
    static final int ANIM_MS = 150;
    /** 画布留白，保证三角形菜单不被窗口裁掉 */
    static final int MARGIN = 16;

    private static final String[] BUTTON_NAMES = {"计划表", "常用文件", "视频网站"};
    private static final PixelIcon[] BUTTON_ICONS =
            {PixelIcon.PLAN, PixelIcon.FILES, PixelIcon.VIDEO};
    /** 三个按钮在三角形上的角度（度，0 度向右，顺时针为正）；三点间隔 120 度 */
    private static final double[] BUTTON_ANGLES = {-150, -30, 90};

    private final double dpiScale;
    private final int menuRadius;
    private final int buttonDiameter;
    private final int fontSize;

    private final Sprite sprite;
    private final int spriteX;
    private final int spriteY;
    private final int centerX;
    private final int centerY;
    private final Shape characterShape;

    private boolean menuOpen;
    private double progress;
    private long animStart;
    private boolean animating;
    private int hoverIndex = -1;
    private String bubble;
    private long bubbleUntil;

    private Point pressScreen;
    private Point pressWindow;
    private boolean dragging;

    public PetWindow(boolean demoMode) throws Exception {
        super();

        // 设备像素空间：屏幕分辨率就是真实分辨率，1 单位 = 1 物理像素
        dpiScale = Math.max(1.0, Toolkit.getDefaultToolkit().getScreenResolution() / 96.0);
        menuRadius = (int) Math.round(MENU_RADIUS_LOGICAL * dpiScale);
        buttonDiameter = (int) Math.round(BUTTON_DIAMETER_LOGICAL * dpiScale);
        fontSize = (int) Math.round(13 * dpiScale);

        Path spritePath = Paths.get("assets", "sprites", "pet-base-132x154.png").toAbsolutePath();
        sprite = Sprite.load(spritePath, SPRITE_SCALE);
        Log.info("精灵图加载完成: " + spritePath + " -> " + sprite.width() + "x" + sprite.height());

        int menuExtent = 2 * (menuRadius + buttonDiameter / 2 + 10);
        int width = Math.max(sprite.width(), menuExtent) + MARGIN * 2;
        int height = Math.max(sprite.height(), menuExtent) + MARGIN * 2;

        spriteX = (width - sprite.width()) / 2;
        spriteY = (height - sprite.height()) / 2;
        centerX = spriteX + sprite.width() / 2;
        // 菜单圆心略高于身体中心：两个按钮在头部两侧，一个落在脚下，三角形读起来更自然
        centerY = spriteY + (int) Math.round(sprite.height() * 0.36);
        characterShape = sprite.silhouette(spriteX, spriteY, 24);

        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(new Dimension(width, height));
        placeInitially(width, height);

        if (!GraphicsEnvironment.isHeadless()) {
            try {
                setShape(characterShape);
            } catch (Exception e) {
                Log.error("窗口裁剪失败（不影响显示）", e);
            }
        }

        PetPanel panel = new PetPanel();
        panel.setOpaque(false);
        setContentPane(panel);

        installMouseHandlers(panel);
        installPopupMenu(panel);
        installTrayIcon();

        Timer timer = new Timer(16, e -> onTick());
        timer.start();

        Log.info("窗口初始化完成: " + width + "x" + height
                + " | DPI缩放=" + dpiScale
                + " | 菜单半径=" + menuRadius + " 按钮直径=" + buttonDiameter
                + " | 菜单圆心=(" + centerX + "," + centerY + ")");
    }

    public void open() {
        setVisible(true);
        Log.info("桌宠已显示");
    }

    /**
     * 自检：用真实鼠标事件点击角色本体，验证"点击展开三角形菜单"这条路是通的
     * （而不是只验证 demo 模式直接调用的 toggleMenu）。
     */
    public void selfTestClick() {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            int tx = getX() + centerX;
            int ty = getY() + centerY + (int) Math.round(60 * dpiScale);
            Log.info("自检：把鼠标移到 (" + tx + "," + ty + ") 并点击角色");
            robot.mouseMove(tx, ty);
            robot.delay(200);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Log.info("自检：点击完成，当前菜单状态=" + (menuOpen ? "展开" : "收起"));
        } catch (Exception e) {
            Log.error("自检点击失败", e);
        }
    }

    private void placeInitially(int width, int height) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = screen.width - width - 60;
        int y = screen.height - height - 100;
        x = Math.max(0, Math.min(x, screen.width - width));
        y = Math.max(0, Math.min(y, screen.height - height));
        setLocation(x, y);
        Log.info("初始位置: (" + getX() + "," + getY() + ") | 屏幕=" + screen.width + "x" + screen.height);
    }

    // ---------------------------------------------------------------- 交互

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
                    Log.info("窗口移动到 (" + getX() + "," + getY() + ")");
                    dragging = false;
                    pressScreen = null;
                    setCursor(Cursor.getDefaultCursor());
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
                Point now = e.getLocationOnScreen();
                int dx = now.x - pressScreen.x;
                int dy = now.y - pressScreen.y;
                if (!dragging && Math.abs(dx) + Math.abs(dy) > 4) {
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
                if (idx != hoverIndex) {
                    hoverIndex = idx;
                    repaint();
                }
                setCursor(idx >= 0
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
    }

    private void handleClick(Point p) {
        if (progress > 0.75) {
            int idx = buttonAt(p);
            if (idx >= 0) {
                onButton(idx);
                return;
            }
        }
        if (characterShape.contains(p)) {
            toggleMenu(!menuOpen);
            return;
        }
        if (menuOpen) {
            toggleMenu(false);
        }
    }

    private void onButton(int index) {
        Log.info("按钮点击: " + BUTTON_NAMES[index] + "（此功能待后续步骤实现）");
        showBubble(BUTTON_NAMES[index] + " · 待后续步骤实现");
        toggleMenu(false);
    }

    void toggleMenu(boolean open) {
        if (menuOpen == open) {
            return;
        }
        menuOpen = open;
        animStart = System.currentTimeMillis();
        animating = true;
        Log.info(open ? "三角形菜单展开" : "三角形菜单收起");
    }

    // ---------------------------------------------------------------- 绘制

    private void onTick() {
        long now = System.currentTimeMillis();
        boolean needRepaint = false;

        if (animating) {
            double t = Math.min(1.0, (now - animStart) / (double) ANIM_MS);
            progress = menuOpen ? ease(t) : 1.0 - ease(t);
            applyShape();
            needRepaint = true;
            if (t >= 1.0) {
                animating = false;
                progress = menuOpen ? 1.0 : 0.0;
            }
        }

        if (bubble != null && now > bubbleUntil) {
            bubble = null;
            needRepaint = true;
        }
        if (needRepaint) {
            repaint();
        }
    }

    private static double ease(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    private void applyShape() {
        Area area = new Area(characterShape);
        for (int i = 0; i < BUTTON_ANGLES.length; i++) {
            if (progress <= 0.02) {
                continue;
            }
            Point c = buttonCenter(i);
            double d = buttonDiameter() * (0.55 + 0.45 * progress);
            area.add(new Area(new Ellipse2D.Double(c.x - d / 2, c.y - d / 2, d, d)));
        }
        try {
            setShape(area);
        } catch (Exception e) {
            Log.error("设置窗口形状失败", e);
        }
    }

    private double buttonDiameter() {
        return buttonDiameter * (0.55 + 0.45 * progress);
    }

    private Point buttonCenter(int index) {
        double rad = Math.toRadians(BUTTON_ANGLES[index]);
        double r = menuRadius * progress;
        return new Point(
                (int) Math.round(centerX + Math.cos(rad) * r),
                (int) Math.round(centerY + Math.sin(rad) * r));
    }

    private int buttonAt(Point p) {
        if (progress < 0.75) {
            return -1;
        }
        double d = buttonDiameter();
        for (int i = 0; i < BUTTON_ANGLES.length; i++) {
            Point c = buttonCenter(i);
            if (Math.hypot(p.x - c.x, p.y - c.y) <= d / 2) {
                return i;
            }
        }
        return -1;
    }

    private void showBubble(String text) {
        bubble = text;
        bubbleUntil = System.currentTimeMillis() + 2200;
        repaint();
    }

    private final class PetPanel extends javax.swing.JPanel {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.drawImage(sprite.image(), spriteX, spriteY, null);

            // 按钮画在角色之上，否则底部那个会被身体挡住
            if (progress > 0.02) {
                for (int i = 0; i < BUTTON_ANGLES.length; i++) {
                    drawButton(g, i);
                }
            }

            if (hoverIndex >= 0 && progress > 0.9) {
                drawHoverLabel(g, hoverIndex);
            }
            if (bubble != null) {
                drawBubble(g);
            }
            g.dispose();
        }
    }

    private void drawButton(Graphics2D g, int index) {
        Point c = buttonCenter(index);
        double d = buttonDiameter();
        float alpha = (float) Math.max(0.0, Math.min(1.0, progress));
        boolean hover = index == hoverIndex;
        double drawD = hover ? d * 1.08 : d;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        g2.setColor(new Color(0, 0, 0, 60));
        g2.fill(new Ellipse2D.Double(c.x - drawD / 2 + 2, c.y - drawD / 2 + 3, drawD, drawD));
        g2.setColor(new Color(0xF4F7FF));
        g2.fill(new Ellipse2D.Double(c.x - drawD / 2, c.y - drawD / 2, drawD, drawD));
        g2.setColor(new Color(0x23263A));
        g2.setStroke(new BasicStroke((float) Math.max(2.0, dpiScale * 2)));
        g2.draw(new Ellipse2D.Double(c.x - drawD / 2, c.y - drawD / 2, drawD, drawD));

        double iconSize = drawD * 0.56;
        double cell = iconSize / 16.0;
        BUTTON_ICONS[index].render(g2, c.x - iconSize / 2, c.y - iconSize / 2, cell);
        g2.dispose();
    }

    private void drawHoverLabel(Graphics2D g, int index) {
        Point c = buttonCenter(index);
        String text = BUTTON_NAMES[index];
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, fontSize));
        FontMetrics fm = g.getFontMetrics();
        int padX = (int) Math.round(10 * dpiScale);
        int w = fm.stringWidth(text) + padX * 2;
        int h = fm.getHeight() + (int) Math.round(6 * dpiScale);
        double d = buttonDiameter();

        double rad = Math.toRadians(BUTTON_ANGLES[index]);
        int lx = (int) Math.round(c.x + Math.cos(rad) * (d / 2 + 10 * dpiScale) - w / 2);
        int ly = (int) Math.round(c.y + Math.sin(rad) * (d / 2 + 10 * dpiScale) - h / 2);

        g.setColor(new Color(0x23263A));
        g.fill(new RoundRectangle2D.Double(lx, ly, w, h, 10, 10));
        g.setColor(new Color(0xF7F9FF));
        g.drawString(text, lx + padX, ly + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void drawBubble(Graphics2D g) {
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, fontSize));
        FontMetrics fm = g.getFontMetrics();
        int padX = (int) Math.round(12 * dpiScale);
        int w = fm.stringWidth(bubble) + padX * 2;
        int h = fm.getHeight() + (int) Math.round(8 * dpiScale);
        int x = centerX - w / 2;
        int y = spriteY - h - (int) Math.round(10 * dpiScale);
        if (y < 4) {
            y = 4;
        }
        g.setColor(new Color(0x23263A));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setColor(new Color(0xF7F9FF));
        g.drawString(bubble, x + padX, y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void installPopupMenu(javax.swing.JComponent panel) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem about = new javax.swing.JMenuItem("关于桌宠");
        about.addActionListener(e -> {
            Log.info("菜单：关于");
            showBubble("桌宠 v0.1 · 第一步：三角形菜单");
        });
        javax.swing.JMenuItem quit = new javax.swing.JMenuItem("退出");
        quit.addActionListener(e -> exit());
        menu.add(about);
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

    /** 托盘图标：窗口没有任务栏条目，托盘是备用出口 */
    private void installTrayIcon() {
        if (!SystemTray.isSupported()) {
            Log.info("系统不支持托盘图标，跳过");
            return;
        }
        try {
            Image icon = sprite.image().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
            PopupMenu popup = new PopupMenu();

            MenuItem showItem = new MenuItem("显示 / 隐藏");
            showItem.addActionListener(e -> {
                boolean visible = isVisible();
                setVisible(!visible);
                Log.info("托盘：切换显示 -> " + !visible);
            });
            MenuItem quitItem = new MenuItem("退出");
            quitItem.addActionListener(e -> exit());
            popup.add(showItem);
            popup.addSeparator();
            popup.add(quitItem);

            TrayIcon trayIcon = new TrayIcon(icon, "桌宠", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                setVisible(true);
                Log.info("托盘：显示桌宠");
            });
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
