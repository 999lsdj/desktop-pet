"""Package actual PetAnimation frames and bind evidence to current code/assets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT/'_tmp/production-animation/final')
    parser.add_argument('--output', type=Path, default=ROOT/'assets/rig/production-preview')
    args = parser.parse_args()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    report = json.loads((args.input/'report.json').read_text())
    for name in ['states.png', 'extremes.png', 'report.json', 'self-test.json']:
        shutil.copyfile(args.input/name, out/name)
    for mode, data in report['modes'].items():
        frames = [Image.open(args.input/mode/f'{i:03d}.png').convert('RGB') for i in range(144)]
        # 每张PNG准确对应求值时刻；GIF使用累计舍入到10ms，避免帧间隔量化累积漂移。
        milliseconds = data['frame_seconds'] * 1000
        durations = [max(10, (round((i+1)*milliseconds/10)-round(i*milliseconds/10))*10) for i in range(144)]
        frames[0].save(out/f'{mode}.gif', save_all=True, append_images=frames[1:], duration=durations, loop=0)
    strip = Image.new('RGB', (160*8, 240), '#e8edf3')
    draw = ImageDraw.Draw(strip)
    for j, i in enumerate(range(0,48,6)):
        picture = Image.open(args.input/'walk'/f'{i:03d}.png')
        strip.paste(picture.crop((130,236,210,336)).resize((160,200),Image.Resampling.NEAREST),(j*160,32))
        draw.text((j*160+5,8), f'phase {i}/48', fill='black')
    strip.save(out/'legs.png')
    # 显式保留旧驱动对照，宽高不同不强行拉伸旧证据。
    previous = ROOT/'assets/rig/q-chibi-draft/motion-preview/gait-comparison.png'
    old = Image.open(previous).convert('RGB')
    comparison = Image.new('RGB',(max(old.width,strip.width),old.height+strip.height+30),'#e8edf3')
    comparison.paste(old,(0,0)); comparison.paste(strip,(0,old.height+30))
    ImageDraw.Draw(comparison).text((8,old.height+8),'Current: grounded gait / torso-pinned hip / refined material',fill='black')
    comparison.save(out/'gait-history-comparison.png')
    binding = {
        'status':'production_path_integrated_pending_visual_and_desktop_acceptance',
        'runtime_integrated':True, 'desktop_started':False, 'user_visual_acceptance':False,
        'material_user_acceptance':False,
        'sources':{str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in [
            ROOT/'assets/rig/q-chibi.layout.json', ROOT/'assets/rig/q-chibi-refined/rig.properties',
            ROOT/'assets/rig/q-chibi-refined/refinement-report.json']},
        'source_files_lf_sha256':{str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(
            p.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode()).hexdigest()
            for p in [*(ROOT/'src/pet').glob('*.java'),ROOT/'config.properties',ROOT/'tools/refine-q-rig.py',
                      ROOT/'tools/package-production-evidence.py']},
        'artifacts':{p.name:sha(p) for p in sorted(out.iterdir()) if p.is_file() and p.suffix in ('.json','.png','.gif') and p.name!='binding.json'},
        'limits':['Stable stance contact is tested; startup and stopping are blended transitions.',
                  'Three walking cycles are sampled. Pause is a six-second decay, not a repeating loop.',
                  'Sway/stumble are supplied scenario inputs to the same production evaluator.',
                  'Extreme sheet covers 26 endpoints and one prepared open-palm pose, not all combined poses.',
                  'Native window, interaction, carried motion and full waving remain to be accepted.']
    }
    (out/'binding.json').write_text(json.dumps(binding,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(out)


if __name__ == '__main__':
    main()
