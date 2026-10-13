"""生成桌宠项目的进度日志 Word 文档。

每次项目推进后重新运行本脚本，即可刷新桌面上的日志追踪文档。
用法：
  python tools/build_log_docx.py --out docs/桌宠项目进度日志.docx
"""

import argparse
import os
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

FONT = "Microsoft YaHei"
INK = RGBColor(0x00, 0x00, 0x00)
HEADER_FILL = "1F3864"
HEADER_TEXT = RGBColor(0xFF, 0xFF, 0xFF)
BORDER = "D9D9D9"
STRIPE = "F2F5FA"


def set_run(run, size=10.5, bold=False, color=INK, font=FONT):
    run.font.name = font
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    run._element.rPr.rFonts.set(qn("w:eastAsia"), font)


def shade(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    tc_pr.append(shd)


def table_borders(table):
    tbl = table._tbl
    tbl_pr = tbl.tblPr
    borders = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        element = OxmlElement("w:" + edge)
        element.set(qn("w:val"), "single")
        element.set(qn("w:sz"), "6")
        element.set(qn("w:space"), "0")
        element.set(qn("w:color"), BORDER)
        borders.append(element)
    tbl_pr.append(borders)


def cell_text(cell, text, bold=False, size=9.5, align=WD_ALIGN_PARAGRAPH.LEFT,
              color=INK):
    cell.text = ""
    paragraph = cell.paragraphs[0]
    paragraph.alignment = align
    paragraph.paragraph_format.space_before = Pt(4)
    paragraph.paragraph_format.space_after = Pt(4)
    paragraph.paragraph_format.line_spacing = 1.15
    run = paragraph.add_run(text)
    set_run(run, size=size, bold=bold, color=color)


def make_table(doc, headers, rows, widths, aligns=None, size=9.5):
    table = doc.add_table(rows=1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table_borders(table)

    for index, header in enumerate(headers):
        cell = table.rows[0].cells[index]
        shade(cell, HEADER_FILL)
        cell_text(cell, header, bold=True, size=size, color=HEADER_TEXT,
                  align=WD_ALIGN_PARAGRAPH.CENTER)

    # 表格跨页时重复表头
    tr_pr = table.rows[0]._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)

    for row_index, row in enumerate(rows):
        cells = table.add_row().cells
        for col_index, value in enumerate(row):
            if row_index % 2 == 1:
                shade(cells[col_index], STRIPE)
            align = WD_ALIGN_PARAGRAPH.LEFT
            if aligns and aligns[col_index] == "c":
                align = WD_ALIGN_PARAGRAPH.CENTER
            cell_text(cells[col_index], value, size=size, align=align)

    for row in table.rows:
        for index, width in enumerate(widths):
            row.cells[index].width = Cm(width)
    return table


def body(doc, text, size=10.5, space_after=8):
    paragraph = doc.add_paragraph()
    paragraph.paragraph_format.space_after = Pt(space_after)
    paragraph.paragraph_format.line_spacing = 1.4
    run = paragraph.add_run(text)
    set_run(run, size=size)
    return paragraph


def heading(doc, text, level=1):
    paragraph = doc.add_paragraph()
    paragraph.paragraph_format.space_before = Pt(14 if level == 1 else 10)
    paragraph.paragraph_format.space_after = Pt(6)
    run = paragraph.add_run(text)
    set_run(run, size=14 if level == 1 else 12, bold=True)
    return paragraph


def build(out_path, figure_path):
    doc = Document()

    style = doc.styles["Normal"]
    style.font.name = FONT
    style.font.size = Pt(10.5)
    style.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)

    section = doc.sections[0]
    section.top_margin = Cm(2.0)
    section.bottom_margin = Cm(2.0)
    section.left_margin = Cm(2.2)
    section.right_margin = Cm(2.2)

    # 标题
    title = doc.add_paragraph()
    title.paragraph_format.space_after = Pt(4)
    run = title.add_run("桌宠项目开发进度日志")
    set_run(run, size=20, bold=True)

    subtitle = doc.add_paragraph()
    subtitle.paragraph_format.space_after = Pt(14)
    run = subtitle.add_run("记录计划、过程与结果 · 更新于 2026-10-13")
    set_run(run, size=10.5)

    body(doc,
         "这份日志用来跟踪桌宠项目的推进过程，方便随时看清已经完成了什么、"
         "每一步是怎么做的、结果如何，以及下一步要做什么。当前进度是："
         "第一步「点击展开三角形菜单」已经完成并通过实机验证，"
         "计划表、常用文件、视频网站三个功能还只是占位按钮，等待后续实现。")

    heading(doc, "一、项目目标与分步计划")
    body(doc,
         "目标是做一个常驻桌面的像素风桌宠，使用角色自己的形象，"
         "并在点击时展开一个三角形菜单，承载三个实用功能。"
         "技术路线选定 Java 17 加 Swing，理由是这台机器上 JDK 已经装好，"
         "透明窗口、置顶、托盘、打包 exe 都在 JDK 内自带，不需要额外安装任何依赖。")
    make_table(
        doc,
        ["步骤", "内容", "状态"],
        [
            ["第 1 步", "窗口骨架与点击展开三角形菜单", "已完成"],
            ["第 2 步", "计划表：可指定任务与时间，点击展开查看", "待开始"],
            ["第 3 步", "常用文件：记录近期频繁打开的 5 个文件并快速打开", "待开始"],
            ["第 4 步", "视频网站：一键打开 B 站网页版", "待开始"],
            ["第 5 步", "待机动画、拖拽手感、开机自启", "待开始"],
            ["第 6 步", "用 jpackage 打包成免安装 exe", "待开始"],
        ],
        widths=[2.2, 10.4, 2.4],
        aligns=["c", "l", "c"],
    )

    heading(doc, "二、第一步做了什么")
    body(doc,
         "第一步的目标是让桌宠先「活」起来：一个透明置顶的窗口显示角色，"
         "点一下角色，三个功能入口以三角形展开。这一步拆成六个小环节完成。")
    make_table(
        doc,
        ["环节", "做法", "结果"],
        [
            ["1.1 工程骨架",
             "建立 src/pet 源码目录，用 JDK 自带的 javac 直接编译，零第三方依赖",
             "编译通过，输出到 out 目录"],
            ["1.2 角色接入",
             "把 132×154 的像素精灵按 2 倍最近邻放大到 264×308，保证像素边缘不发虚",
             "角色正常显示"],
            ["1.3 透明置顶窗口",
             "用无边框窗口加逐像素透明背景，并设置置顶，让它始终浮在桌面上",
             "窗口透明，且不占任务栏"],
            ["1.4 轮廓裁剪",
             "按精灵图的 alpha 通道逐行生成角色剪影，作为窗口形状",
             "角色以外的透明区域完全不挡鼠标点击"],
            ["1.5 三角形菜单",
             "三个按钮排在外接圆半径 110 逻辑像素、间隔 120 度的三角形上，"
             "按钮直径 56 逻辑像素，展开动画 150 毫秒缓出",
             "点击角色后三键展开，图标分别为清单、文件夹、播放"],
            ["1.6 兜底交互",
             "拖动可移动桌宠；右键菜单可退出；托盘图标可作为备用出口",
             "不依赖任务栏也能正常关闭"],
        ],
        widths=[2.6, 8.4, 4.0],
    )

    heading(doc, "三、验收入口与实测结果")
    body(doc,
         "验收不是「看起来对」，而是走了一遍真实点击：程序用系统级鼠标事件，"
         "把指针移到角色身上并点击左键，然后检查菜单状态与运行日志。"
         "点击之后日志依次出现「自检：点击完成」和「三角形菜单展开」，"
         "截图确认三个按钮都渲染在正确位置。")

    if figure_path and os.path.exists(figure_path):
        picture = doc.add_paragraph()
        picture.alignment = WD_ALIGN_PARAGRAPH.CENTER
        picture.paragraph_format.space_before = Pt(10)
        picture.paragraph_format.space_after = Pt(4)
        picture.add_run().add_picture(figure_path, width=Cm(5.5))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        caption.paragraph_format.space_after = Pt(10)
        run = caption.add_run("图 1　第一步验收截图：点击角色后三个功能入口以三角形展开")
        set_run(run, size=9)

    make_table(
        doc,
        ["验收项", "实测结果"],
        [
            ["编译", "javac 无报错，输出 out 目录"],
            ["显示", "角色在桌面右下角透明显示，无边框、无任务栏条目"],
            ["点击展开", "真实左键点击角色后，菜单状态由收起变为展开"],
            ["按钮布局", "三键间隔 120 度，实测半径 138、直径 70 设备像素"],
            ["托盘", "托盘图标创建成功，可显示或隐藏桌宠"],
        ],
        widths=[3.4, 11.6],
    )

    heading(doc, "四、过程中解决的问题")
    body(doc,
         "这一步真正的难点不在 Swing，而在素材和显示环境，"
         "下面四条都是排查后修掉的，记录在此避免以后重蹈。")
    make_table(
        doc,
        ["问题", "原因", "处理"],
        [
            ["角色图没有透明通道",
             "图片是 JPEG，棋盘格背景被压成了真实像素",
             "从四条边泛洪，只把与边界连通的接近白色像素置为透明，"
             "角色身上的白色外套不受影响"],
            ["像素网格对不齐",
             "素材属于 AI 像素风，放大倍数不是整数",
             "识别出像素格约 4 像素，改为按 4×4 分块、按透明度加权平均降采样"],
            ["素材处理脚本编译失败",
             "运行时加载的程序集与动态编译的引用集冲突",
             "放弃动态编译，改用纯 PowerShell 读写像素缓冲实现"],
            ["窗口被推出屏幕、图像发糊",
             "系统缩放 125%，窗口坐标被放大，绘制又被二次插值",
             "关闭 Java 的界面缩放，统一在设备像素空间绘制，"
             "再按 DPI 换算菜单尺寸"],
        ],
        widths=[3.2, 5.4, 6.4],
    )

    heading(doc, "五、当前交付物")
    make_table(
        doc,
        ["文件", "说明"],
        [
            ["src/pet/*.java", "5 个源文件：入口、窗口、精灵、像素图标、日志"],
            ["build.ps1 / run.ps1", "编译与启动脚本"],
            ["assets/sprites/pet-base-132x154.png", "桌宠使用的精灵图"],
            ["assets/像素角色/", "透明母图与放大预览"],
            ["tools/prepare-sprite.ps1", "素材抠底与像素化工具"],
            ["tools/screenshot.ps1", "DPI 感知截屏工具，用于验收"],
            ["logs/pet.log", "运行日志，记录展开、点击、移动等动作"],
            ["准备与规格.md", "规格与美术资产总表"],
        ],
        widths=[6.0, 9.0],
    )

    heading(doc, "六、下一步计划")
    body(doc,
         "第二步做计划表：用一个本地数据文件保存任务、时间和完成状态，"
         "点击三角形菜单左上角的清单按钮后，在桌宠旁边展开可勾选的面板。"
         "第三步做常用文件：读取 Windows 最近使用记录，统计出现频率最高的五个文件，"
         "点击即可直接打开。第四步做视频网站入口：调用系统默认浏览器打开 B 站网页版。"
         "三个功能都落地之后，再补待机动画与打包。")

    heading(doc, "七、已知问题与取舍")
    make_table(
        doc,
        ["事项", "说明"],
        [
            ["按钮是占位", "三个按钮点击后只弹出提示气泡，功能待后续步骤实现"],
            ["按钮与角色重叠",
             "半径 110 逻辑像素时，底部按钮会压在角色腰腿位置，"
             "已把绘制层级放在角色之上并抬高菜单圆心，后续可再微调"],
            ["未做眨眼动画", "角色原设定就是闭眼微笑，眨眼不必要"],
            ["素材版权", "角色形象属同人二创，自用无碍；"
             "若要推送到公开仓库，建议只提交代码，素材另行处理"],
        ],
        widths=[3.4, 11.6],
    )

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    doc.save(out_path)
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--figure", default="")
    args = parser.parse_args()
    path = build(args.out, args.figure)
    print("已生成:", path)


if __name__ == "__main__":
    main()
