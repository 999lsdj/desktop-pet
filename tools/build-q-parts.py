"""从已确认的 Q 版素材按轮廓拆层，输出补洞输入和未补齐关节的检查证据。"""
import argparse
from collections import deque
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def verify_source(data, layout):
    if hashlib.sha256(data).hexdigest() != layout["source_sha256"]:
        raise ValueError("来源图 SHA-256 与分层定义不一致，须重新量坐标")


def polygons(size, points):
    mask = Image.new("L", size)
    draw = ImageDraw.Draw(mask)
    for polygon in points:
        draw.polygon([tuple(p) for p in polygon], fill=255)
    return mask


def split_visible(original, parts):
    # 每个像素只有一个可见归属，半透明边缘不能重复叠加。
    if len(parts) > 255:
        raise ValueError("部件数量不能超过 255")
    owners = Image.new("L", original.size, 255)
    for index, part in enumerate(parts):
        owners.paste(index, mask=polygons(original.size, part["polygons"]))
    # 轮廓外的零碎抗锯齿像素归给最近的区域，不能全塞到躯干留下悬浮碎片。
    values = list(owners.get_flattened_data())
    queue = deque(i for i, v in enumerate(values) if v != 255)
    if not queue:
        raise ValueError("至少需要一个有轮廓的部件")
    width, height = original.size
    while queue:
        i = queue.popleft()
        x, y = i % width, i // width
        for nx, ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            j = ny*width+nx
            if 0 <= nx < width and 0 <= ny < height and values[j] == 255:
                values[j] = values[i]
                queue.append(j)
    owners.putdata(values)
    layers, masks = {}, {}
    for index, part in enumerate(parts):
        mask = owners.point(lambda value: 255 if value == index else 0).convert("L")
        layers[part["name"]] = Image.composite(original, Image.new("RGBA", original.size), mask)
        masks[part["name"]] = mask
    return layers, masks


def reassemble(layers, size):
    result = Image.new("RGBA", size)
    for layer in layers:
        result.alpha_composite(layer)
    return result


def fill_hidden(layer, generated, mask):
    if layer.size != generated.size or layer.size != mask.size:
        raise ValueError("补层尺寸不一致")
    if any(a > 0 and m > 0 for a, m in zip(layer.getchannel("A").get_flattened_data(), mask.get_flattened_data())):
        raise ValueError("补洞蒙版覆盖了已有部件像素")
    return Image.composite(generated.convert("RGBA"), layer, mask)


def concealed_padding(target, visible, front_layers):
    # 余量只放到前景完全遮住的位置；不能覆盖背后的既有像素或加深半透明轮廓。
    front = reassemble(front_layers, target.size).getchannel("A")
    occluded = front.point(lambda a: 255 if a == 255 else 0)
    return ImageChops.multiply(ImageChops.subtract(target, visible), occluded)


def source_mask(mask, size, layout):
    left, top, right, bottom = layout["crop"]
    result = Image.new("L", size)
    result.paste(mask.resize((right-left, bottom-top), Image.Resampling.NEAREST), (left, top))
    return result


def joint_gap(before, after, pivot, radius):
    # 只测连接处内部，排除轮廓随运动腾出的背景；轮廓连续性另由姿态图验收。
    interior = before.filter(ImageFilter.MinFilter(3))
    x0, y0 = pivot
    return sum(interior.getpixel((x, y)) > 8 and after.getpixel((x, y)) <= 8
               for y in range(max(0, y0-radius), min(before.height, y0+radius+1))
               for x in range(max(0, x0-radius), min(before.width, x0+radius+1))
               if (x-x0)**2 + (y-y0)**2 <= radius**2)


