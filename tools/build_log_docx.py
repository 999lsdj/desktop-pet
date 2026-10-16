"""生成桌宠项目的进度日志 Word 文档。

每次项目推进后重新运行本脚本，即可刷新桌面上的日志追踪文档。
用法：
  python tools/build_log_docx.py --out docs/桌宠项目进度日志.docx
"""

import argparse
import json
import os
from pathlib import Path
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
    paragraph.paragraph_format.keep_with_next = True
    paragraph.paragraph_format.space_before = Pt(14 if level == 1 else 10)
    paragraph.paragraph_format.space_after = Pt(6)
    run = paragraph.add_run(text)
    set_run(run, size=14 if level == 1 else 12, bold=True)
    return paragraph


def build(out_path, figure_path, mesh_figure_path="", skeleton_figure_path="", parts_dir="", motion_dir="", production_dir=""):
    doc = Document()
    root = Path(__file__).resolve().parent.parent
    task_lines = (root / 'openspec/changes/skeletal-pet-animation/tasks.md').read_text(encoding='utf-8').splitlines()
    task_count = sum(line.startswith(('- [x]', '- [ ]')) for line in task_lines)
    done_count = sum(line.startswith('- [x]') for line in task_lines)

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
    title = doc.add_paragraph(style="Title")
    for node in doc.styles["Title"].element.findall(".//" + qn("w:pBdr")):
        node.getparent().remove(node)
    # 默认 Word 模板可能给 Title 继承蓝色底线，显式关闭边框。
    borders = OxmlElement("w:pBdr")
    for side in ("top", "bottom", "left", "right", "between"):
        edge = OxmlElement("w:" + side)
        edge.set(qn("w:val"), "nil")
        borders.append(edge)
    title._p.get_or_add_pPr().append(borders)
    title.paragraph_format.space_after = Pt(4)
    run = title.add_run("桌宠项目开发进度日志")
    set_run(run, size=20, bold=True)

    subtitle = doc.add_paragraph()
    subtitle.paragraph_format.space_after = Pt(14)
    run = subtitle.add_run("记录计划、过程与结果  更新于 2026-10-16  素材精修与正式动画接入")
    set_run(run, size=10.5)

    body(doc,
         "这份日志用来跟踪桌宠项目的推进过程，方便随时看清已经完成了什么、"
         "每一步是怎么做的、结果如何，以及下一步要做什么。当前进度是："
         "第一步「点击展开三角形菜单」已经完成并通过实机验证，"
         "随后按使用反馈做了第二轮调整（角色缩小、菜单移到头顶上方、去掉悬停文字），"
         "第五步「让桌宠动起来」也已完成，并在被退回一次后返工做对；"
         "计划表、常用文件、视频网站三个功能还只是占位按钮，等待后续实现。"
         "最新一轮把目标提到骨骼动画（关节要能连续弯），并新增被鼠标拎起来与招手两个动作，"
         "当前已精修 Q 版隐藏材质，并把分层蒙皮、接地步态与衣发柔性接入正式窗口代码。"
         "桌宠保持关闭，用户观感、组合动作及桌面交互仍待验收。"
         f"OpenSpec 当前完成 {done_count}/{task_count} 项。"
         "最新结论见第十六节；前文保留各阶段的历史状态。")

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
            ["第 5 步", "走动、微醺摇晃、提灯物理、休息", "已完成"],
            ["第 6 步", "骨骼动画：关节灵活、被鼠标拎起来、招手", "进行中"],
            ["第 7 步", "待机动画细节、拖拽手感、开机自启", "待开始"],
            ["第 8 步", "用 jpackage 打包成免安装 exe", "待开始"],
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
             "把像素精灵按 2 倍最近邻放大显示，保证像素边缘不发虚"
             "（第一版用 132×154，第二轮改为 105×123）",
             "角色正常显示"],
            ["1.3 透明置顶窗口",
             "用无边框窗口加逐像素透明背景，并设置置顶，让它始终浮在桌面上",
             "窗口透明，且不占任务栏"],
            ["1.4 轮廓裁剪",
             "按精灵图的 alpha 通道逐行生成角色剪影，作为窗口形状",
             "角色以外的透明区域完全不挡鼠标点击"],
            ["1.5 三角形菜单",
             "三个按钮按等边三角形排布，间隔 120 度，按钮直径 56 逻辑像素，"
             "展开动画 150 毫秒缓出（外接圆半径第一版 110 逻辑像素，第三轮收到 80）",
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
        picture.add_run().add_picture(figure_path, width=Cm(5.0))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        caption.paragraph_format.space_after = Pt(10)
        run = caption.add_run("图 1　当前效果：点击角色后三个入口在头顶上方以三角形展开")
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
            ["腿和提灯被裁掉",
             "窗口裁剪形状只按躯干图层生成，拆件后的腿和提灯落在形状之外",
             "改用未拆分的整图生成裁剪形状，四肢与提灯都不再被切掉"],
            ["躺平时像一团乱麻",
             "整身硬转到接近水平，大衣、长发和长杖被拖着横向摊开",
             "退回「靠着打盹」：小幅后仰、放缓呼吸、头顶冒 Z 字；"
             "真正的躺姿需要一张躺下的立绘"],
        ],
        widths=[3.2, 5.4, 6.4],
    )

    heading(doc, "五、当前交付物")
    make_table(
        doc,
        ["文件", "说明"],
        [
            ["src/pet/*.java", "入口、窗口、精灵、骨架、蒙皮、链式动力学、图标与日志等源码"],
            ["build.ps1 / run.ps1", "编译与启动脚本"],
            ["assets/sprites/pet-base.png", "桌宠使用的精灵图（105×123 原生像素）"],
            ["assets/像素角色/", "透明母图、精灵图与放大预览"],
            ["tools/prepare-sprite.ps1", "素材抠底与像素化工具"],
            ["tools/screenshot.ps1", "DPI 感知截屏工具，用于验收"],
            ["logs/pet.log", "运行日志，记录展开、点击、移动等动作"],
            ["准备与规格.md", "规格与美术资产总表"],
        ],
        widths=[6.0, 9.0],
    )

    heading(doc, "六 后续功能计划")
    body(doc,
         "以下实用功能保留在后续计划中，当前先推进第十三节的骨骼素材工作。"
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
            ["未做眨眼动画", "角色原设定就是闭眼微笑，眨眼不必要"],
            ["素材版权", "角色形象属同人二创，自用无碍；"
             "若要推送到公开仓库，建议只提交代码，素材另行处理"],
        ],
        widths=[3.4, 11.6],
    )

    heading(doc, "八、使用反馈与调整记录")
    body(doc,
         "第一轮做完之后，实际用起来陆续发现几处不顺手，逐条修掉，"
         "每改一次都重新截图验收。")
    make_table(
        doc,
        ["轮次", "反馈的问题", "调整做法", "结果"],
        [
            ["第二轮", "角色偏大",
             "从 800 像素母图按 5×5 分块重新降采样，得到 105×123 原生精灵，"
             "再按 2 倍放大显示，整体比原来小约两成，像素边缘依然清晰",
             "角色尺寸合适，细节没有丢失"],
            ["第二轮", "菜单遮挡角色",
             "三角形改为尖朝上，整体移到角色头顶上方，"
             "底边两个按钮的下缘停在头顶之上，窗口相应加高并保持透明裁剪",
             "三个入口不再压住角色，三角形仍然成立"],
            ["第二轮", "按钮旁边出现多余小字",
             "删掉鼠标悬停时弹出的文字标签，只保留图标与悬停放大反馈",
             "界面清爽，不再出现那行黑字"],
            ["第三轮", "菜单离角色太远",
             "三角形外接圆半径从 110 逻辑像素收到 80，"
             "底边与头顶之间的空隙从 10 收到 5，整体贴住角色头部",
             "位置紧凑，按钮与角色的指向关系更清楚"],
            ["第三轮", "圆圈周围有黑边",
             "去掉按钮的深色描边，只保留白色底盘与柔和投影",
             "边缘干净，不再有黑圈"],
            ["第三轮", "最上方图标偏高",
             "把顶端按钮到圆心的距离从 80 收到 45 逻辑像素，三角形压扁，"
             "并给圆盘加了两层柔和投影",
             "顶端图标下移，整体更贴合角色头部；浅色桌面上也看得出圆盘边界"],
        ],
        widths=[1.6, 2.9, 6.5, 4.0],
    )

    heading(doc, "九、让桌宠动起来")
    body(doc,
         "这一轮改动最大：她不再固定站在那里，而是沿屏幕底边来回踱步、偶尔停下打盹，"
         "走动时带着微醺的摇晃，手里的提灯像真的一样甩动。"
         "素材仍然只有一张站立图，所以「把图拆成可动部件」是这一轮的技术核心。")
    make_table(
        doc,
        ["环节", "做法", "结果"],
        [
            ["拆件",
             "把 105×123 的精灵图按坐标拆成躯干、提灯（杖＋灯笼）、左右小腿四个部件，"
             "以握点与髋部作为旋转枢轴",
             "提灯能独立摆动，小腿能交替迈步"],
            ["踱步",
             "沿屏幕底边左右走动，速度 30 逻辑像素/秒，走 8–20 秒、停 3–10 秒，"
             "到屏幕边缘水平镜像转身",
             "她会一直在桌面底部来回踱步"],
            ["微醺摇晃",
             "整体倾斜 ±8 度、周期 1.5 秒，叠加 2–4 像素的上下浮沉；"
             "每 20–40 秒踉跄一次，倾斜到 15 度并停顿半秒",
             "走起来带着醉意，偶尔绊一下"],
            ["提灯单摆",
             "自写单摆物理：重力回复力、阻尼、手的水平加速度耦合，摆角限制在 ±35 度",
             "起步、停下、转身时提灯都会甩动，静止后余摆渐渐停住"],
            ["休息打盹",
             "每隔 2–5 分钟休息 20–60 秒，到点自动醒，点一下立即醒；"
             "靠小幅后仰、放缓呼吸和头顶的 Z 字表达",
             "符合她原本的闭眼设定"],
            ["点击交互",
             "点她即停止踱步（打盹中先起身），手里的杖向外摆开，"
             "三个入口在她头顶以弧形展开；再点一下收起并继续踱步",
             "交互链路走通"],
            ["全屏让路",
             "常驻脚本检测前台窗口是否正好铺满屏幕；"
             "最大化的普通窗口会比屏幕多出约 9 像素隐形边框，所以判定用精确匹配。"
             "全屏时她淡到 15% 并停住",
             "你看网课时她不会挡画面"],
            ["站在任务栏上",
             "同一脚本回报任务栏上沿，她平滑地抬脚站到弹出的任务栏上，"
             "任务栏收起后再落回屏幕底边",
             "不会被任务栏压住半截"],
            ["参数外置",
             "速度、摆幅、周期、休息间隔、摆长与阻尼等全部写进 config.properties",
             "改完重启即可调手感"],
        ],
        widths=[2.4, 8.2, 4.4],
    )
    body(doc,
         "关于你提供的那段录屏：它是《明日方舟》基建房间的画面，"
         "我按它作为动作与节奏的参考，量出里面角色的踱步速度约为每秒 0.1 个身位，"
         "本机采用的是每秒 0.15 个身位，略快一点——桌面比游戏房间大得多，"
         "太慢会显得像卡住，这个数字在配置文件里，随时可以调。"
         "录屏本身不能当帧素材用：她在画面里只有约 200 像素高，"
         "背后是复杂房间，脚下还压着状态标签，抠帧的质量会明显低于现有精灵图。")

    heading(doc, "十、第三轮返工：把移动做对")
    body(doc,
         "第一版「会走路」交付之后被打了回来：平移诡异、双腿看不出迈步、图像有丢失、提灯也不摆。"
         "逐条查下来，根因是窗口裁剪和驱动力两件事没做对。")
    make_table(
        doc,
        ["反馈的问题", "根因", "修复"],
        [
            ["头、腿、提灯有缺失",
             "窗口裁剪形状只按静止姿态生成，身体一倾斜、腿一摆、灯一甩，"
             "这些部位就落到形状之外被裁掉",
             "改成「动作范围包络」：把角色在四个极限姿态（±16 度倾斜 × ±35 度甩灯）"
             "加上放下提灯的姿势各渲染一次，含镜像共 12 张，"
             "取所有不透明像素的并集作为静态裁剪形状，开机生成一次，不再每帧重算"],
            ["双腿看不出迈步",
             "摆角只有 7 度，脚几乎原地不动",
             "摆角提到 13 度，并给抬起的那条腿加 5 像素抬脚；"
             "上下起伏改成与步频同相，走起来才有踩地的节奏"],
            ["提灯不摆",
             "驱动力只来自手的加速度，而匀速走动时手几乎不加速，摆幅趋近于零",
             "提高驱动力增益，并在每一步给一个交替方向的推力；"
             "停下后余摆照样衰减到静止"],
            ["平移诡异",
             "每帧移动窗口的同时，「地面线跟随任务栏」也在改窗口位置，两者互相覆盖",
             "把位置更新收到一处，每帧只设置一次窗口位置"],
            ["躺下像一团乱麻",
             "整身硬转到接近水平，大衣、长发和长杖被拖着横向摊开",
             "改成把挂灯放到地上立着，她朝灯的方向靠着打盹，头顶冒 Z 字；"
             "拿起灯才算醒"],
            ["全屏时还能看见她",
             "原来只是淡到 15%",
             "改成完全隐藏，退出全屏再出现"],
        ],
        widths=[2.9, 5.3, 6.8],
    )

    heading(doc, "十一、怎么运行与验证")
    body(doc,
         "启动脚本里固定了关闭界面缩放的参数，目的是让像素图按整数倍显示；"
         "绕过脚本直接敲 java 命令时也要带上同样的参数，否则 125% 缩放下位置和清晰度都会出问题。")
    make_table(
        doc,
        ["命令或操作", "作用"],
        [
            ["pwsh -File build.ps1", "用 JDK 自带的 javac 编译到 out 目录"],
            ["pwsh -File run.ps1", "正常启动桌宠"],
            ["pwsh -File run.ps1 --demo", "启动后自动展开菜单，用于截图"],
            ["pwsh -File run.ps1 --selftest", "用系统级鼠标事件点一次角色，验证点击链路是否通"],
            ["tools 目录下的脚本", "截屏验收（DPI 感知）、渲染本日志检查排版"],
            ["右键角色，或托盘图标右键", "退出桌宠"],
        ],
        widths=[6.4, 8.6],
    )

    heading(doc, "十二、骨骼动画方案落成与第一版验证")
    body(doc,
         "角色现在的动作是四片刚体贴图各自绕枢轴旋转，关节处没有形变能力，"
         "多转几度就会露出被挖空的透明洞，所以动作幅度一直做不大。"
         "这一轮把「骨骼动画」定成新目标：关节要能连续弯，"
         "并新增两个动作——被鼠标拎起来、招手，两者都需要循环播放。"
         "方案整理成一份 OpenSpec 变更文档，放在项目的 openspec 目录下。")
    make_table(
        doc,
        ["事项", "决定"],
        [
            ["骨骼放哪",
             "在 Java 运行时自己求值：骨树、双骨 IK、按顶点权重的网格变形。"
             "不引入任何第三方库，编译仍然只用 JDK 自带的 javac"],
            ["形变分级",
             "只有肩、肘、髋、膝四处用蒙皮；头发、袖子、袍摆、提灯挂成链，"
             "链式柔性已改为 Verlet 积分加距离约束，单摆只保留给提灯"],
            ["角色尺寸",
             "用户已确认保留当前 Q 版外观：800×800 透明原图按 5 倍整数降采样，"
             "裁剪后仍为 105×123；2048 正常身材参考图只保留作历史实验"],
            ["素材来源",
             "缺失的像素（关节缝、被头发和袖子挡住的躯干、张开的手掌、"
             "抬起的小臂、独立袖子层）交给本机 ComfyUI 补；"
             "它只负责出静态素材，不出动画"],
            ["招手怎么做",
             "招手时把提灯杖杵在地上，用握杖那只手招，"
             "省掉再造一条远处手臂的工程量"],
            ["拎起来",
             "拖拽时把悬挂锚点从脚下换成鼠标抓点，肢体和头发自然下垂，松手落回地面"],
        ],
        widths=[2.6, 12.4],
    )
    body(doc,
         "第一版验证挑的是整条路线里唯一没有现成代码可抄的部分："
         "用 Java 自己画三角网格变形，看 120 Hz 的每帧预算够不够。"
         "做法是把现有精灵图放大到屏幕上同样的大小，以髋线为折痕做弯曲，"
         "逐帧计时两百多帧并丢掉预热帧，求值与绘制分开统计。")
    make_table(
        doc,
        ["方案", "求值（顶点位移）", "绘制（逐三角形贴图）", "合计"],
        [
            ["网格 12×14（336 个三角形）", "0.008 ms", "0.997 ms", "约 1.0 ms"],
            ["网格 24×28（1344 个三角形）", "0.009 ms", "2.233 ms", "约 2.2 ms"],
            ["对照：整图直贴（现在的做法）", "—", "0.077 ms", "0.08 ms"],
        ],
        widths=[5.8, 3.4, 3.6, 2.2],
        aligns=["l", "c", "c", "c"],
    )
    body(doc,
         "每帧预算是 8.33 ms。两种网格密度分别占约 12% 和 27%，都留得住；"
         "求值几乎不花时间，代价全在逐三角形贴图。"
         "结论是继续走蒙皮路线，不退回「分段刚体」那条备选，"
         "正式实现时按「关节附近加密、其余稀疏」来分配三角形。")
    if mesh_figure_path and os.path.exists(mesh_figure_path):
        picture = doc.add_paragraph()
        picture.alignment = WD_ALIGN_PARAGRAPH.CENTER
        picture.paragraph_format.space_before = Pt(10)
        picture.paragraph_format.space_after = Pt(4)
        picture.add_run().add_picture(mesh_figure_path, width=Cm(15.0))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        caption.paragraph_format.space_after = Pt(10)
        run = caption.add_run("图 2　网格弯曲验证 上排为贴图 下排为网格 四列依次为 0、15、30、45 度")
        set_run(run, size=9)
    body(doc,
         "第二组接着把骨树、双骨 IK 和说明文档做完（当时 29 项任务里已完成 6 项）。"
         "骨架没有另写一套坐标，髋部、握点、杖底三个关节直接读拆件的值，"
         "所以「骨架和拆件对齐」是结构上的，不靠两处常量互相凑。")
    if skeleton_figure_path and os.path.exists(skeleton_figure_path):
        picture = doc.add_paragraph()
        picture.alignment = WD_ALIGN_PARAGRAPH.CENTER
        picture.paragraph_format.space_before = Pt(10)
        picture.paragraph_format.space_after = Pt(4)
        picture.add_run().add_picture(skeleton_figure_path, width=Cm(15.0))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        caption.paragraph_format.space_after = Pt(10)
        run = caption.add_run("图 3　骨架验收：角色画成半透明，蓝线是骨头、橙点是关节、紫点是根节点；"
                              "三格分别是静止姿态、摆腿甩灯、用 IK 够目标点（绿十字）")
        set_run(run, size=9)
    body(doc,
         "每项都留了可复跑的自检。改完坐标跑一次预览工具，日志里就会打出结果："
         "骨架关节与拆件坐标的偏差、IK 的末端误差、蒙皮在静止姿态下的顶点位移、"
         "以及链式摆动多久能停住。"
         "肩关节与肘关节在现有扁平立绘上看不出来，暂时按估计值放，"
         "等美术返工做出分层部件后再量真实位置。")
    body(doc,
         "第三组「链式柔性」数值验证时改过一次方案。第一版按设计文档的思路把单摆串成链，"
         "结果被自检直接证伪：所有链节等长、固有频率相同，每一级都正好踩在上一级的共振点上，"
         "越往下振幅越大，最后死死卡在限位上不收敛——把限位从 40 度收到 8 度，"
         "后两节依然精确停在 8 度，说明不是限位的问题。"
         "改成 Verlet 积分加距离约束之后，同一份输入前 4 秒顶在限位、之后每秒衰减约 3 倍，"
         "12 秒内稳稳停住，而且每节杆长偏差是 0.000000 像素。")
    make_table(
        doc,
        ["环节", "关键实测"],
        [
            ["骨架与 IK",
             "关节与拆件坐标偏差 0.000000 原生像素；IK 186 组目标末端误差 0.000000 设备像素"],
            ["网格蒙皮",
             "静止姿态顶点位移 0.000000000 原生像素；每帧重算平均 0.015 毫秒（120 Hz 预算 8.33 毫秒）"],
            ["链式柔性",
             "末端滞后 0.100 秒；停摆 4.47 秒内所有关节落到 1 度以内；杆长偏差 0.000000 像素"],
            ["参数进配置",
             "只把发丝阻尼从 2.5 改成 6.0：发丝停摆 4.47→2.05 秒，袖子与袍摆的表现完全不变"],
        ],
        widths=[2.6, 12.4],
    )
    body(doc,
         "期间还发现两个必须等美术返工之后才能解决的问题："
         "一是整张图统一按距离分配蒙皮权重，会让同一块材质被两根骨头拉扯"
         "（提灯杖会被撕成一条长条、灯笼糊到脸上），权重必须改成「先认部件、再认距离」；"
         "二是柔性链目前只验了数值上的滞后与收敛，还没和真实发丝、袖子的图形合过。")
    body(doc,
         "当前顺序已调整为：先完成 Q 版正式分层与缺口补齐，再接入蒙皮、迁移既有动作，"
         "随后实现拎起和招手，最后验收包络与命中。分层是后续工作的前置条件，"
         "因为蒙皮权重必须按部件约束。")

    heading(doc, "十三 Q 版分层与补洞阶段进度")
    body(doc,
         "2026 年 10 月 15 日，用户确认沿用当前 Q 版持杖外观。正式来源固定为 "
         "assets/像素角色/master-transparent-800.png；原图的内容采样范围是 "
         "[139,81,664,696)，按 5 倍降采样得到 105×123 精灵，与当前运行素材逐像素一致。"
         "SDXL 补洞模型已完整下载并通过 SHA-256 校验，两次衣袖冒烟执行和 6 项基础测试通过。")
    body(doc,
         "本轮把原来的四矩形拆件扩展为按轮廓定义的 22 个候选部件，分开手、杖、提灯、"
         "头部、双侧长发、衣摆、袖子和四肢。两条小臂在原图中被衣袖遮挡，"
         "初始层为空，必须通过补洞生成，不能把衣袖当成已经存在的小臂。"
         "每个部件记录来源、枢轴、骨头名、可见范围和缺失蒙版；关节余量默认 3 个原生像素，"
         "远侧脚踝为 4 个像素。")
    if parts_dir:
        evidence = Path(parts_dir)
        split = json.loads((evidence / "report.json").read_text(encoding="utf-8"))
        filled = json.loads((evidence / "inpaint-report.json").read_text(encoding="utf-8"))
        make_table(doc, ["检查项", "本轮结果"], [
            ["原姿态可见层重组", f'全部 RGBA 像素差异 {split["reassembly_changed_rgba_pixels"]}，包含半透明边缘'],
            ["补洞输入", f'{split["inpaint_jobs"]} 个部件任务，合计 {split["missing_layer_pixels"]} 个待补层像素（不同层分别计数）'],
            ["补洞执行", f'已生成 {len(filled["completed"])} 个部件候选，逐项保留输入、蒙版、工作流、结果和像素检查报告'],
            ["补洞前关节扫描", f'{split["joint_samples"]} 个单关节角度样本，{split["joint_samples_with_gaps"]} 个样本存在连接处缺口'],
            ["补洞后关节扫描", f'{filled["joint_samples"]} 个单关节角度样本，{filled["joint_samples_with_gaps"]} 个样本仍有缺口；不替代蒙皮与组合动作验收'],
            ["补层后原姿态重组", f'{filled["rest_changed_rgba_pixels"]} 个 RGBA 像素发生变化；仍须检查移动时的层级与接缝'],
        ], widths=[4.0, 11.0])
    body(doc,
         "新缺口统计只检查关节连接区域，排除了部件移动后正常腾出的背景。旧报告里整张图的"
         "轮廓差值不能当作隐藏像素的精确数量。初始可见层可以无损重组，但 AI 候选的层级覆盖、"
         "材质接缝和张开手掌尚未全部验收；当前不替换运行中的桌宠，也不勾选任务 7.1、7.2、7.3。"
         "本轮同步日志不等于完成任务 9.4 的最终收尾。")
    body(doc,
         "下一步优先修正候选分层的遮挡关系，逐部件检查关节补图；补齐独立袖子与张开手掌，"
         "完成全产线重复执行和极限姿态验收后，再把素材接入 PetWindow。"
         "当前候选范围已完整运行两遍，目录结构一致，163 个受比对的输入和产物文件哈希相同；"
         "完整素材清单仍未包括张开手掌，所以这不代表任务 7.3 完成。"
         "项目已设置每 30 分钟持续追踪，仅在进展、失败或需要处理的事项发生变化时通知。")

    heading(doc, "十四 实机反馈与分层蒙皮预览")
    body(doc,
         "用户在桌面运行版中发现待机僵硬和切割、手发衣摆不动、双腿像矩形贴片移动。"
         "已按要求关闭桌宠，本轮只做离屏验证。运行版仍是 BodyRig 四矩形路径，"
         "没有直接使用新候选；本节记录的是替换前的技术与素材验证。")
    body(doc,
         "layout v2 将误归给杖的蓝色发梢、误归给头部的杖轮廓、误归给头发的灯穗分回对应部件。"
         "双侧长发、袖子和衣摆补出隐藏连接轮廓，22 层共需执行 16 项补洞。"
         "长发生成输入只引用本层颜色，避免邻近手部与灯穗污染材质；已有可见像素始终保留。"
         "这些调整改善了候选，但连通块为一块并不代表材质与动作已经合格。")
    body(doc,
         "新的 LayeredRig 使用按材质限定的骨头集合；同一肢体各层共用网格与权重，"
         "使腿部运动不再拉动木杖或头发。手臂、双腿、发丝、袖子和衣摆可在离屏图中实际弯曲。"
         "绘制跳过透明三角形，单骨层直接绘制，并使用整数 RGBA 合成控制成本。"
         "预览角度来自周期函数，尚未接入 Verlet、IK 或正式动作状态机。")
    if motion_dir:
        motion = Path(motion_dir)
        report = json.loads((motion / 'report.json').read_text(encoding='utf-8'))
        timing = report['timing_ms_png_io_excluded']
        make_table(doc, ['离屏检查', '结果与边界'], [
            ['静止图', f'参考重组差异 {report["rest_rgba_difference_pixels"]}；实际渲染差异 {report["rest_render_difference_pixels"]} 个 RGBA 像素'],
            ['连续预览', f'{report["frames_per_cycle"] * report["cycles"]} 帧，{report["cycles"]} 轮周期；首尾差异 {report["rendered_cycle_closure_difference_pixels"]}。周期函数不等于正式动作连续性验收'],
            ['裁切检查', f'本组样本顶点均在 {report["padding_px"]} 原生像素留边内：{report["all_tested_vertices_inside_padding"]}；不代表所有动作包络合格'],
            ['求值耗时', f'平均 {timing["update"]["mean"]:.3f} ms，P95 {timing["update"]["p95"]:.3f} ms'],
            ['绘制耗时', f'平均 {timing["draw"]["mean"]:.3f} ms，P95 {timing["draw"]["p95"]:.3f} ms；不含 PNG 写盘与完整窗口成本'],
        ], widths=[3.5,11.5])
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.add_run().add_picture(str(motion/'poses.png'), width=Cm(15))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        set_run(caption.add_run('图 4 分层蒙皮诊断姿态 仍需修正隐藏材质与髋部遮挡'), size=9)
    body(doc,
         "代码审查发现非有限坐标可能误通过画布边界检查，现已增加回归并拒绝该情况。"
         "正式接入前仍要处理隐藏发丝的颜色和轮廓、髋部拉伸与遮挡，补张开手掌，"
         "将自然摆动与既有动作迁到分层蒙皮，再验证极限动作、窗口裁切、命中和实机观感。"
         "OpenSpec 2.3、3.2、4.1、4.2、7.1 至 7.3 均保持未完成；候选 runtime_ready 为 false。")

    body(doc,
         "同日按用户反馈修正膝盖内凹与腿部摆幅。髋摆幅从 ±17 度减为 ±7 度，"
         "膝盖最高弯曲从 34 度减为 24 度；只在抬腿半周弯曲，落下回直，两侧方向镜像。"
         "旧权重会让脚踝牵动膝盖，矩阵混合也会缩窄弯曲处。腿部现改为相邻关节局部权重，"
         "围绕共同枢轴插值旋转角。独立踝膝隔离、膝盖 ±45 度截面和 721 个动作相位回归通过。"
         "这次修改随后仍被用户反馈步幅不均和右腿内凹，不能作为视觉验收通过；后续处理见下一节。"
         "本节图 4 与耗时展示最新离屏结果，正式自然动作仍未验收。")

    heading(doc, "十五 双腿步幅与脚掌方向修正")
    body(doc,
         "2026 年 10 月 16 日按最新要求把髋摆幅调整到 ±12 度。核对画面后确认左腿为 Near、右腿为 Far。"
         "旧动作中左腿的髋膝转动互相抵消，右腿却同向叠加，脚掌又继承了这组旋转。"
         "左右踝横向行程因此只有 3.865 与 11.312 原生像素，右靴最大倾斜达到 34.510 度。")
    body(doc,
         "新驱动让两腿相差半周期，弯膝放在向前收腿阶段，峰值为 14 度，两侧使用相同弯曲方向。"
         "脚掌追加角抵消髋膝旋转，保留站姿方向。左右踝横向行程均为 11.227 原生像素，"
         "旧右靴的明显整体斜折在八相位对照中已消除；本轮没有改动原素材、补洞结果或踝枢轴。")
    body(doc,
         "721 个相位的范围与交替检查通过，收腿交界及循环首尾的骨端点速度连续；"
         "144 帧离屏预览的静止渲染、循环闭合差异均为 0。前后动画和静态对照保存在候选目录的 "
         "motion-preview/gait-comparison.gif 与 gait-comparison.png，逐帧图位于 _tmp/gait-fix/after。")
    body(doc,
         "新版视觉效果仍待用户复核。脚底接地尚未求解，左右踝仍有约 1.126 与 2.503 原生像素纵向变化；"
         "交替弯膝不等于脚底稳定支撑，也不能据此认定跳动观感已解决。下一步先处理脚底接地、两靴交叉遮挡与自然步态，"
         "再继续隐藏材质、张开手掌、衣发柔性和运行版接入。OpenSpec 仍为 11/29，桌宠保持关闭。")

    if production_dir:
        production = Path(production_dir)
        metrics = json.loads((production / 'self-test.json').read_text(encoding='utf-8'))
        validation = json.loads((root / 'assets/rig/q-chibi-provenance/validation.json').read_text(encoding='utf-8'))
        heading(doc, "十六 素材精修与正式动画接入")
        body(doc,
             "2026 年 10 月 16 日，22 层 Q 版素材完成本轮精修，PetWindow 已改用 PetAnimation 与 LayeredRig。"
             "下一次按用户要求启动时将使用这一版本。此次没有运行 pet.Main；本节结果来自同一求值器的离屏验证，"
             "并非用户已认可成品。第十三至十五节中尚未接入、脚底接地未求解等表述为历史记录。")
        body(doc,
             "精修只修改 499 个隐藏像素，处理双侧蓝发、袖布和髋部生成黑块，原绘可见像素、蒙版外 RGBA 与静止重组差异均为 0。"
             "备用张开手掌采用独立轮廓引导与 ComfyUI 材质生成，保留输入、蒙版、输出和两轮尝试；默认仍握杖。"
             "髋以上短裤固定到躯干，髋下平滑接入大腿权重，避免短裤随腿拉伸。")
        make_table(doc, ['正式动作检查', '结果与边界'], [
            ['交替步态', f'实际髋半行程 {metrics["hip_half_range_deg"][0]:.3f}° / {metrics["hip_half_range_deg"][1]:.3f}°；两侧踝横向行程均 {metrics["ankle_stride_px"][0]:.3f} 原生像素'],
            ['接地与脚掌', f'{metrics["phase_samples"]} 相位覆盖三个周期，稳定支撑高度误差 {metrics["stable_support_height_error_px"]:.6f} 像素，脚掌方向误差 {metrics["foot_angle_error_deg"]:.6f}°；起停渐变另行验收'],
            ['衣发随动', f'发丝、袖子、衣摆使用 Verlet，提灯使用单摆；柔性峰值 {metrics["flex_peak_deg"]:.3f}°，停步六秒后 {metrics["flex_after_six_seconds_deg"]:.3f}°'],
        ], widths=[3.3,11.7])
        body(doc,
             "抬脚高度收为 0.6 原生像素，站姿屈膝下移 0.8 像素；移除旧的整体周期跳动。"
             "打盹时杖固定在地面，握点由手臂 IK 跟随。已输出走路、停步、微醺、踉跄、打盹和菜单六状态，"
             "另有 26 个关节极限端点和备用掌心姿态。三轮走路与停步衰减有回归，不能代替所有动作循环的验收。")
        picture = doc.add_paragraph()
        picture.alignment = WD_ALIGN_PARAGRAPH.CENTER
        picture.add_run().add_picture(str(production/'legs.png'), width=Cm(15))
        caption = doc.add_paragraph()
        caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
        set_run(caption.add_run('图 5 正式接地步态八相位 用户观感仍待复核'), size=9)
        body(doc,
             "完整来源缓存保存 16 项原补洞及掌心的输入、模型结果和工作流。build-refined-pipeline.py "
             "实际重新拆件、重放锁定的模型结果并执行精修；新目录与同目录重跑均通过，"
             f'两次实际命令的 {validation["file_count"]} 个产物文件 SHA 一致。'
             "这是缓存重建，不是重新执行 16 次模型推理，也不依赖旧临时目录或正在运行的 ComfyUI。")
        body(doc,
             f"当前 OpenSpec 为 {done_count}/{task_count}。素材状态仍为 runtime_ready:false，集成报告另记 runtime_integrated:true。"
             "技术接入和人工放行分开记录；还要复核腿部交叉遮挡与膝踝轮廓、隐藏材质与组合动作，"
             "继续拎起悬挂、完整放杖招手循环，以及窗口裁切、命中和端到端验收。"
             "六状态动画、原始报告与源码绑定位于 assets/rig/production-preview；本轮同步不勾选最终收尾任务 9.4。")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    doc.save(out_path)
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--figure", default="")
    parser.add_argument("--figure-mesh", default="")
    parser.add_argument("--figure-skeleton", default="")
    parser.add_argument("--parts-dir", default="", help="包含分层与补洞报告的产出目录")
    parser.add_argument("--motion-dir", default="", help="包含分层离屏报告与姿态图的目录")
    parser.add_argument("--production-dir", default=str(Path(__file__).resolve().parent.parent/'assets/rig/production-preview'), help="包含正式动画报告与腿部相位图的目录")
    args = parser.parse_args()
    path = build(args.out, args.figure, args.figure_mesh, args.figure_skeleton, args.parts_dir, args.motion_dir, args.production_dir)
    print("已生成:", path)


if __name__ == "__main__":
    main()
