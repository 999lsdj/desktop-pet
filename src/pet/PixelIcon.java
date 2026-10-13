package pet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/**
 * 用代码画的像素风图标：底图是 16x16 的格子，按格子填矩形，天然是块状像素感。
 * 三个图标分别对应计划表、常用文件、视频网站，配色与角色保持一致。
 */
public enum PixelIcon {

    /** 计划表：一份带勾选状态的清单 */
    PLAN(new Color(0x23263A), new Color(0xF7F9FF), new Color(0x9AA3B8),
            new Color(0x3FA45B), new Color(0x9AA3B8)) {
        @Override
        void draw(Graphics2D g, double cell) {
            fill(g, cell, 3, 1, 10, 14, paper);
            outline(g, cell, 3, 1, 10, 14);
            // 第一行：已打勾
            fill(g, cell, 4, 4, 2, 2, accent2);
            fill(g, cell, 7, 4, 5, 1, accent3);
            // 第二行
            outline(g, cell, 4, 7, 2, 2);
            fill(g, cell, 7, 7, 5, 1, accent3);
            // 第三行
            outline(g, cell, 4, 10, 2, 2);
            fill(g, cell, 7, 10, 5, 1, accent3);
        }
    },

    /** 常用文件：文件夹里露出一份文档 */
    FILES(new Color(0x23263A), new Color(0xF7F9FF), new Color(0xE3B341),
            new Color(0xE3B341), new Color(0x4FA3C7)) {
        @Override
        void draw(Graphics2D g, double cell) {
            fill(g, cell, 1, 5, 14, 9, accent2);          // 文件夹主体
            outline(g, cell, 1, 5, 14, 9);
            fill(g, cell, 2, 3, 5, 3, accent2);           // 文件夹耳朵
            outline(g, cell, 2, 3, 5, 3);
            fill(g, cell, 6, 1, 7, 6, paper);             // 露出的文档
            outline(g, cell, 6, 1, 7, 6);
            fill(g, cell, 8, 3, 3, 1, accent3);           // 文档上的文字
            fill(g, cell, 8, 5, 3, 1, accent3);
        }
    },

    /** 视频网站：一台带播放键的小电视 */
    VIDEO(new Color(0x23263A), new Color(0xF7F9FF), new Color(0xE86A92),
            new Color(0xE86A92), new Color(0x4FA3C7)) {
        @Override
        void draw(Graphics2D g, double cell) {
            fill(g, cell, 4, 1, 1, 2, ink);               // 天线
            fill(g, cell, 11, 1, 1, 2, ink);
            fill(g, cell, 1, 3, 14, 11, accent2);         // 电视外壳
            outline(g, cell, 1, 3, 14, 11);
            fill(g, cell, 3, 5, 10, 7, paper);            // 屏幕
            outline(g, cell, 3, 5, 10, 7);
            fill(g, cell, 6, 6, 1, 5, accent3);           // 播放三角
            fill(g, cell, 7, 7, 1, 3, accent3);
            fill(g, cell, 8, 8, 1, 1, accent3);
        }
    };

    final Color ink;
    final Color paper;
    final Color line;
    final Color accent2;
    final Color accent3;

    PixelIcon(Color ink, Color paper, Color line, Color accent2, Color accent3) {
        this.ink = ink;
        this.paper = paper;
        this.line = line;
        this.accent2 = accent2;
        this.accent3 = accent3;
    }

    abstract void draw(Graphics2D g, double cell);

    /** 把 16x16 格子的图标画到 (x, y)，每个格子边长 cell 像素。 */
    public void render(Graphics2D g, double x, double y, double cell) {
        Graphics2D copy = (Graphics2D) g.create();
        copy.translate(x, y);
        draw(copy, cell);
        copy.dispose();
    }

    static void fill(Graphics2D g, double cell, int gx, int gy, int gw, int gh, Color c) {
        if (gw <= 0 || gh <= 0) {
            return;
        }
        g.setColor(c);
        g.fill(new Rectangle2D.Double(gx * cell, gy * cell, gw * cell, gh * cell));
    }

    /** 用单位格拼出一圈描边，保持像素感。 */
    static void outline(Graphics2D g, double cell, int gx, int gy, int gw, int gh) {
        g.setColor(new Color(0x23263A));
        g.fill(new Rectangle2D.Double(gx * cell, gy * cell, gw * cell, cell));
        g.fill(new Rectangle2D.Double(gx * cell, (gy + gh - 1) * cell, gw * cell, cell));
        g.fill(new Rectangle2D.Double(gx * cell, gy * cell, cell, gh * cell));
        g.fill(new Rectangle2D.Double((gx + gw - 1) * cell, gy * cell, cell, gh * cell));
    }
}
