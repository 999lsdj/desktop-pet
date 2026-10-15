# Q 版分层候选

本目录保存当前阶段的 22 层候选，不是已经验收的运行素材。`runtime_ready` 保持 false。

- 来源及各层 SHA-256：`candidate-manifest.json`；分层坐标与骨头名见上级 `q-chibi.layout.json`。
- `layers/`：包含补片的 105×123 RGBA 层，均保持同一画布与原点。
- `filled-comparison.png`：原姿态拼回对照；`filled-layers.png`：各层联系表。
- `poses.png` 与 `pose-checks.json`：13 个关节的单关节刚性扫描证据。
- `report.json`：初始可见层的拆件报告；`inpaint-report.json`：补洞候选报告。

当前仅完成可见层划分和基础关节补图。发丝／袖子／衣摆的完整轮廓、隐藏材质的风格、
张开手掌、组合动作与连续蒙皮仍需精修验收。单关节内部缺口计数不包含自然移动的原透明边缘，
不能据此宣称所有外轮廓与接缝都合格。

重建命令见 `docs/素材产线说明.md`。完整输入、蒙版、请求及 ComfyUI 原始结果在本机
`_tmp/q-parts/`，不作为运行依赖、不提交模型权重。当前桌宠仍使用原有素材。
