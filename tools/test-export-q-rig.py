"""Behavior checks for the portable, hash-verified Java preview bundle."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from PIL import Image

MODULE = Path(__file__).with_name("export-q-rig.py")
ROOT = MODULE.parent.parent


class ExportContracts(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), "hash-verified rig exporter is missing")
        spec = importlib.util.spec_from_file_location("export_q_rig", MODULE)
        self.exporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.exporter)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.candidate = self.root / "candidate"
        (self.candidate / "layers").mkdir(parents=True)
        self.layout = json.loads((ROOT / "assets/rig/q-chibi.layout.json").read_text(encoding="utf-8-sig"))
        Image.new("RGBA", (105, 123), (12, 34, 56, 127)).save(self.root / "native.png")
        native_hash = self.sha(self.root / "native.png")
        self.layout.update(native="native.png", native_sha256=native_hash,
                           source="native.png", source_sha256=native_hash)
        next(j for j in self.layout["joints"] if j["name"] == "elbowNear")["pivot"] = [34, 64]
        self.layout_path = self.root / "layout.json"
        self.layout_path.write_text(json.dumps(self.layout), encoding="utf-8")
        layers = []
        for part in self.layout["parts"]:
            file = "layers/" + part["name"] + ".png"
            Image.new("RGBA", (105, 123), (1, 2, 3, 255)).save(self.candidate / file)
            layers.append(dict(name=part["name"], file=file, sha256=self.sha(self.candidate / file)))
        self.manifest = dict(layout_sha256=self.sha(self.layout_path), source_sha256=native_hash,
                             canvas_size=[105, 123], layers=layers, runtime_ready=False)
        self.save_manifest()

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def save_manifest(self):
        (self.candidate / "candidate-manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def export(self):
        self.exporter.export_rig(self.layout_path, self.candidate, self.root / "output", self.root)
        return dict(line.split("=", 1) for line in (self.root / "output/rig.properties")
                    .read_text(encoding="utf-8").splitlines() if line and not line.startswith("#"))

    def test_joint_coordinates_layer_order_and_material_isolation(self):
        p = self.export()
        self.assertEqual(p["bone.foreArm"], "upperArm,34,64,25,69")
        self.assertEqual(p["parts"].split(","), [v["name"] for v in self.layout["parts"]])
        self.assertEqual(p["layer.staff.bones"], "staff")
        self.assertEqual(p["layer.shinNear.bones"], p["layer.thighNear.bones"])
        self.assertEqual(p["layer.footNear.bones"], p["layer.thighNear.bones"])
        self.assertEqual(p["layer.thighNear.skinning"], "joint-chain")
        self.assertEqual(p["layer.shinFar.skinning"], "joint-chain")
        self.assertEqual(p["layer.staff.skinning"], "distance")
        self.assertNotIn("thigh", p["layer.hairNear.bones"])
        seen = set()
        for bone in p["bones"].split(","):
            parent = p["bone." + bone].split(",")[0]
            self.assertTrue(not parent or parent in seen)
            seen.add(bone)
        self.assertEqual(self.sha(self.root / "output/native.png"), p["reference.sha256"])
        before = (self.root / "output/rig.properties").read_bytes()
        self.export()
        self.assertEqual(before, (self.root / "output/rig.properties").read_bytes())

    def test_tampered_layer_rejected_before_any_output(self):
        (self.candidate / self.manifest["layers"][0]["file"]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.export()
        self.assertFalse((self.root / "output").exists())

    def test_stale_layout_and_path_escape_rejected(self):
        self.manifest["layout_sha256"] = "0" * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "layout.*SHA-256"):
            self.export()
        self.manifest["layout_sha256"] = self.sha(self.layout_path)
        self.manifest["layers"][0]["file"] = "../native.png"
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "escape"):
            self.export()


if __name__ == "__main__":
    unittest.main()
