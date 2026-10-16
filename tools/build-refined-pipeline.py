"""Replay checked-in ComfyUI caches from original split through final 22-layer refinement.

Normal command never uses _tmp or contacts a model server. --capture imports an
existing successful historical inference run into a deduplicated provenance cache.
"""
import argparse
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
CACHE=ROOT/'assets/rig/q-chibi-provenance'
LAYOUT=ROOT/'assets/rig/q-chibi.layout.json'
DRAFT=ROOT/'assets/rig/q-chibi-draft'
HAND=ROOT/'assets/rig/q-chibi-refined/generation'


def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/f'{name}.py')
    result=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))


def safe(root,relative):
    path=(root/relative).resolve()
    if not path.is_relative_to(root.resolve()): raise ValueError('cache path escape')
    return path


def capture(source,cache=CACHE):
    """Import immutable historical evidence; explicitly not new model inference."""
    source,cache=Path(source),Path(cache)
    exporter=module('export-q-rig')
    manifest=read(source/'manifest.json')
    inpaint=read(source/'inpaint-report.json')
    candidate=read(DRAFT/'candidate-manifest.json')
    exporter.verify(LAYOUT,manifest['layout_sha256'],'layout')
    exporter.verify(source/'manifest.json',inpaint['manifest_sha256'],'split manifest')
    if manifest['layout'] != read(LAYOUT) or inpaint['remaining_jobs'] or inpaint['rest_changed_rgba_pixels']:
        raise ValueError('historical run is not a complete matching layout-v2 run')
    for part in candidate['layers']:
        exporter.verify(source/'filled-layers'/f"{part['name']}.png",part['sha256'],'filled layer')
        if inpaint['filled_layers_sha256'][part['name']] != part['sha256']:
            raise ValueError('filled report does not match published candidate')
    artifacts={}
    cache.mkdir(parents=True,exist_ok=True)
    (cache/'blobs').mkdir(exist_ok=True)
    def add(alias,path):
        digest=sha(path)
        blob='blobs/'+digest
        destination=cache/blob
        if not destination.exists(): shutil.copyfile(path,destination)
        entry={'blob':blob,'sha256':digest,'size_bytes':path.stat().st_size}
        if path.suffix.lower()=='.png':
            with Image.open(path) as image: entry['dimensions']=list(image.size)
        artifacts[alias]=entry
    add('layout.json',LAYOUT)
    add('split-manifest.json',source/'manifest.json')
    add('inpaint-report.json',source/'inpaint-report.json')
    add('candidate-manifest.json',DRAFT/'candidate-manifest.json')
    jobs=[]
    for job in manifest['jobs']:
        name=job['part']
        result=read(source/'fills'/name/'report.json')
        history=read(source/'fills'/name/'history.json')
        if result['status']!='passed' or not history.get('status',{}).get('completed'):
            raise ValueError('unfinished historical inference '+name)
        paths={}
        for filename in ['input.png','mask.png','generated.png','result.png','workflow.json','request.json','history.json','report.json']:
            alias=f'inpaint/{name}/{filename}'
            add(alias,source/'fills'/name/filename)
            paths[filename]=alias
        exporter.verify(source/job['input'],sha(source/'fills'/name/'input.png'),'job input')
        exporter.verify(source/job['mask'],sha(source/'fills'/name/'mask.png'),'job mask')
        jobs.append({'name':name,'status':'historical_model_inference_completed_cached','prompt_id':result['prompt_id'],'artifacts':paths})
    hand_fingerprints=read(HAND/'artifact-hashes.json')
    add('hand/artifact-hashes.json',HAND/'artifact-hashes.json')
    for relative,expected in hand_fingerprints.items():
        path=safe(HAND,relative)
        exporter.verify(path,expected,'hand evidence')
        add('hand/'+relative,path)
    value={'version':1,'status':'historical_inference_cache_locked','new_model_inference':False,
        'layout_sha256':sha(LAYOUT),'source_sha256':manifest['layout']['source_sha256'],
        'artifacts':artifacts,'inpaint_jobs':jobs,'hand':{'status':'historical_guided_open_palm_cached','artifacts_prefix':'hand/'},
        'storage':{'unique_blob_count':len({a['blob'] for a in artifacts.values()}),'unique_bytes':sum((cache/b).stat().st_size for b in {a['blob'] for a in artifacts.values()}),'max_blob_bytes':max(a['size_bytes'] for a in artifacts.values())}}
    if len(jobs)!=16: raise ValueError('expected original 16 inpaint jobs')
    write(cache/'manifest.json',value)
    return value


