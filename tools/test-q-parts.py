"""Q 版分层的验收约束：保留半透明边缘、追溯来源、只量关节缺口。"""
import importlib.util
import contextlib
import hashlib
import io
from pathlib import Path
import unittest
import json
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

MODULE = Path(__file__).with_name("build-q-parts.py")


class LayerContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if MODULE.exists():
            spec = importlib.util.spec_from_file_location("q_parts", MODULE)
            cls.pipeline = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.pipeline)

    def setUp(self):
        self.assertTrue(MODULE.exists(), "Q 版轮廓分层产线尚未实现")

    def test_exclusive_layers_preserve_translucent_pixels(self):
        original = Image.new("RGBA", (8, 8), (50, 100, 150, 127))
        parts = [{"name": "body", "polygons": [[[0, 0], [7, 0], [7, 7], [0, 7]]]},
                 {"name": "arm", "polygons": [[[3, 0], [7, 0], [7, 7], [3, 7]]]}]
        layers, _ = self.pipeline.split_visible(original, parts)
        result = self.pipeline.reassemble(layers.values(), original.size)
        self.assertEqual(original.tobytes(), result.tobytes())
        self.assertEqual(layers["body"].getpixel((4, 4))[3], 0)
        self.assertEqual(layers["arm"].getpixel((4, 4))[3], 127)

    def test_vacated_silhouette_is_not_a_joint_hole(self):
        before = Image.new("L", (20, 20), 255)
        after = Image.new("L", (20, 20), 255)
        after.putpixel((19, 19), 0)
        self.assertEqual(self.pipeline.joint_gap(before, after, (10, 10), 3), 0)
        after.putpixel((10, 10), 0)
        self.assertEqual(self.pipeline.joint_gap(before, after, (10, 10), 3), 1)

    def test_coordinate_round_trip(self):
        layout = {"crop": [139, 81, 664, 696], "downsample": 5}
        mask = Image.new("L", (105, 123))
        mask.putpixel((19, 66), 255)
        full = self.pipeline.source_mask(mask, (800, 800), layout)
        self.assertEqual(full.getbbox(), (234, 411, 239, 416))
        self.assertEqual(full.crop(layout["crop"]).resize(mask.size, Image.Resampling.NEAREST).tobytes(), mask.tobytes())

    def test_wrong_source_rejected_before_export(self):
        with self.assertRaisesRegex(ValueError, "来源"):
            self.pipeline.verify_source(b"different artwork", {"source_sha256": "0" * 64})

    def test_hidden_fill_preserves_existing_rgba(self):
        layer = Image.new("RGBA", (8, 8))
        layer.putpixel((2, 2), (33, 66, 99, 127))
        generated = Image.new("RGBA", (8, 8), (200, 150, 100, 255))
        mask = Image.new("L", (8, 8))
        mask.putpixel((3, 2), 255)
        result = self.pipeline.fill_hidden(layer, generated, mask)
        self.assertEqual(result.getpixel((2, 2)), layer.getpixel((2, 2)))
        self.assertEqual(result.getpixel((3, 2)), generated.getpixel((3, 2)))
        self.assertEqual(result.getpixel((0, 0)), (0, 0, 0, 0))
        mask.putpixel((2, 2), 255)
        with self.assertRaisesRegex(ValueError, "已有"):
            self.pipeline.fill_hidden(layer, generated, mask)

    def test_padding_only_behind_fully_opaque_front_layer(self):
        target = Image.new("L", (3, 1), 255)
        visible = Image.new("L", (3, 1))
        front = Image.new("RGBA", (3, 1))
        front.putpixel((0, 0), (10, 20, 30, 255))
        front.putpixel((1, 0), (10, 20, 30, 127))
        mask = self.pipeline.concealed_padding(target, visible, [front])
        self.assertEqual(list(mask.get_flattened_data()), [255, 0, 0])

    def test_moving_outer_edge_is_not_an_internal_joint_hole(self):
        before = Image.new("L", (20, 20), 255)
        before.putpixel((13, 10), 0)
        after = before.copy()
        after.putpixel((12, 10), 0)
        self.assertEqual(self.pipeline.joint_gap(before, after, (10, 10), 3), 0)
        after.putpixel((10, 10), 0)
        self.assertEqual(self.pipeline.joint_gap(before, after, (10, 10), 3), 1)

    def test_failed_preflight_invalidates_old_success(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            report = out / "inpaint-report.json"
            report.write_text(json.dumps({"status":"generated_pending_review","remaining_jobs":[]}))
            with self.assertRaises(FileNotFoundError):
                self.pipeline.build(out / "missing-layout.json", out)
            self.assertEqual(json.loads(report.read_text())["status"], "not_run_for_current_build")
            self.assertEqual(json.loads((out/"report.json").read_text())["status"], "failed")
            spec = importlib.util.spec_from_file_location("q_inpaint", MODULE.with_name("inpaint-q-parts.py"))
            runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
            report.write_text(json.dumps({"status":"generated_pending_review","remaining_jobs":[]}))
            with self.assertRaises(FileNotFoundError):
                runner.run(out, None)
            self.assertEqual(json.loads(report.read_text())["status"], "failed")

    def test_visible_materials_do_not_follow_the_wrong_part(self):
        root = MODULE.parent.parent
        layout = json.loads((root/'assets/rig/q-chibi.layout.json').read_text(encoding='utf-8'))
        original = Image.open(root/layout['native']).convert('RGBA')
        _, owners = self.pipeline.split_visible(original, layout['parts'])
        # 原图可见的蓝色发梢不能被杖的矩形余量带走；衣物与腿也不能混绑。
        samples = {'hairNear':[(21,85),(21,90),(32,52)], 'hairFar':[(69,54)],
                   'staff':[(24,85),(25,31),(25,37),(26,42)], 'lantern':[(18,70),(18,82),(18,90),(20,76)],
                   'coatNear':[(29,84),(37,96)],
                   'sleeveNear':[(37,65)], 'sleeveFar':[(73,74)],
                   'handFar':[(77,79)], 'thighNear':[(42,90)], 'thighFar':[(58,92)]}
        for name, points in samples.items():
            for point in points:
                with self.subTest(part=name, point=point):
                    self.assertEqual(owners[name].getpixel(point), 255)

    def test_occluded_hair_and_coat_have_reconstruction_targets(self):
        root = MODULE.parent.parent
        layout = json.loads((root/'assets/rig/q-chibi.layout.json').read_text(encoding='utf-8'))
        for name in ('hairNear','hairFar','coatNear','coatFar','sleeveNear','sleeveFar'):
            part = next(p for p in layout['parts'] if p['name'] == name)
            with self.subTest(part=name):
                self.assertTrue(part.get('reconstruct'), '完整衣发轮廓需要显式隐藏重建目标')

    def test_outline_audit_detects_disconnected_material(self):
        self.assertTrue(hasattr(self.pipeline, 'component_sizes'), '缺少衣发断段检查')
        alpha = Image.new('L', (8, 8))
        for point in [(1,1),(2,2),(2,3),(6,6),(6,7)]:
            alpha.putpixel(point, 255)
        self.assertEqual(self.pipeline.component_sizes(alpha), [3,2])
        alpha.putpixel((0,7), 1)
        self.assertEqual(self.pipeline.component_sizes(alpha), [3,2])

    def test_material_context_excludes_other_parts_and_keeps_visible_pixels(self):
        self.assertTrue(hasattr(self.pipeline, 'material_context'), '缺少独立材质上下文')
        source = Image.new('RGBA', (8,8), (240,160,100,255))
        source.putpixel((3,3), (30,70,120,255))
        visible = Image.new('L', (8,8)); visible.putpixel((3,3),255)
        target = Image.new('L', (8,8)); target.paste(255,(2,2,6,6))
        result = self.pipeline.material_context(source, visible, target,
                                                {'crop':[0,0,8,8], 'native_size':[8,8]})
        self.assertEqual(result.getpixel((3,3)),source.getpixel((3,3)))
        self.assertEqual(result.getpixel((4,4)),(30,70,120,255))
        self.assertNotIn((240,160,100,255),list(result.get_flattened_data()))


class CandidateExportContracts(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("q_inpaint", MODULE.with_name("inpaint-q-parts.py"))
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "run"
        self.destination = Path(self.temp.name) / "candidate"
        for name in ("layers", "filled-layers", "filled-poses/poses"):
            (self.root / name).mkdir(parents=True, exist_ok=True)
        torso = Image.new("RGBA", (8, 8), (12, 34, 56, 255))
        hair = Image.new("RGBA", (8, 8))
        hair.putpixel((4, 4), (30, 70, 120, 255))
        original = torso.copy()
        original.alpha_composite(hair)
        for name, layer in (("torso", torso), ("hairNear", hair)):
            layer.save(self.root / "layers" / f"{name}.png")
            layer.save(self.root / "filled-layers" / f"{name}.png")
        for name in ("source.png", "original.png", "filled-comparison.png", "filled-layers.png", "filled-poses/poses.png"):
            original.save(self.root / name)
        layout = {"source": str(self.root / "source.png"), "source_sha256": self.sha(self.root / "source.png"),
                  "crop": [0, 0, 8, 8], "downsample": 1, "native_size": [8, 8],
                  "joints": [{"name": "neck", "pivot": [4, 4], "angles": [-5, 5],
                              "moves": ["hairNear"], "radius": 1}]}
        manifest = {"layout": layout, "layout_sha256": "controlled-layout",
                    "layers": [{"name": name, "bone": name, "layer": f"layers/{name}.png"}
                               for name in ("torso", "hairNear")], "jobs": []}
        (self.root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (self.root / "report.json").write_text("{}", encoding="utf-8")
        (self.root / "filled-poses/checks.json").write_text("[]", encoding="utf-8")
        self.report = {"status": "generated_pending_review", "remaining_jobs": [],
                       "manifest_sha256": self.sha(self.root / "manifest.json"), "rest_changed_rgba_pixels": 0,
                       "filled_layers_sha256": {name: self.sha(self.root / "filled-layers" / f"{name}.png")
                                                for name in ("torso", "hairNear")}}
        self.save_report()

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def save_report(self):
        (self.root / "inpaint-report.json").write_text(json.dumps(self.report), encoding="utf-8")

    def test_export_accepts_layers_bound_to_the_zero_difference_report(self):
        self.runner.export_candidates(self.root, self.destination)
        candidate = json.loads((self.destination / "candidate-manifest.json").read_text(encoding="utf-8"))
        for layer in candidate["layers"]:
            self.assertEqual(layer["sha256"], self.report["filled_layers_sha256"][layer["name"]])
            self.assertEqual(self.sha(self.destination / layer["file"]), layer["sha256"])

    def test_export_rejects_nonzero_or_missing_rest_difference_before_output(self):
        for value in (1, None):
            with self.subTest(difference=value):
                self.report["rest_changed_rgba_pixels"] = value
                self.save_report()
                with self.assertRaisesRegex(ValueError, "重组"):
                    self.runner.export_candidates(self.root, self.destination)
                self.assertFalse(self.destination.exists())

    def test_export_requires_fingerprints_for_every_layer(self):
        for fingerprints in (None, {}, {"torso": self.report["filled_layers_sha256"]["torso"]}):
            with self.subTest(fingerprints=fingerprints):
                self.report["filled_layers_sha256"] = fingerprints
                self.save_report()
                with self.assertRaisesRegex(ValueError, "指纹.*重新生成"):
                    self.runner.export_candidates(self.root, self.destination)
                self.assertFalse(self.destination.exists())

    def test_export_rejects_a_layer_modified_after_generation_before_output(self):
        Image.new("RGBA", (8, 8), (255, 0, 0, 255)).save(self.root / "filled-layers/hairNear.png")
        with self.assertRaisesRegex(ValueError, "SHA-256.*hairNear"):
            self.runner.export_candidates(self.root, self.destination)
        self.assertFalse(self.destination.exists())

    def test_generation_records_fingerprints_for_all_filled_layers(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.runner.run(self.root, None)
        report = json.loads((self.root / "inpaint-report.json").read_text(encoding="utf-8"))
        self.assertIn("filled_layers_sha256", report)
        self.assertEqual(set(report["filled_layers_sha256"]), {"torso", "hairNear"})
        for name, fingerprint in report["filled_layers_sha256"].items():
            self.assertEqual(fingerprint, self.sha(self.root / "filled-layers" / f"{name}.png"))
        self.runner.export_candidates(self.root, self.destination)

    def test_completed_inpaint_updates_the_layer_fingerprint(self):
        manifest_path = self.root / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["jobs"] = [{"part": "hairNear", "prompt": "blue hair", "input": "source.png",
                             "mask": "masks/hairNear-missing.png", "seed": 1, "steps": 1}]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        (self.root / "masks").mkdir()
        mask = Image.new("L", (8, 8))
        mask.putpixel((1, 1), 255)
        mask.save(self.root / "masks/hairNear-missing.png")
        previous = self.sha(self.root / "filled-layers/hairNear.png")

        # 只替换外部 ComfyUI 请求；合成、报告、指纹和导出验证都走实际代码。
        def generate(args):
            args.out.mkdir(parents=True)
            Image.new("RGBA", (8, 8), (10, 20, 150, 255)).save(args.out / "result.png")
            (args.out / "report.json").write_text(json.dumps({"full_size": {
                "outside_changed_pixels": 0, "inside_changed_pixels": 1, "mask_pixels": 1}}), encoding="utf-8")

        real_loader = self.runner.load_module
        def load(name, filename):
            return SimpleNamespace(run=generate) if name == "comfy" else real_loader(name, filename)

        with patch.object(self.runner, "load_module", side_effect=load), contextlib.redirect_stdout(io.StringIO()):
            self.runner.run(self.root, None)
        report = json.loads((self.root / "inpaint-report.json").read_text(encoding="utf-8"))
        actual = self.sha(self.root / "filled-layers/hairNear.png")
        self.assertNotEqual(actual, previous)
        self.assertEqual(report["filled_layers_sha256"]["hairNear"], actual)
        self.assertEqual(report["rest_changed_rgba_pixels"], 1)
        with self.assertRaisesRegex(ValueError, "重组"):
            self.runner.export_candidates(self.root, self.destination)


if __name__ == "__main__":
    unittest.main()
