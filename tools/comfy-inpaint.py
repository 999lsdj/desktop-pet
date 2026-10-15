"""通过本机 ComfyUI 补洞，保留输入、蒙版、原始结果及蒙版外像素验证。"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
SERVER = "http://127.0.0.1:8188"


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def api(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(SERVER + path, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"ComfyUI {error.code}: {error.read().decode('utf-8')}") from None


def upload(path, name):
    boundary = uuid.uuid4().hex
    data = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="{name}"\r\n'
        'Content-Type: image/png\r\n\r\n'
    ).encode() + path.read_bytes() + (
        f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="overwrite"\r\n\r\ntrue'
        f'\r\n--{boundary}--\r\n'
    ).encode()
    request = urllib.request.Request(SERVER + "/upload/image", data=data, headers={
        "Content-Type": "multipart/form-data; boundary=" + boundary,
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    return "/".join(filter(None, [result.get("subfolder"), result["name"]]))


def composite_and_check(original, generated, mask):
    original = original.convert("RGBA")
    generated = generated.convert("RGBA")
    mask = mask.convert("L")
    if original.size != generated.size or original.size != mask.size:
        raise ValueError("原图、生成图与蒙版尺寸必须一致，不能静默缩放")
    result = Image.composite(generated, original, mask)
    return result, pixel_changes(original, result, mask)


def pixel_changes(original, result, mask):
    before = list(original.get_flattened_data())
    after = list(result.get_flattened_data())
    weights = list(mask.get_flattened_data())
    outside = sum(a != b for a, b, m in zip(before, after, weights) if m == 0)
    changed = sum(a != b for a, b, m in zip(before, after, weights) if m > 0)
    if outside:
        raise AssertionError(f"蒙版外 {outside} 个像素被修改")
    return {"outside_changed_pixels": outside, "inside_changed_pixels": changed,
            "mask_pixels": sum(m > 0 for m in weights)}


def downsample_result(original, result, mask, factor, crop):
    original, result, mask = (im.crop(crop) for im in (original, result, mask))
    size = (original.width // factor, original.height // factor)
    small_original = original.resize(size, Image.Resampling.BOX)
    small_result = result.resize(size, Image.Resampling.BOX)
    # 一格内只要有待补像素，该格就属于补洞影响范围，不能用最近邻把细蒙版丢掉。
    small_mask = Image.new("L", size)
    for y in range(size[1]):
        for x in range(size[0]):
            block = mask.crop((x * factor, y * factor, (x + 1) * factor, (y + 1) * factor))
            if block.getextrema()[1] > 0:
                small_mask.putpixel((x, y), 255)
    return small_result, pixel_changes(small_original, small_result, small_mask)


def prepare(args):
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # 每次执行覆盖固定证据文件；旧成功报告不能替失败的新执行背书。
    save_json(out / "report.json", {"status": "prepared", "source": str(args.image.resolve())})
    original = Image.open(args.image).convert("RGBA")
    width, height = original.size
    if width % 8 or height % 8:
        raise ValueError("输入宽高须为 8 的倍数，请先准备有坐标记录的局部画布")
    if args.downsample < 1 or width % args.downsample or height % args.downsample:
        raise ValueError("降采样倍率须为正整数且整除输入宽高")
    if args.smoke:
        if original.size != (800, 800):
            raise ValueError("冒烟样例使用已确认的 800×800 Q 版原图")
        mask = Image.new("L", original.size)
        ImageDraw.Draw(mask).ellipse((309, 376, 331, 400), fill=255)
    else:
        if args.mask is None:
            raise ValueError("请指定 --mask（白色补洞、黑色保留），或使用 --smoke")
        mask = Image.open(args.mask).convert("L")
    if mask.size != original.size or mask.getbbox() is None:
        raise ValueError("蒙版必须与原图同尺寸且包含待补区域")
    original.save(out / "input.png")
    mask.save(out / "mask.png")
    # 给生成模型一个不透明画布；最终结果仍按原始 RGBA 图严格合成。
    model_input = Image.new("RGBA", original.size, (245, 245, 245, 255))
    model_input.alpha_composite(original)
    model_input.convert("RGB").save(out / "model-input.png")
    marked = Image.composite(Image.new("RGBA", original.size, (255, 60, 80, 255)), original, mask)
    marked.save(out / "mask-preview.png")
    graph = json.loads((ROOT / "tools/workflows/sdxl-inpaint-api.json").read_text(encoding="utf-8"))
    graph["4"]["inputs"]["text"] = args.prompt
    graph["7"]["inputs"].update(seed=args.seed, steps=args.steps)
    save_json(out / "workflow.json", graph)
    save_json(out / "request.json", {
        "source": str(args.image.resolve()), "source_sha256": hashlib.sha256(args.image.read_bytes()).hexdigest(),
        "size": original.size, "mask_bbox": mask.getbbox(), "seed": args.seed,
        "steps": args.steps, "smoke": args.smoke,
    })
    return out, original, mask, graph


def run(args):
    out, original, mask, graph = prepare(args)
    if args.prepare_only:
        print(f"已准备输入与工作流：{out}", flush=True)
        return
    stats = api("/system_stats")
    model_info = api("/object_info/CheckpointLoaderSimple")
    names = model_info["CheckpointLoaderSimple"]["input"]["required"]["ckpt_name"][0]
    if graph["1"]["inputs"]["ckpt_name"] not in names:
        raise RuntimeError("ComfyUI 未发现 SDXL 补洞模型")
    run_id = uuid.uuid4().hex
    graph["2"]["inputs"]["image"] = upload(out / "model-input.png", f"pet-{run_id}-input.png")
    graph["3"]["inputs"]["image"] = upload(out / "mask.png", f"pet-{run_id}-mask.png")
    graph["9"]["inputs"]["filename_prefix"] = f"pet-inpaint/{run_id}"
    save_json(out / "workflow.json", graph)
    started = time.monotonic()
    queued = api("/prompt", {"prompt": graph, "client_id": run_id})
    save_json(out / "queued.json", queued)
    prompt_id = queued["prompt_id"]
    print(f"已提交补洞任务 {prompt_id}", flush=True)
    while time.monotonic() - started < args.timeout:
        history = api("/history/" + prompt_id).get(prompt_id)
        if history:
            save_json(out / "history.json", history)
            if history.get("status", {}).get("status_str") == "error":
                raise RuntimeError("ComfyUI 执行失败，详情见 history.json")
            if history.get("status", {}).get("completed"):
                break
        time.sleep(2)
    else:
        raise TimeoutError(f"等待超过 {args.timeout} 秒；队列任务可能仍运行，见 queued.json")
    descriptor = history["outputs"]["9"]["images"][0]
    with urllib.request.urlopen(SERVER + "/view?" + urllib.parse.urlencode(descriptor), timeout=30) as response:
        raw = response.read()
    (out / "generated.png").write_bytes(raw)
    generated = Image.open(io.BytesIO(raw))
    result, checks = composite_and_check(original, generated, mask)
    result.save(out / "result.png")
    if checks["inside_changed_pixels"] == 0:
        raise RuntimeError("蒙版内没有任何变化，不能算补洞成功")
    # 同样检查最终整数降采样尺寸，避免只在生成分辨率验收。
    factor = 5 if args.smoke else args.downsample
    if args.smoke:
        left, top, right, bottom = original.getchannel("A").getbbox()
        crop = (left, top, left + (right - left) // factor * factor, top + (bottom - top) // factor * factor)
    else:
        crop = (0, 0, original.width, original.height)
    small_result, native_checks = downsample_result(original, result, mask, factor, crop)
    small_result.save(out / "result-native.png")
    sheet = Image.new("RGB", (original.width * 3, original.height + 24), (225, 225, 225))
    draw = ImageDraw.Draw(sheet)
    for index, (label, im) in enumerate([("original", original), ("mask", Image.open(out / "mask-preview.png")), ("result", result)]):
        draw.text((index * original.width + 8, 5), label, fill="black")
        sheet.paste(im, (index * original.width, 24), im)
    sheet.save(out / "comparison.png")
    save_json(out / "report.json", {"status": "passed", "prompt_id": prompt_id,
        "elapsed_seconds": round(time.monotonic() - started, 2), "full_size": checks,
        "native_size": small_result.size, "native_checks": native_checks, "downsample": factor, "crop": crop,
        "devices": stats.get("devices"), "visual_review": "pending"})
    print(json.dumps(checks), flush=True)
    print(f"结果与验证报告：{out}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, default=ROOT / "assets/像素角色/master-transparent-800.png")
    parser.add_argument("--mask", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "_tmp/comfy/smoke")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--seed", type=int, default=20261015)
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--downsample", type=int, default=1)
    parser.add_argument("--prompt", default="chibi pixel art character, white coat sleeve, delicate grey folds, consistent pixel art shading, same clothing")
    run(parser.parse_args())