def checker(size):
    im = Image.new("RGBA", size, (228, 228, 228, 255))
    draw = ImageDraw.Draw(im)
    for y in range(0, size[1], 8):
        for x in range(0, size[0], 8):
            if (x//8+y//8) % 2:
                draw.rectangle((x, y, x+7, y+7), fill=(204, 204, 204, 255))
    return im


def sheet(items, path, cols=4, scale=3, padding=0):
    w, h = items[0][1].size
    cw, ch = (w+2*padding)*scale, (h+2*padding)*scale+24
    result = Image.new("RGB", (cw*cols, ch*((len(items)+cols-1)//cols)), "white")
    for i, (name, im) in enumerate(items):
        bg = checker((w+2*padding, h+2*padding))
        bg.alpha_composite(im, (padding, padding))
        x, y = (i % cols)*cw, (i//cols)*ch
        result.paste(bg.resize((cw, ch-24), Image.Resampling.NEAREST), (x, y+24))
        ImageDraw.Draw(result).text((x+5, y+5), name, fill="black")
    result.save(path)


def pose_checks(layers, original, layout, out):
    padding = 64
    size = (original.width+padding*2, original.height+padding*2)
    padded = {}
    for name, layer in layers.items():
        im = Image.new("RGBA", size)
        im.paste(layer, (padding, padding))
        padded[name] = im
    before = reassemble(padded.values(), size).getchannel("A")
    records, previews = [], []
    for joint in layout["joints"]:
        center = tuple(v+padding for v in joint["pivot"])
        # 两端加中间每 5 度的扫描，避免只在端点碰巧合拢。
        low, high = joint["angles"]
        angles = sorted(set([low, high, 0] + list(range(low, high+1, 5))))
        for angle in angles:
            pose = reassemble([
                im.rotate(-angle, resample=Image.Resampling.NEAREST, center=center)
                if name in joint["moves"] else im for name, im in padded.items()
            ], size)
            gaps = joint_gap(before, pose.getchannel("A"), center, joint["radius"])
            if angle in (low, high):
                label = f'{joint["name"]} {angle:+} deg / gap {gaps}'
                pose.save(out / "poses" / f'{joint["name"]}-{angle:+}.png')
                previews.append((label, pose))
            records.append({"joint":joint["name"], "angle":angle, "gap_pixels":gaps})
    sheet(previews, out / "poses.png", cols=4, scale=1)
    return records


def build(layout_path, out):
    out.mkdir(parents=True, exist_ok=True)
    write_json(out / "report.json", {"status":"building"})
    write_json(out / "inpaint-report.json", {"status":"not_run_for_current_build", "runtime_ready":False})
    try:
        _build(layout_path, out)
    except Exception as error:
        write_json(out / "report.json", {"status":"failed", "error":str(error)})
        raise


def _build(layout_path, out):
    layout = json.loads(layout_path.read_text(encoding="utf-8"))
    source_path, native_path = ROOT / layout["source"], ROOT / layout["native"]
    verify_source(source_path.read_bytes(), layout)
    verify_source(native_path.read_bytes(), {"source_sha256":layout["native_sha256"]})
    original = Image.open(native_path).convert("RGBA")
    source = Image.open(source_path).convert("RGBA")
    if list(original.size) != layout["native_size"] or list(source.size) != layout["source_size"]:
        raise ValueError("来源尺寸与分层定义不一致")
    left, top, right, bottom = layout["crop"]
    if (right-left, bottom-top) != tuple(v*layout["downsample"] for v in original.size):
        raise ValueError("裁剪框与整数倍率不匹配")
    for name in ("layers", "masks", "jobs", "poses"):
        (out / name).mkdir(exist_ok=True)
    layers, owners = split_visible(original, layout["parts"])
    rebuilt = reassemble(layers.values(), original.size)
    mismatch = sum(a != b for a, b in zip(original.get_flattened_data(), rebuilt.get_flattened_data()))
    if mismatch:
        raise AssertionError(f"原姿态重组有 {mismatch} 个 RGBA 像素不同")
    rebuilt.save(out / "reassembled.png")
    original.save(out / "original.png")
    opaque = original.getchannel("A").point(lambda a: 255 if a > 0 else 0)
    metadata, jobs = [], []
    for index, part in enumerate(layout["parts"]):
        name = part["name"]
        visible = ImageChops.multiply(owners[name], opaque)
        target = ImageChops.lighter(visible, polygons(original.size, part.get("reconstruct", [])))
        draw = ImageDraw.Draw(target)
        for joint in layout["joints"]:
            if name in (joint["parent"], joint["child"]):
                x, y = joint["pivot"]
                r = joint.get("overlap", layout["overlap"])
                draw.ellipse((x-r, y-r, x+r, y+r), fill=255)
        # 此阶段补的是被其他部件盖住的内容，不凭空扩大原姿态外轮廓。
        target = ImageChops.multiply(target, opaque)
        missing = concealed_padding(target, visible, list(layers.values())[index+1:])
        target = ImageChops.lighter(visible, missing)
        missing_count = sum(p > 0 for p in missing.get_flattened_data())
        layers[name].save(out / "layers" / f"{name}.png")
        visible.save(out / "masks" / f"{name}-visible.png")
        target.save(out / "masks" / f"{name}-target.png")
        missing.save(out / "masks" / f"{name}-missing.png")
        entry = {"name":name, "bone":part["bone"], "bounds":layers[name].getbbox(),
                 "visible_pixels":sum(a > 0 for a in layers[name].getchannel("A").get_flattened_data()),
                 "missing_pixels":missing_count, "layer":f"layers/{name}.png",
                 "status":"needs_inpaint" if missing_count else "visible_only_needs_review"}
        metadata.append(entry)
        if missing_count:
            job_dir = out / "jobs" / name
            job_dir.mkdir(exist_ok=True)
            source.save(job_dir / "input.png")
            full_mask = source_mask(missing, source.size, layout)
            full_mask.save(job_dir / "mask.png")
            default_prompt = f'chibi anime character {name}, continuous original material, same colors and illustration shading, repair occluded joint'
            if name.startswith(("thigh", "shin")):
                default_prompt = "chibi girl leg, continuous pale skin, smooth knee joint, same original anime illustration shading"
            elif name.startswith("foot"):
                default_prompt = "chibi girl ankle and black grey lace up boot, continuous shoe cuff, same original anime illustration shading"
            elif name == "head":
                default_prompt = "chibi girl neck and collar, pale skin, white collar, same original anime illustration shading"
            prompt = part.get("prompt", default_prompt)
            job = {"part":name, "input":f"jobs/{name}/input.png", "mask":f"jobs/{name}/mask.png",
                   "prompt":prompt, "seed":20261015, "steps":20, "status":"prepared",
                   "source_sha256":layout["source_sha256"], "crop":layout["crop"],
                   "downsample":layout["downsample"], "native_missing_pixels":missing_count}
            write_json(job_dir / "job.json", job)
            jobs.append(job)
    records = pose_checks(layers, original, layout, out)
    sheet(list(layers.items()), out / "layers.png", cols=5, scale=2)
    sheet([("original",original),("reassembled",rebuilt)], out / "comparison.png", cols=2, scale=4)
    missing_total = sum(p["missing_pixels"] for p in metadata)
    report = {"status":"needs_inpaint_and_pose_review", "reassembly_changed_rgba_pixels":mismatch,
              "parts":len(metadata), "inpaint_jobs":len(jobs), "missing_layer_pixels":missing_total,
              "joint_samples":len(records), "joint_samples_with_gaps":sum(r["gap_pixels"] > 0 for r in records),
              "max_joint_gap_pixels":max(r["gap_pixels"] for r in records),
              "visual_review":"pending", "runtime_ready":False,
              "notes":"可见像素分层已输出；未补齐层不满足正式关节验收。检查图只测单关节刚性摆位，不替代后续蒙皮和组合动作验收。"}
    write_json(out / "manifest.json", {"layout":layout, "layout_sha256":hashlib.sha256(layout_path.read_bytes()).hexdigest(),
               "layers":metadata, "jobs":jobs, "joint_checks":records})
    write_json(out / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layout", type=Path, default=ROOT / "assets/rig/q-chibi.layout.json")
    parser.add_argument("--out", type=Path, default=ROOT / "_tmp/q-parts")
    args = parser.parse_args()
    build(args.layout, args.out)
