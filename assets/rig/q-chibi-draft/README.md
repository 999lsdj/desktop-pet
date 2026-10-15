# Q 版分层候选

本目录保存当前阶段的 22 层候选，不是已经验收的运行素材。`runtime_ready` 保持 false。

- 来源及各层 SHA-256：`candidate-manifest.json`；分层坐标与骨头名见上级 `q-chibi.layout.json`。
- `layers/`：包含补片的 105×123 RGBA 层，均保持同一画布与原点。
- `filled-comparison.png`：原姿态拼回对照；`filled-layers.png`：各层联系表。
- `poses.png` 与 `pose-checks.json`：13 个关节的单关节刚性扫描证据。
- `report.json`：初始可见层的拆件报告；`inpaint-report.json`：补洞候选报告。

当前 layout v2 包含 22 层、16 项补洞和 2107 个待补层像素。已修正蓝发、灯穗和杖轮廓的归属，
双侧发丝／袖子／衣摆已补出隐藏连接候选。两次完整生成的蒙版外 RGBA 变化、静止重组差异均为 0，
163 个单关节刚性样本的内部缺口为 0，六个柔性层各有一个至少 4 像素的主要连通块。
少于 4 像素的岛仍列在报告中，不能把主要连通块计数当作美术验收。

`inpaint-report.json` 的 `filled_layers_sha256` 绑定生成时的全部图层；候选导出拒绝非零重组差异、
缺失指纹或生成后被改动的图层。`candidate-manifest.json` 和 layout 的 SHA 必须匹配当前文件。

`motion-preview/` 保存本轮按材质约束的 Java 离屏蒙皮证据：

- `poses.png`、`preview.gif`：真实材质的六种诊断姿态与一轮周期预览；GIF 为 40 ms/帧的近似展示，精确 PNG 帧仍在临时目录。
- `report.json`：144 帧三轮的逐帧角度、端点、每层位移与耗时，静止渲染/周期闭合差异均为 0。
- `rig.properties`：绘制时的骨架与贴图指纹记录。它需要导出器重建的同目录 layers/native 才可运行。
- `repeat-report.json`：两轮目录结构相同，163 个输入与产物文件哈希相同；请求 ID、执行耗时不要求相同。
- `binding.json`：该预览所对应的 layout、候选 manifest、补洞报告及证据文件 SHA，附未解决的视觉问题。

绘制平均 2.689 ms、P95 3.568 ms，求值平均 0.038 ms；不含 PNG 写盘与完整窗口成本。
本组样本的 18 像素留边未被超出。周期函数驱动不包含惯性、IK 或正式状态机，
`physics_acceptance: false`；不能据此勾选动作连续性或全范围包络验收。

待办：抬腿时的髋部黑色拉伸与遮挡、隐藏发丝颜色和轮廓、张开手掌、自然动态，以及正式 `PetWindow` 接入。
单关节内部缺口计数不包含自然移动的原透明边缘，也不能代替外轮廓与接缝检查。

重建命令见 `docs/素材产线说明.md`。完整输入、蒙版、请求及 ComfyUI 原始结果在本机
`_tmp/q-parts-motion/`，两轮快照在 `_tmp/q-motion/final-repeat/`，逐帧预览在 `_tmp/layered-preview/final/`。
这些临时目录不作为运行依赖、不提交模型权重。旧 `_tmp/q-parts/` 为历史布局证据。
当前桌宠已按用户要求关闭；再次运行时仍使用原有素材。OpenSpec 复核为 11/29，3.2 已纠正为未完成。
