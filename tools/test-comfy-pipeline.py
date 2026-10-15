"""检查下载短读恢复及补洞合成的像素保护边界。"""

import importlib.util
from http.client import IncompleteRead
from pathlib import Path
import unittest
from unittest.mock import patch

from PIL import Image


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


inpaint = load_module("inpaint", "comfy-inpaint.py")
download = load_module("download", "download-comfy-model.py")


class PixelProtection(unittest.TestCase):
    def test_unmasked_rgba_survives_unrelated_generated_changes(self):
        original = Image.new("RGBA", (3, 1))
        original.putdata([(22, 33, 44, 0), (5, 6, 7, 128), (8, 9, 10, 255)])
        generated = Image.new("RGB", (3, 1), (200, 100, 50))
        mask = Image.new("L", (3, 1))
        mask.putdata([0, 255, 0])
        result, report = inpaint.composite_and_check(original, generated, mask)
        self.assertEqual(result.getpixel((0, 0)), (22, 33, 44, 0))
        self.assertEqual(result.getpixel((2, 0)), (8, 9, 10, 255))
        self.assertEqual(result.getpixel((1, 0)), (200, 100, 50, 255))
        self.assertEqual(report["outside_changed_pixels"], 0)
        self.assertEqual(report["inside_changed_pixels"], 1)

    def test_misaligned_generated_size_is_rejected(self):
        with self.assertRaises(ValueError):
            inpaint.composite_and_check(Image.new("RGBA", (8, 8)), Image.new("RGB", (7, 8)), Image.new("L", (8, 8)))

    def test_transparent_hole_becomes_opaque_only_inside_mask(self):
        original = Image.new("RGBA", (2, 1), (0, 0, 0, 0))
        mask = Image.new("L", (2, 1))
        mask.putpixel((1, 0), 255)
        result, _ = inpaint.composite_and_check(original, Image.new("RGB", (2, 1), "white"), mask)
        self.assertEqual(result.getpixel((0, 0))[3], 0)
        self.assertEqual(result.getpixel((1, 0))[3], 255)

    def test_thin_mask_survives_final_size_validation(self):
        original = Image.new("RGBA", (8, 8), "black")
        result = original.copy()
        result.putpixel((3, 3), (255, 255, 255, 255))
        mask = Image.new("L", (8, 8))
        mask.putpixel((3, 3), 255)
        small, report = inpaint.downsample_result(original, result, mask, 4, (0, 0, 8, 8))
        self.assertEqual(small.size, (2, 2))
        self.assertEqual(report["mask_pixels"], 1)
        self.assertEqual(report["outside_changed_pixels"], 0)
        self.assertEqual(report["inside_changed_pixels"], 1)


class DownloadRecovery(unittest.TestCase):
    def test_incomplete_http_body_is_retried(self):
        with patch.object(download, "request_range") as request, patch.object(download.time, "sleep"):
            request.return_value.__enter__.return_value.read.side_effect = [IncompleteRead(b"a", 2), b"bc"]
            self.assertEqual(download.fetch_chunk(0, 2), b"abc")
            self.assertEqual(request.call_count, 2)
            self.assertEqual(request.call_args_list[1].args, (1, 2))

    def test_early_eof_resumes_after_received_bytes(self):
        with patch.object(download, "request_range") as request, patch.object(download.time, "sleep"):
            request.return_value.__enter__.return_value.read.side_effect = [b"a", b"", b"bc"]
            self.assertEqual(download.fetch_chunk(0, 2), b"abc")
            self.assertEqual(request.call_args_list[1].args, (1, 2))


if __name__ == "__main__":
    unittest.main()
