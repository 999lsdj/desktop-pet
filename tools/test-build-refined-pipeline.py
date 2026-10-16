"""End-to-end cached-model replay contract; no live ComfyUI dependency."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent

class PipelineTest(unittest.TestCase):
    def test_incomplete_cache_rejected_before_output(self):
        import copy,json,shutil
        spec=importlib.util.spec_from_file_location('pipeline',ROOT/'tools/build-refined-pipeline.py')
        pipeline=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pipeline)
        original=json.loads((pipeline.CACHE/'manifest.json').read_text(encoding='utf-8'))
        cases=[]
        missing=copy.deepcopy(original)
        del missing['inpaint_jobs'][0]['artifacts']['workflow.json']
        del missing['artifacts']['inpaint/torso/workflow.json']
        cases.append(('missing-task-evidence',missing))
        for alias in ['layout.json','split-manifest.json','inpaint-report.json','candidate-manifest.json']:
            missing=copy.deepcopy(original)
            del missing['artifacts'][alias]
            cases.append(('missing-'+alias,missing))
        duplicate=copy.deepcopy(original)
        duplicate['inpaint_jobs'][1]=copy.deepcopy(duplicate['inpaint_jobs'][0])
        cases.append(('duplicate-job',duplicate))
        unknown=copy.deepcopy(original)
        unknown['inpaint_jobs'][0]['name']='unknownMaterial'
        cases.append(('unknown-job',unknown))
        wrongref=copy.deepcopy(original)
        wrongref['inpaint_jobs'][0]['artifacts']['workflow.json']='inpaint/hairNear/workflow.json'
        cases.append(('wrong-task-reference',wrongref))
        hand=copy.deepcopy(original)
        del hand['artifacts']['hand/comfy/input.png']
        cases.append(('missing-hand-evidence',hand))
        with tempfile.TemporaryDirectory() as temp:
            cache=Path(temp)/'cache'
            shutil.copytree(pipeline.CACHE,cache)
            for name,manifest in cases:
                with self.subTest(name=name):
                    (cache/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
                    out=Path(temp)/name
                    with self.assertRaises(ValueError):
                        pipeline.build(out,cache=cache)
                    self.assertFalse(out.exists(),'preflight rejection must precede output creation')

    def test_cached_rebuild_contract(self):
        script = ROOT/'tools/build-refined-pipeline.py'
        self.assertTrue(script.exists(), 'full provenance replay pipeline is not implemented')
        spec = importlib.util.spec_from_file_location('pipeline',script)
        pipeline = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pipeline)
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)/'new'
            pipeline.DRAFT = Path(temp)/'missing-draft'
            pipeline.HAND = Path(temp)/'missing-hand'
            from unittest.mock import patch
            with patch('urllib.request.urlopen',side_effect=AssertionError('model/server access is forbidden in replay')):
                report = pipeline.build(out)
            self.assertEqual(16,len(report['inpaint_jobs']))
            self.assertEqual(22,len(report['refined_layers']))
            self.assertEqual(0,report['rest_changed_rgba_pixels'])
            self.assertFalse(report['new_model_inference'])
            import json
            expected=json.loads((ROOT/'assets/rig/q-chibi-refined/refinement-report.json').read_text(encoding='utf-8'))
            for layer in expected['layers']:
                self.assertEqual(layer['output_sha256'],pipeline.sha(out/'refined'/layer['output']))
            self.assertEqual(expected['variant']['sha256'],pipeline.sha(out/'refined/variants/handOpen.png'))
            first = {str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()}
            pipeline.build(out)
            self.assertEqual(first,{str(p.relative_to(out)):p.read_bytes() for p in out.rglob('*') if p.is_file()})
            another = Path(temp)/'another'
            pipeline.build(another)
            self.assertEqual(first,{str(p.relative_to(another)):p.read_bytes() for p in another.rglob('*') if p.is_file()})
            import shutil,json
            cache = Path(temp)/'cache'
            shutil.copytree(pipeline.CACHE,cache)
            manifest=json.loads((cache/'manifest.json').read_text(encoding='utf-8'))
            blob = cache/next(iter(manifest['artifacts'].values()))['blob']
            blob.write_bytes(blob.read_bytes()+b'tamper')
            with self.assertRaisesRegex(ValueError,'SHA-256 mismatch'):
                pipeline.build(Path(temp)/'rejected',cache=cache)
            self.assertFalse((Path(temp)/'rejected').exists())

if __name__=='__main__': unittest.main()