def preflight(provenance,cache,layout,exporter):
    """Validate the complete evidence graph before allowing any output writes."""
    artifacts=provenance.get('artifacts')
    core={'layout.json','split-manifest.json','inpaint-report.json','candidate-manifest.json'}
    if not isinstance(artifacts,dict) or not core.issubset(artifacts):
        raise ValueError('cache missing core aliases: '+','.join(sorted(core-set(artifacts or {}))))
    for alias,entry in artifacts.items():
        if not isinstance(alias,str) or not isinstance(entry,dict) or not {'blob','sha256','size_bytes'}.issubset(entry):
            raise ValueError('invalid cache artifact entry')
        blob=safe(cache,entry['blob'])
        exporter.verify(blob,entry['sha256'],alias)
        if blob.stat().st_size!=entry['size_bytes']: raise ValueError('cache size mismatch '+alias)
    def artifact(alias): return safe(cache,artifacts[alias]['blob'])
    historical=read(artifact('split-manifest.json'))
    if read(artifact('layout.json'))!=layout or historical.get('layout')!=layout or historical.get('layout_sha256')!=sha(LAYOUT):
        raise ValueError('cached core layout does not match original layout')
    split_jobs=historical.get('jobs')
    tasks=provenance.get('inpaint_jobs')
    if not isinstance(tasks,list) or not isinstance(split_jobs,list) or len(tasks)!=16 or len(split_jobs)!=16:
        raise ValueError('cache requires 16 original jobs and split tasks')
    names=[task.get('name') for task in tasks if isinstance(task,dict)]
    split_names=[job.get('part') for job in split_jobs if isinstance(job,dict)]
    if len(names)!=16 or any(not isinstance(name,str) for name in names) or len(set(names))!=16:
        raise ValueError('cache requires 16 unique job names')
    if len(split_names)!=16 or len(set(split_names))!=16 or set(names)!=set(split_names):
        raise ValueError('cached jobs must exactly match split tasks')
    required={'input.png','mask.png','generated.png','result.png','workflow.json','request.json','history.json','report.json'}
    for task in tasks:
        references=task.get('artifacts')
        if not isinstance(references,dict) or set(references)!=required:
            raise ValueError('job requires eight evidence artifacts: '+task['name'])
        for filename,alias in references.items():
            if alias!=f"inpaint/{task['name']}/{filename}" or alias not in artifacts:
                raise ValueError('missing or wrong job evidence reference: '+task['name']+'/'+filename)
    hand_required={'source.png','source-mask.png','coordinates.json','artifact-hashes.json','comfy/generated.png',
                   'comfy/input.png','comfy/mask.png','comfy/workflow.json','comfy/request.json','comfy/history.json','comfy/report.json'}
    if any('hand/'+relative not in artifacts for relative in hand_required):
        raise ValueError('cache missing required hand evidence')
    hand_fingerprints=read(artifact('hand/artifact-hashes.json'))
    if not isinstance(hand_fingerprints,dict) or not (hand_required-{'artifact-hashes.json'}).issubset(hand_fingerprints):
        raise ValueError('hand manifest does not cover required evidence')
    for relative,expected in hand_fingerprints.items():
        alias='hand/'+relative
        if alias not in artifacts or artifacts[alias]['sha256']!=expected:
            raise ValueError('missing or inconsistent locked hand evidence: '+relative)
    inpaint=read(artifact('inpaint-report.json'))
    candidate=read(artifact('candidate-manifest.json'))
    if inpaint.get('manifest_sha256')!=sha(artifact('split-manifest.json')) or inpaint.get('remaining_jobs')!=[] or inpaint.get('rest_changed_rgba_pixels')!=0:
        raise ValueError('invalid completed inpaint evidence')
    if candidate.get('layout_sha256')!=sha(LAYOUT) or len(candidate.get('layers',[]))!=22:
        raise ValueError('invalid original 22-layer candidate evidence')
    return historical


