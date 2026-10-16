"""Integrity and material regressions for the reproducible refined rig."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import shutil
import subprocess
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent

class RefinementTest(unittest.TestCase):
    def test_pipeline_contract(self):
        path = ROOT / 'tools/refine-q-rig.py'
        self.assertTrue(path.exists(), 'refinement pipeline is not implemented')
        spec = importlib.util.spec_from_file_location('refine', path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)/'bundle'
            mod.refine(out)
            first = {str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()}
            mod.refine(out)
            self.assertEqual(first, {str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()})
            report = json.loads((out/'refinement-report.json').read_text())
            self.assertEqual(0, report['rest_changed_rgba_pixels'])
            rest = Image.new('RGBA',(105,123))
            for layer in report['layers']:
                rest.alpha_composite(Image.open(out/layer['output']).convert('RGBA'))
            self.assertEqual(Image.open(out/'native.png').convert('RGBA').tobytes(),rest.tobytes())
            self.assertEqual(22, len(report['layers']))
            for layer in report['layers']:
                before = Image.open(out/layer['input']).convert('RGBA')
                after = Image.open(out/layer['output']).convert('RGBA')
                mask = Image.open(out/layer['mask']).convert('L')
                self.assertTrue(all(a == b for a,b,m in zip(before.get_flattened_data(), after.get_flattened_data(), mask.get_flattened_data()) if not m))
                self.assertEqual(0, layer['original_visible_changed_pixels'])
            self.assertTrue((out/'variants/handOpen.png').is_file())
            self.assertTrue((out/'variants/handOpen-mask.png').is_file())
            palm = Image.open(out/'variants/handOpen.png').convert('RGBA')
            palm_mask = Image.open(out/'variants/handOpen-mask.png').convert('L')
            self.assertTrue(all(pixel == (0,0,0,0) for pixel,m in zip(palm.get_flattened_data(),palm_mask.get_flattened_data()) if not m))
            self.assertGreater(sum(p[3]>0 for p in palm.get_flattened_data()),80)
            self.assertNotIn('handOpen', (out/'rig.properties').read_text().split('parts=')[1].splitlines()[0])
            self.assertEqual('comfyui_inpaint',report['variant']['method'])
            bad = json.loads((ROOT/'assets/rig/q-chibi.layout.json').read_text())
            bad['source_sha256'] = '0'*64
            layout = Path(temp)/'bad.json'
            layout.write_text(json.dumps(bad))
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                mod.refine(Path(temp)/'rejected', layout_path=layout)
            self.assertFalse((Path(temp)/'rejected').exists())
            generated = out/'generation/comfy/generated.png'
            generated.write_bytes(generated.read_bytes()+b'tamper')
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                mod.refine(Path(temp)/'rejected-generation',hand_source=out/'generation')
            self.assertFalse((Path(temp)/'rejected-generation').exists())

    def test_generation_manifest_must_cover_required_inputs(self):
        spec = importlib.util.spec_from_file_location('refine', ROOT/'tools/refine-q-rig.py')
        mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)/'source'
            shutil.copytree(ROOT/'assets/rig/q-chibi-refined/generation', source)
            (source/'artifact-hashes.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'generation manifest'):
                mod.refine(Path(temp)/'output', hand_source=source)
            self.assertFalse((Path(temp)/'output').exists())

    def test_cli_can_build_fresh_directory_from_locked_generation(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)/'fresh'
            result = subprocess.run([sys.executable, '-X', 'utf8', str(ROOT/'tools/refine-q-rig.py'),
                                     '--output', str(output)], capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((output/'rig.properties').exists())

if __name__ == '__main__':
    unittest.main()
