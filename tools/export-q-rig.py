"""Export a portable, verified preview bundle; does not update runtime assets.

Coordinates are native absolute rest coordinates. Optional layout.rig_paths maps
hairNear/hairFar/coatNear/coatFar/sleeveNear/sleeveFar to {parent, points}.
These explicit paths are preview defaults, not an approved animation design.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(path, expected, label):
    if digest(path) != expected:
        raise ValueError(f"{label} SHA-256 mismatch: {path}")


def inside(root, relative):
    root = root.resolve()
    result = (root / relative).resolve()
    if not result.is_relative_to(root):
        raise ValueError(f"path escape: {relative}")
    return result


def rig_definition(layout):
    joints = {joint["name"]: joint["pivot"] for joint in layout["joints"]}
    hip = [(joints["hipNear"][i] + joints["hipFar"][i]) / 2 for i in range(2)]
    bones = {}

    def add(name, parent, start, end):
        if name in bones or (parent and parent not in bones):
            raise ValueError(f"bone order/duplicate: {name}")
        if len(start) != 2 or len(end) != 2 or not all(math.isfinite(v) for v in [*start, *end]):
            raise ValueError(f"invalid bone coordinates: {name}")
        bones[name] = (parent, *start, *end)

    add("root", "", hip, hip)
    add("torso", "root", hip, joints["neck"])
    add("head", "torso", joints["neck"], [joints["neck"][0], 10])
    groups = {"torso": ["torso"], "head": ["head"]}
    for side in ["Near", "Far"]:
        suffix = "" if side == "Near" else "Far"
        thigh, shin, foot = [name + side for name in ["thigh", "shin", "foot"]]
        add(thigh, "torso", joints["hip" + side], joints["knee" + side])
        add(shin, thigh, joints["knee" + side], joints["ankle" + side])
        add(foot, shin, joints["ankle" + side], [joints["ankle" + side][0], 121])
        groups["leg" + side] = [thigh, shin, foot]
        upper, fore, hand = [name + suffix for name in ["upperArm", "foreArm", "hand"]]
        add(upper, "torso", joints["shoulder" + side], joints["elbow" + side])
        add(fore, upper, joints["elbow" + side], joints["wrist" + side])
        wrist = joints["wrist" + side]
        add(hand, fore, wrist, [wrist[0] + (-3 if side == "Near" else 2), wrist[1] + 2])
        groups["arm" + side] = [upper, fore, hand]
    add("staff", "hand", joints["wristNear"], [31, 121])
    add("lantern", "staff", [8, 51], [8, 92])
    groups.update(staff=["staff"], lantern=["lantern"])
    paths = {
        "hairNear": {"parent": "head", "points": [[34, 40], [26, 62], [19, 91]]},
        "hairFar": {"parent": "head", "points": [[68, 39], [74, 66], [96, 88]]},
        "coatNear": {"parent": "torso", "points": [[39, 77], [32, 95], [9, 115]]},
        "coatFar": {"parent": "torso", "points": [[62, 77], [73, 96], [96, 114]]},
        "sleeveNear": {"parent": "foreArm", "points": [joints["elbowNear"], [30, 70], [25, 81]]},
        "sleeveFar": {"parent": "foreArmFar", "points": [joints["elbowFar"], [74, 71], [73, 79]]},
    }
    overrides = layout.get("rig_paths", {})
    if set(overrides) - set(paths):
        raise ValueError("unknown rig_paths entry")
    paths.update(overrides)
    for group, path in paths.items():
        points, parent = path["points"], path["parent"]
        if len(points) < 3:
            raise ValueError(f"flexible path needs at least two segments: {group}")
        groups[group] = []
        for i, (start, end) in enumerate(zip(points, points[1:])):
            name = f"{group}{i}"
            add(name, parent, start, end)
            groups[group].append(name)
            parent = name
    memberships = {bone: group for group, names in groups.items() for bone in names}
    memberships.update({group: group for group in paths})
    return bones, groups, memberships


def export_rig(layout_path, candidate, output, root=ROOT):
    layout_path, candidate, output = Path(layout_path), Path(candidate), Path(output)
    layout = json.loads(layout_path.read_text(encoding="utf-8-sig"))
    manifest_path = candidate / "candidate-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    verify(layout_path, manifest["layout_sha256"], "layout")
    if manifest["source_sha256"] != layout["source_sha256"]:
        raise ValueError("source SHA-256 mismatch between layout and manifest")
    if manifest["canvas_size"] != layout["native_size"]:
        raise ValueError("canvas size mismatch")
    native = inside(Path(root), layout["native"])
    verify(native, layout["native_sha256"], "native")
    verify(inside(Path(root), layout["source"]), layout["source_sha256"], "source")
    names = [part["name"] for part in layout["parts"]]
    if len(names) != len(set(names)) or any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", n) for n in names):
        raise ValueError("invalid/duplicate layer names")
    if names != [layer["name"] for layer in manifest["layers"]]:
        raise ValueError("layer order does not match layout")
    bones, groups, memberships = rig_definition(layout)
    lines = ["# Synthetic offscreen preview only; material and motion review still required.",
             "version=1", "runtime.ready=false", "canvas=" + ",".join(map(str, layout["native_size"])),
             "grid.step=4", "skin.falloff=16", "parts=" + ",".join(names),
             "bones=" + ",".join(bones), "layout.sha256=" + digest(layout_path),
             "manifest.sha256=" + digest(manifest_path), "reference.file=native.png",
             "reference.sha256=" + digest(native)]
    for name, values in bones.items():
        encoded = [values[0], *(f"{number:g}" for number in values[1:])]
        lines.append(f"bone.{name}=" + ",".join(encoded))
    sources = []
    for layer in manifest["layers"]:
        name = layer["name"]
        source = inside(candidate, layer["file"])
        verify(source, layer["sha256"], name)
        with Image.open(source) as image:
            if image.size != tuple(layout["native_size"]):
                raise ValueError(f"wrong layer dimensions: {name}")
        group = memberships[name]
        target = f"layers/{name}.png"
        lines.extend([f"layer.{name}.file={target}", f"layer.{name}.sha256={layer['sha256']}",
                      f"layer.{name}.group={group}", f"layer.{name}.bones=" + ",".join(groups[group])])
        sources.append((source, target))
    # All integrity checks finish before creating or modifying the output bundle.
    (output / "layers").mkdir(parents=True, exist_ok=True)
    for source, target in sources:
        destination = output / target
        if source.resolve() != destination.resolve():
            shutil.copyfile(source, destination)
    if native.resolve() != (output / "native.png").resolve():
        shutil.copyfile(native, output / "native.png")
    (output / "rig.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output / "rig.properties"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layout", type=Path, default=ROOT / "assets/rig/q-chibi.layout.json")
    parser.add_argument("--candidate", type=Path, default=ROOT / "assets/rig/q-chibi-draft")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(export_rig(args.layout, args.candidate, args.output))


if __name__ == "__main__":
    main()
