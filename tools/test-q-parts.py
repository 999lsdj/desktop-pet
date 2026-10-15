"""Q 版分层的验收约束：保留半透明边缘、追溯来源、只量关节缺口。"""
import importlib.util
from pathlib import Path
import unittest
import json
import tempfile

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


if __name__ == "__main__":
    unittest.main()
