# Q 版精修素材包

输入为 `../q-chibi-draft/` 的已校验 22 层与 `../q-chibi.layout.json` v2；旧候选没有修改。默认层为原生 105×123。

重建（使用包内已完成且有 SHA 的 ComfyUI 掌心证据，不要求服务运行）：

```powershell
E:/AI/ComfyUI_windows_portable/python_embeded/python.exe tools/refine-q-rig.py --output assets/rig/q-chibi-refined
E:/AI/ComfyUI_windows_portable/python_embeded/python.exe tools/test-refine-q-rig.py
```

`--generate-hand` 才会向已有 `127.0.0.1:8188` 服务发起新生成；脚本不会启动服务。重新生成会改变生成证据，因此正式包的可复现重建使用已锁定的缓存。

`rig.properties/native.png/layers/` 与 LayeredRig.load(Path) 格式兼容。22 默认层不包含张开手，`variants/handOpen.png` 为满画布替换层，元数据为 `replaces=hand, group=armNear, bones=upperArm,foreArm,hand`。手掌使用独立轮廓引导，ComfyUI 生成肤色材质后按轮廓蒙版裁入；第一轮握杖结果不合要求，保留于 generation/round1-closed-hand-rejected/。

每层 evidence/ 保留候选输入、原绘可见像素、修改蒙版；结果在 layers/，三个文件的 SHA 与逐层差异见 refinement-report.json。任何原绘可见像素和蒙版外像素均不改，原姿态合成为逐像素一致。

复核图为 rest.png（实际原尺寸）、rest-comparison.png、refined-layers.png、material-comparison.png。发丝隐藏非蓝色块已纠正、袖摆隐藏区改为原绘白衣材质、近侧髋部隐藏黑块已修；原绘短裤与腿环仍保留，动态拉伸与交叉需要由运行时骨骼权重/遮挡验证。hairNear 保留原有 3 像素小组件，不能宣称所有层均只有一个组件。

素材完整性通过不代表美术验收；用户视觉复核、招手全流程与桌面交互验收仍待进行，未运行 pet.Main。
重建会原位更新包中的多个文件；请先关闭读取该包的预览/运行时，再执行命令，完成校验后重新加载。并发读取期间可能看到层文件与rig.properties哈希的中间态。
