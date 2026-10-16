"""Deterministic hidden-region refinement; immutable original pixels, cached ComfyUI hand."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
DEFAULT = ROOT/'assets/rig/q-chibi-refined'

def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT/'tools'/f'{name}.py')
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def generate_hand(directory):
    comfy = module('comfy-inpaint')
    comfy.api('/system_stats')  # Never start a second service.
    directory.mkdir(parents=True, exist_ok=True)
    # Native hand is centered on (25,69). A larger local context gives SDXL room for fingers.
    points = [(23,70),(21,68),(19,65),(20,64),(22,66),(21,61),(22,60),(24,64),(24,59),(25,59),(26,64),(27,60),(28,61),(28,66),(29,64),(30,65),(28,70),(26,73),(24,73)]
    native_mask = Image.new('L',(28,28))
    ImageDraw.Draw(native_mask).polygon([(x-11,y-49) for x,y in points],fill=255)
    mask = native_mask.resize((560,560),Image.Resampling.NEAREST)
    guide = Image.new('RGBA',(560,560),(214,220,226,255))
    ImageDraw.Draw(guide).polygon([(230,560),(230,450),(330,450),(380,560)],fill=(240,237,231,255),outline=(56,49,61,255),width=5)
    guide.paste((244,211,187,255),mask=mask)
    guide.save(directory/'source.png')
    mask.save(directory/'source-mask.png')
    comfy.run(argparse.Namespace(out=directory/'comfy',image=directory/'source.png',mask=directory/'source-mask.png',smoke=False,prepare_only=False,
        prompt='isolated open human hand, palm facing viewer, fingers fully extended and spread upward, five short fingers, pale peach skin, chibi anime illustration, small delicate hand, white coat cuff at bottom, empty palm, flat grey background, no object, no stick, no staff, no gripping',seed=2026101601,steps=24,timeout=600,downsample=5))
    write(directory/'coordinates.json', {'input_kind':'isolated open-hand silhouette guide; original default hand is untouched','generated_size':[560,560],'native_canvas':[105,123],'native_crop_origin':[11,49],'native_crop_size':[28,28]})

    write(directory/'artifact-hashes.json', {str(p.relative_to(directory)).replace('\\','/'):sha(p) for p in sorted(directory.rglob('*')) if p.is_file() and p.name != 'artifact-hashes.json'})

def refine(output, layout_path=ROOT/'assets/rig/q-chibi.layout.json', candidate=ROOT/'assets/rig/q-chibi-draft', hand_source=DEFAULT/'generation'):
    output, candidate, hand_source = Path(output), Path(candidate), Path(hand_source)
    exporter, builder = module('export-q-rig'), module('build-q-parts')
    layout = json.loads(Path(layout_path).read_text(encoding='utf-8-sig'))
    manifest = json.loads((candidate/'candidate-manifest.json').read_text())
    # Preflight before output mutation; verify every inherited source and generation artifact.
    exporter.verify(layout_path, manifest['layout_sha256'], 'layout')
    exporter.verify(ROOT/layout['source'],layout['source_sha256'],'source')
    exporter.verify(ROOT/layout['native'],layout['native_sha256'],'native')
    prior = json.loads((candidate/'inpaint-report.json').read_text())
    if layout.get('version') != 2 or len(manifest['layers']) != 22:
        raise ValueError('requires layout v2 and 22 verified layers')
    if prior['rest_changed_rgba_pixels'] != 0 or prior['remaining_jobs']:
        raise ValueError('candidate lacks complete zero-difference inpaint evidence')
    for part in manifest['layers']:
        exporter.verify(candidate/part['file'],part['sha256'],part['name'])
        if prior['filled_layers_sha256'][part['name']] != part['sha256']:
            raise ValueError('candidate and inpaint report disagree')
    fingerprints = json.loads((hand_source/'artifact-hashes.json').read_text())
    required = {'source.png', 'source-mask.png', 'coordinates.json', 'comfy/generated.png',
                'comfy/input.png', 'comfy/mask.png', 'comfy/workflow.json', 'comfy/request.json',
                'comfy/history.json', 'comfy/report.json'}
    if not isinstance(fingerprints, dict) or not required.issubset(fingerprints):
        raise ValueError('generation manifest must cover input, mask, workflow, request, history and result')
    if any(not isinstance(v, str) or len(v) != 64 or any(c not in '0123456789abcdef' for c in v)
           for v in fingerprints.values()):
        raise ValueError('generation manifest contains an invalid SHA-256')
    for relative, expected in fingerprints.items():
        exporter.verify(exporter.inside(hand_source,relative),expected,'hand generation')
    generated = hand_source/'comfy/generated.png'
    history = json.loads((hand_source/'comfy/history.json').read_text())
    if not history.get('status',{}).get('completed') or not generated.exists():
        raise ValueError('completed ComfyUI open-hand evidence required')
    original = Image.open(ROOT/layout['native']).convert('RGBA')
    visible, _ = builder.split_visible(original,layout['parts'])
    layers = {p['name']:Image.open(candidate/p['file']).convert('RGBA') for p in manifest['layers']}
    original_layers = {n:im.copy() for n,im in layers.items()}
    names = list(layers)
    records = []
    output.mkdir(parents=True,exist_ok=True)
    exporter.export_rig(layout_path,candidate,output)
    for index,name in enumerate(names):
        layer = layers[name]
        mask = Image.new('L',original.size)
        front = builder.reassemble([visible[n] for n in names[index+1:]],original.size)
        # A modification can only occupy a previously missing pixel fully hidden at rest.
        allowed = [(x,y) for y in range(123) for x in range(105) if visible[name].getpixel((x,y))[3]==0 and front.getpixel((x,y))[3]==255]
        target = builder.polygons(original.size,layout['parts'][index].get('reconstruct',layout['parts'][index]['polygons']))
        seeds = [(x,y,visible[name].getpixel((x,y))) for y in range(123) for x in range(105) if visible[name].getpixel((x,y))[3]>220]
        if name.startswith('hair'):
            seeds = [s for s in seeds if s[2][2]>s[2][0]*1.12 and s[2][1]>s[2][0]*1.05 and s[2][0]<140]
        elif name.startswith('sleeve'):
            seeds = [s for s in seeds if max(s[2][:3])-min(s[2][:3])<26 and min(s[2][:3])>150]
        elif name.startswith('thigh'):
            seeds = [s for s in seeds if s[2][0]>160 and s[2][0]>s[2][2]*1.1]
        if name.startswith(('hair','sleeve','thigh')) and seeds:
            for x,y in allowed:
                if not target.getpixel((x,y)):
                    continue
                old = layer.getpixel((x,y))
                # Hair: eliminate foreign skin/fabric in generated pixels; sleeves: complete silhouette.
                # Thigh: remove generated dark hip smears, original shorts remain protected.
                change = (name.startswith('hair') and (old[2]<=old[0]*1.12 or old[1]<=old[0]*1.05 or old[0]>=140)) or name.startswith('sleeve') or (name.startswith('thigh') and y<90 and max(old[:3])<125)
                if change:
                    color = min(seeds,key=lambda s:(s[0]-x)**2+(s[1]-y)**2)[2]
                    layer.putpixel((x,y),color[:3]+(255,))
                    mask.putpixel((x,y),255)
        evidence = output/'evidence'/name
        evidence.mkdir(parents=True,exist_ok=True)
        original_layers[name].save(evidence/'input.png')
        visible[name].save(evidence/'original-visible.png')
        mask.save(evidence/'mask.png')
        layer.save(output/'layers'/f'{name}.png')
        changed = sum(a!=b for a,b in zip(original_layers[name].get_flattened_data(),layer.get_flattened_data()))
        protected = sum(a!=b and v[3]>0 for a,b,v in zip(original_layers[name].get_flattened_data(),layer.get_flattened_data(),visible[name].get_flattened_data()))
        records.append({'name':name,'input':f'evidence/{name}/input.png','mask':f'evidence/{name}/mask.png','output':f'layers/{name}.png','changed_pixels':changed,'original_visible_changed_pixels':protected,
            'input_sha256':sha(evidence/'input.png'),'mask_sha256':sha(evidence/'mask.png'),'output_sha256':sha(output/'layers'/f'{name}.png')})
    rebuilt = builder.reassemble(layers.values(),original.size)
    rest_diff = sum(a!=b for a,b in zip(original.get_flattened_data(),rebuilt.get_flattened_data()))
    if rest_diff:
        raise AssertionError(f'rest composite changed {rest_diff} RGBA pixels')
    variants = output/'variants'
    variants.mkdir(exist_ok=True)
    # Keep generated painting inside a hand silhouette; no source layer is overwritten.
    hand = Image.new('RGBA',original.size)
    handmask = Image.new('L',original.size)
    points = [(23,70),(21,68),(19,65),(20,64),(22,66),(21,61),(22,60),(24,64),(24,59),(25,59),(26,64),(27,60),(28,61),(28,66),(29,64),(30,65),(28,70),(26,73),(24,73)]
    ImageDraw.Draw(handmask).polygon(points,fill=255)
    local = Image.open(generated).convert('RGBA').resize((28,28),Image.Resampling.BOX)
    canvas = Image.new('RGBA',original.size)
    canvas.paste(local,(11,49))
    hand = Image.composite(canvas,hand,handmask)
    hand.save(variants/'handOpen.png')
    handmask.save(variants/'handOpen-mask.png')
    Image.new('RGBA',original.size).save(variants/'handOpen-input.png')
    if hand_source.resolve() != (output/'generation').resolve():
        shutil.copytree(hand_source,output/'generation',dirs_exist_ok=True)
    properties = (output/'rig.properties').read_text()
    for record in records:
        name=record['name']
        properties = properties.replace(f"layer.{name}.sha256={next(p['sha256'] for p in manifest['layers'] if p['name']==name)}",f"layer.{name}.sha256={record['output_sha256']}")
    properties += '\nvariants=handOpen\nvariant.handOpen.file=variants/handOpen.png\nvariant.handOpen.sha256='+sha(variants/'handOpen.png')+'\nvariant.handOpen.mask=variants/handOpen-mask.png\nvariant.handOpen.replaces=hand\nvariant.handOpen.group=armNear\nvariant.handOpen.bones=upperArm,foreArm,hand\n'
    (output/'rig.properties').write_text(properties,encoding='utf-8')
    rebuilt.save(output/'rest.png')
    builder.sheet(list(layers.items())+[('open hand variant',hand)],output/'refined-layers.png',cols=5,scale=4)
    builder.sheet([('native original',original),('refined rest',rebuilt)],output/'rest-comparison.png',cols=2,scale=4)
    focus=[]
    for name in ['hairNear','hairFar','sleeveNear','sleeveFar','thighNear','thighFar']:
        focus.extend([(name+' before',original_layers[name]),(name+' refined',layers[name])])
    builder.sheet(focus+[('handOpen',hand)],output/'material-comparison.png',cols=4,scale=4)
    report={'status':'refined_pending_user_visual_review','runtime_ready':False,'runtime_package_compatible':True,'user_visual_acceptance':False,'visual_review':'user_pending','method':'deterministic nearest original material, opaque occluded missing pixels only',
        'canvas_size':list(original.size),'source_sha256':layout['source_sha256'],'layout_sha256':sha(Path(layout_path)),'candidate_manifest_sha256':sha(candidate/'candidate-manifest.json'),'inpaint_report_sha256':sha(candidate/'inpaint-report.json'),
        'rest_changed_rgba_pixels':rest_diff,'layers':records,'variant':{'name':'handOpen','method':'comfyui_inpaint','guidance':'isolated silhouette; generated material clipped at native scale','replaces':'hand','sha256':sha(variants/'handOpen.png'),'mask_sha256':sha(variants/'handOpen-mask.png'),'input_sha256':sha(variants/'handOpen-input.png'),'generated_sha256':sha(generated),'generation_manifest_sha256':sha(hand_source/'artifact-hashes.json')},
        'known_limits':['Open hand is a generated candidate clipped to a separate silhouette; user art review pending.','Original visible hip/shorts pixels are immutable; dynamic occlusion must also be verified with runtime deformation.','Nearest-material completion is conservative and cannot establish user aesthetic approval.']}
    report['outline_checks'] = {name: {'components':builder.component_sizes(layers[name].getchannel('A'))} for name in ['hairNear','hairFar','sleeveNear','sleeveFar']}
    write(output/'refinement-report.json',report)
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=DEFAULT)
    parser.add_argument('--generate-hand',action='store_true')
    parser.add_argument('--hand-source',type=Path,default=DEFAULT/'generation',
                        help='locked ComfyUI hand evidence; defaults to the checked-in generation directory')
    args=parser.parse_args()
    if args.generate_hand:
        generate_hand(args.output/'generation')
    source = args.output/'generation' if args.generate_hand else args.hand_source
    print(json.dumps(refine(args.output,hand_source=source),ensure_ascii=False,indent=2))