def build(output,cache=CACHE):
    """Re-split the original, replay old model results, then run current refinement."""
    output,cache=Path(output),Path(cache)
    if output.resolve().is_relative_to((ROOT/'assets/rig/q-chibi-refined').resolve()) or output.resolve().is_relative_to(cache.resolve()):
        raise ValueError('output must not overwrite the frozen refined bundle or provenance cache')
    exporter,builder,refiner=module('export-q-rig'),module('build-q-parts'),module('refine-q-rig')
    provenance=read(cache/'manifest.json')
    exporter.verify(LAYOUT,provenance['layout_sha256'],'layout')
    layout=read(LAYOUT)
    exporter.verify(ROOT/layout['source'],layout['source_sha256'],'source')
    exporter.verify(ROOT/layout['native'],layout['native_sha256'],'native')
    historical=preflight(provenance,cache,layout,exporter)
    def artifact(alias): return safe(cache,provenance['artifacts'][alias]['blob'])
    # Only after all immutable sources and caches pass integrity may output be created.
    output.mkdir(parents=True,exist_ok=True)
    split=output/'split'
    with redirect_stdout(io.StringIO()): builder.build(LAYOUT,split)
    fresh=read(split/'manifest.json')
    if fresh!=historical:
        raise ValueError('fresh split manifest differs from locked historical split')
    layers={part['name']:Image.open(split/part['layer']).convert('RGBA') for part in fresh['layers']}
    candidate=output/'candidate'
    (candidate/'layers').mkdir(parents=True,exist_ok=True)
    jobs=[]
    comfy=module('comfy-inpaint')
    for task in provenance['inpaint_jobs']:
        name=task['name']
        native_job=next(j for j in fresh['jobs'] if j['part']==name)
        inputs=task['artifacts']
        request=read(artifact(inputs['request.json']))
        history=read(artifact(inputs['history.json']))
        historical_report=read(artifact(inputs['report.json']))
        if request['source_sha256'] != provenance['artifacts'][inputs['input.png']]['sha256'] or not history.get('status',{}).get('completed') or historical_report['status']!='passed':
            raise ValueError('invalid historical inference status/input '+name)
        exporter.verify(split/native_job['input'],provenance['artifacts'][inputs['input.png']]['sha256'],'regenerated inpaint input')
        exporter.verify(split/native_job['mask'],provenance['artifacts'][inputs['mask.png']]['sha256'],'regenerated inpaint mask')
        before=Image.open(artifact(inputs['input.png'])).convert('RGBA')
        raw=Image.open(artifact(inputs['generated.png'])).convert('RGBA')
        mask=Image.open(artifact(inputs['mask.png'])).convert('L')
        result,checks=comfy.composite_and_check(before,raw,mask)
        locked=Image.open(artifact(inputs['result.png'])).convert('RGBA')
        if result.tobytes()!=locked.tobytes(): raise ValueError('cached model result reconstruction mismatch '+name)
        if checks != historical_report['full_size']:
            raise ValueError('historical checks disagree '+name)
        small=result.crop(layout['crop']).resize(tuple(layout['native_size']),Image.Resampling.BOX)
        missing=Image.open(split/'masks'/f'{name}-missing.png').convert('L')
        layers[name]=builder.fill_hidden(layers[name],small,missing)
        jobdir=output/'inpaint-evidence'/name
        jobdir.mkdir(parents=True,exist_ok=True)
        for filename,alias in inputs.items(): shutil.copyfile(artifact(alias),jobdir/filename)
        result.save(jobdir/'reconstructed-result.png')
        jobs.append({'name':name,'status':'cached_result_reconstructed_and_verified','new_model_inference':False,'checks':checks,
            'artifacts':{filename:{'file':f'inpaint-evidence/{name}/{filename}',**provenance['artifacts'][alias]} for filename,alias in inputs.items()}})
    expected=read(artifact('candidate-manifest.json'))
    for part in expected['layers']:
        dest=candidate/part['file']
        layers[part['name']].save(dest)
        exporter.verify(dest,part['sha256'],'reconstructed filled layer')
    for filename in ['candidate-manifest.json','inpaint-report.json']:
        shutil.copyfile(artifact(filename),candidate/filename)
    hand=output/'hand-cache'
    for alias in provenance['artifacts']:
        if alias.startswith('hand/'):
            dest=safe(hand,alias[len('hand/'):])
            dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(artifact(alias),dest)
    refined=output/'refined'
    refinement=refiner.refine(refined,candidate=candidate,hand_source=hand)
    refined_records=[{**record,**{key:'refined/'+record[key] for key in ['input','mask','output']},'status':'hidden_refinement_verified'} for record in refinement['layers']]
    variant={**refinement['variant'],'status':'cached_comfy_guided_palm_reconstructed','file':'refined/variants/handOpen.png','mask':'refined/variants/handOpen-mask.png','input':'refined/variants/handOpen-input.png','model_evidence':'hand-cache/comfy/','generation_manifest':'hand-cache/artifact-hashes.json'}
    report={'status':'full_pipeline_rebuilt_from_locked_model_cache','new_model_inference':False,'provenance_manifest_sha256':sha(cache/'manifest.json'),
        'layout_sha256':sha(LAYOUT),'source_sha256':layout['source_sha256'],'split_manifest_sha256':sha(split/'manifest.json'),
        'inpaint_jobs':jobs,'refined_layers':refined_records,'variant':variant,'rest_changed_rgba_pixels':refinement['rest_changed_rgba_pixels'],
        'user_visual_acceptance':False,'storage':provenance['storage']}
    write(output/'pipeline-report.json',report)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--cache',type=Path,default=CACHE)
    parser.add_argument('--capture',type=Path,help='one-time import from a completed historical build directory')
    args=parser.parse_args()
    if args.capture: print(json.dumps(capture(args.capture,args.cache)['storage']))
    if args.output:
        result=build(args.output,args.cache)
        print(json.dumps({k:result[k] for k in ['status','new_model_inference','rest_changed_rgba_pixels','storage']},indent=2))
    if not args.capture and not args.output: parser.error('--output or --capture is required')
