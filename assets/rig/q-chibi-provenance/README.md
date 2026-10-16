# Q 版完整来源缓存与重建

本目录保存历史 ComfyUI 推理的必要证据与完整清单，不含模型权重。`manifest.json` 列出16项原隐藏区补洞任务、掌心生成证据，以及每个输入/蒙版/原始生成图/蒙版合成结果/工作流/请求/历史/状态报告的 SHA、字节数、图像尺寸。`blobs/` 按 SHA 去重；147个唯一blob共16,007,093字节，最大511,622字节。文件别名可在清单中逐项人工核对，重建输出会还原成易浏览的命名目录。

来源为已成功的历史 q-parts-motion 构建；导入时已核对split manifest与inpaint-report绑定、layout v2、16任务完成状态、22 filled-layer哈希与已发布draft逐层一致。原临时目录只在一次性导入使用；正常重建不读取临时目录，也不访问ComfyUI服务器。

从仓库根目录连续执行同一条命令：

```powershell
E:/AI/ComfyUI_windows_portable/python_embeded/python.exe tools/build-refined-pipeline.py --output _tmp/refined-pipeline-cli
E:/AI/ComfyUI_windows_portable/python_embeded/python.exe tools/build-refined-pipeline.py --output _tmp/refined-pipeline-cli
E:/AI/ComfyUI_windows_portable/python_embeded/python.exe tools/test-build-refined-pipeline.py
```

`--output` 可指定任意新的目录；禁止覆盖冻结的 q-chibi-refined 包与来源缓存。输出包含：

- split/：真正调用build-q-parts.py重新拆件的22原绘层、missing/visible/target蒙版、16项输入/蒙版及manifest。
- inpaint-evidence/：16项原模型输入、蒙版、generated、result、workflow、request、history、report，以及现场重合成的reconstructed-result.png。
- candidate/：从原生成图按原mask、crop、BOX降采样与missing-mask重新补齐的22层，逐层SHA必须等于原draft。
- hand-cache/：掌心原始生成证据（含被拒绝的首轮握杖结果）和锁定清单。
- refined/：调用当前refine-q-rig.py所得22层精修、逐层输入/修改蒙版/结果/哈希与备用掌心。
- pipeline-report.json：16项状态和证据映射、22层精修来源、掌心状态与哈希、默认原姿态RGBA差异。路径从输出目录定位，refined的内部报告路径从refined/定位。

这是对已缓存模型输出的确定性重建，不是新的模型推理。手掌仍是独立轮廓引导与ComfyUI材质生成的候选；用户美术认可、运行时动态遮挡与桌面交互验收不由缓存重建代替。

开发者回归先观察了缺少全链脚本的预期红灯，再实现。最终回归阻断urlopen，证明重建无网络依赖；把可选原draft/hand导入路径置为不存在，证明正常流程只依赖提交缓存与固定原始素材。验证新目录、同目录重跑、第二新目录全部输出逐文件相同，篡改blob则在创建输出前拒绝，最终22层与掌心SHA等于冻结精修包。另两次实际CLI命令执行记录和全部输出SHA见validation.json。
2026-10-16补充结构预检回归：在创建任何输出目录前要求四个核心别名齐全、16个job名称唯一且与split任务完全对应、每job严格包含八项所需证据且引用归属本任务、hand必需项与其锁定指纹全部覆盖。九种缺项/重复/错误引用用例均抛出ValueError且输出不存在；完整重建与一致性回归仍通过（2 tests，39.318s）。只验证现存blob哈希不再被视为证据链完整。
