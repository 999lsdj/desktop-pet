"""逐项执行 Q 版隐藏区域补洞，保留证据并导出待验收的分层候选。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

from PIL import Image


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(root, selected):
    root.mkdir(parents=True,exist_ok=True)
    report_path = root/"inpaint-report.json"
    report_path.write_text(json.dumps({"status":"preflight","runtime_ready":False}),encoding="utf-8")
    try:
        _run(root,selected)
    except Exception as error:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report.update(status="failed",error=str(error))
        report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        raise


def _run(root, selected):
    pipeline = load_module("q_parts", "build-q-parts.py")
    comfy = load_module("comfy", "comfy-inpaint.py")
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    layout = manifest["layout"]
    pipeline.verify_source((pipeline.ROOT / layout["source"]).read_bytes(), layout)
    jobs = manifest["jobs"]
    names = {j["part"] for j in jobs}
    if selected and not set(selected) <= names:
        raise ValueError(f"未找到补洞任务：{set(selected)-names}")
    report = {"status":"running", "completed":[], "runtime_ready":False, "visual_review":"pending",
              "manifest_sha256":hashlib.sha256((root/"manifest.json").read_bytes()).hexdigest()}
    report_path = root / "inpaint-report.json"
    pipeline.write_json(report_path, report)
    layers = {p["name"]:Image.open(root / p["layer"]).convert("RGBA") for p in manifest["layers"]}
    dest = root / "filled-layers"
    dest.mkdir(exist_ok=True)
    layer_hashes = {}
    # 每次执行从可见层开始，选择少量任务时不能混入上次未指定的结果。
    for name, layer in layers.items():
        layer.save(dest / f"{name}.png")
        layer_hashes[name] = hashlib.sha256((dest / f"{name}.png").read_bytes()).hexdigest()
    try:
        for job in jobs:
            name = job["part"]
            if selected and name not in selected:
                continue
            report["current"] = name
            pipeline.write_json(report_path, report)
            out = root / "fills" / name
            print(f"补全部件 {name}", flush=True)
            prompt = job["prompt"]
            comfy.run(argparse.Namespace(out=out, image=root/job["input"], mask=root/job["mask"],
                smoke=False, prepare_only=False, prompt=prompt, seed=job["seed"], steps=job["steps"],
                timeout=300, downsample=layout["downsample"]))
            generated = Image.open(out/"result.png").convert("RGBA").crop(layout["crop"])
            generated = generated.resize(tuple(layout["native_size"]), Image.Resampling.BOX)
            mask = Image.open(root/"masks"/f"{name}-missing.png").convert("L")
            layers[name] = pipeline.fill_hidden(layers[name], generated, mask)
            layers[name].save(dest/f"{name}.png")
            layer_hashes[name] = hashlib.sha256((dest/f"{name}.png").read_bytes()).hexdigest()
            checks = json.loads((out/"report.json").read_text(encoding="utf-8"))
            report["completed"].append({"part":name,"full_size":checks["full_size"],
                                        "visual_review":"pending"})
            pipeline.write_json(report_path, report)
        original = Image.open(root/"original.png").convert("RGBA")
        rebuilt = pipeline.reassemble(layers.values(), original.size)
        rebuilt.save(root/"filled-reassembled.png")
        report["rest_changed_rgba_pixels"] = sum(a != b for a,b in zip(original.get_flattened_data(),rebuilt.get_flattened_data()))
        pipeline.sheet(list(layers.items()), root/"filled-layers.png", cols=5, scale=2)
        pipeline.sheet([("original",original),("filled candidate",rebuilt)],root/"filled-comparison.png",cols=2,scale=4)
        poses = root/"filled-poses"
        (poses/"poses").mkdir(parents=True,exist_ok=True)
        records = pipeline.pose_checks(layers,original,layout,poses)
        pipeline.write_json(poses/"checks.json", records)
        report["joint_samples"] = len(records)
        report["joint_samples_with_gaps"] = sum(r["gap_pixels"]>0 for r in records)
        report["max_joint_gap_pixels"] = max(r["gap_pixels"] for r in records)
        # 数量检查揭示被遮挡的整段衣发，而不是只盯住关节附近的小圆圈。
        report["flexible_outlines"] = {
            name:{"component_pixels":pipeline.component_sizes(layer.getchannel("A")),
                  "major_components":sum(n >= 4 for n in pipeline.component_sizes(layer.getchannel("A")))}
            for name,layer in layers.items() if name.startswith(("hair","coat","sleeve"))}
        report["filled_layers_sha256"] = layer_hashes
        report["status"] = "generated_pending_review"
        report["remaining_jobs"] = sorted(names-{j["part"] for j in report["completed"]})
        report.pop("current",None)
        pipeline.write_json(report_path,report)
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except Exception as error:
        report.update(status="failed", error=str(error))
        pipeline.write_json(report_path,report)
        raise


def export_candidates(root, destination):
    """保存小型候选包；完整 ComfyUI 输入与执行证据仍留在 root。"""
    pipeline = load_module("q_parts", "build-q-parts.py")
    report = json.loads((root/"inpaint-report.json").read_text(encoding="utf-8"))
    if report["status"] != "generated_pending_review" or report["remaining_jobs"]:
        raise ValueError("只允许导出当前全部补洞任务执行完毕的候选包")
    if report.get("manifest_sha256") != hashlib.sha256((root/"manifest.json").read_bytes()).hexdigest():
        raise ValueError("补洞报告与当前分层定义不一致，请重新生成")
    if report.get("rest_changed_rgba_pixels") != 0:
        raise ValueError("原姿态重组必须经过零 RGBA 差异验证，请重新生成")
    manifest = json.loads((root/"manifest.json").read_text(encoding="utf-8"))
    fingerprints = report.get("filled_layers_sha256")
    if not isinstance(fingerprints, dict) or set(fingerprints) != {p["name"] for p in manifest["layers"]}:
        raise ValueError("补洞报告缺少完整图层指纹，请重新生成")
    verified_layers = {}
    for part in manifest["layers"]:
        name = part["name"]
        data = (root/"filled-layers"/f"{name}.png").read_bytes()
        if hashlib.sha256(data).hexdigest() != fingerprints[name]:
            raise ValueError(f"补洞图层 SHA-256 与生成报告不一致：{name}，请重新生成")
        verified_layers[name] = data
    # 验证全部通过后才写目标；写入刚才验证的字节，避免检查后再次读取变化的源图。
    (destination/"layers").mkdir(parents=True,exist_ok=True)
    for name, data in verified_layers.items():
        (destination/"layers"/f"{name}.png").write_bytes(data)
    for name in ("report.json","inpaint-report.json","filled-comparison.png","filled-layers.png"):
        shutil.copy2(root/name,destination/name)
    shutil.copy2(root/"filled-poses"/"checks.json",destination/"pose-checks.json")
    shutil.copy2(root/"filled-poses"/"poses.png",destination/"poses.png")
    pipeline.write_json(destination/"candidate-manifest.json",{
        "status":"draft_pending_visual_and_motion_review", "runtime_ready":False,
        "source":manifest["layout"]["source"],"source_sha256":manifest["layout"]["source_sha256"],
        "layout_sha256":manifest["layout_sha256"], "crop":manifest["layout"]["crop"],
        "downsample":manifest["layout"]["downsample"], "canvas_size":manifest["layout"]["native_size"],
        "layers":[{"name":p["name"],"bone":p["bone"],"file":f'layers/{p["name"]}.png',
                   "sha256":hashlib.sha256((destination/"layers"/f'{p["name"]}.png').read_bytes()).hexdigest()}
                  for p in manifest["layers"]]})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parent.parent/"_tmp/q-parts")
    parser.add_argument("--parts",nargs="*",help="只执行指定部件；省略表示全部")
    parser.add_argument("--export",type=Path,help="全部生成完成后另存待验收候选包")
    parser.add_argument("--export-only",action="store_true",help="仅导出已有完整结果，不提交新的生成任务")
    args = parser.parse_args()
    if args.export_only and args.export is None:
        parser.error("--export-only 必须同时指定 --export")
    if not args.export_only:
        run(args.root,args.parts)
    if args.export:
        export_candidates(args.root,args.export)
