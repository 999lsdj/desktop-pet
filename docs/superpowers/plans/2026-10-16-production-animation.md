# Q 版素材精修与正式动画 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 保留 Q 版原貌，精修可变形素材，并让 PetWindow 实际使用分层蒙皮与有支撑阶段的步态。

**Architecture:** 原始候选作为可追溯输入，新增独立精修包与逐像素修改蒙版；Java 运行时从经哈希校验的包加载。动作求值与窗口分离，使离屏测试运行与 PetWindow 相同的动作、物理和渲染代码。

**Tech Stack:** Java 17、Java2D、Python/Pillow、本机 ComfyUI；Java 零第三方依赖。

**Spec:** openspec/changes/skeletal-pet-animation/{proposal,design,tasks}.md 与 specs/。

## Global Constraints

- 固定来源 assets/像素角色/master-transparent-800.png，原生 105×123，2 倍显示。
- 不运行 pet.Main；桌宠窗口保持关闭。离屏测试不算桌面交互验收。
- 不改变原立绘已经绘制的像素；精修隐藏区域与备用张开手掌须保留输入、蒙版、结果与哈希。
- 髋摆幅目标约 12°，两腿交替，支撑脚不浮空，脚踝不向内折入。
- 只对实际达到的 OpenSpec 条件勾选。用户视觉复核与拎起、招手全流程仍单独追踪。
- 延续用户授权在当前仓库按检查点保存及推送，不暂存 .agents/。

## Task 1: 精修素材包

**Files:** tools/refine-q-rig.py、tools/test-refine-q-rig.py、assets/rig/q-chibi-refined/；必要时 tools/export-q-rig.py。
**Interfaces:** 消费 q-chibi-draft 的已校验 22 层、layout v2、补洞报告；输出兼容 LayeredRig.load(Path) 的 rig.properties/native.png/layers，独立 refinement-report.json。备用手掌不参与默认原姿态合成。

- [x] 先写回归：蒙版外 RGBA 不变、原姿态重组一致、源 SHA 不匹配拒绝、同目录两次生成一致、备用掌心有独立图像和蒙版。
- [x] 阅读逐层图，确定隐藏蓝发颜色、髋部遮挡、袖摆轮廓和手掌的具体修正区域。
- [x] 实现可复跑命令 `python tools/refine-q-rig.py --output _tmp/refined-rebuild`，保留修正输入/蒙版/输出，素材状态与视觉审查分别记录。冻结运行包不原位重建。
- [x] 连跑两次，查看最终尺寸和放大联系表；记录每层差异与已知限制，再独立审查。

## Task 2: 正式动作求值

**Files:** src/pet/PetAnimation.java、src/pet/LayeredDynamics.java、src/pet/ProductionAnimationSelfTest.java、Skeleton.java、LayeredRig.java、VerletChain.java。
**Interfaces:** `PetAnimation(LayeredRig, Config)`；`update(dt, walking, speed, tilt, rest, menu, externalX)`，`frame()` 返回带固定留边的实际渲染帧；窗口与测试共用。

- [x] 写接地、对称步幅、脚掌方向、有限顶点、停步收敛的行为回归；先观察旧预览的接地检查失败。
- [x] 采用足端轨迹与双骨求解维持支撑脚高度；脚掌朝向补偿；固定身体高度，避免整体周期跳动。实际髋半行程约 11.672° / 12.430°。
- [x] 真实分层发丝、袖子、衣摆绑定 Verlet 链，提灯用阻尼单摆；起停速度驱动惯性，连续推进且不在周期边界重置。
- [x] 输出走路、站定、微醺、踉跄、打盹和菜单连续帧；回归三轮走路交界速度、停步衰减与当前场景包络。所有循环动作连续性仍按 OpenSpec 4.2 跟踪。

## Task 3: PetWindow 接入与同步

**Files:** src/pet/PetWindow.java、config.properties、tools/build_log_docx.py、docs/桌宠项目进度日志.docx、OpenSpec tasks/design、两份说明与素材 README。
**Interfaces:** PetWindow 使用 PetAnimation 帧；命中和形状均取同一帧可见 alpha，不以旧身体矩形替代。

- [x] 替换 BodyRig 绘制路径，保留行为状态机；移除整体 bob；脚步随移动速度变化，静止时衰减而非重置。
- [x] 窗口绘制、镜像、命中与形状使用一致坐标；预留材质动态留边，菜单与提示并入形状。桌面交互另待实测。
- [x] 构建、离屏集成、自检与 OpenSpec strict；独立代码/规格审查，修复发现的问题。
- [x] 绑定当前源码与素材 SHA，输出便于用户复核的动画与状态图；同步 Word 日志并渲染检查。
- [x] 按真实结果同步复选框、自动化接续说明，按检查点提交并推送。

## 执行记录

- 2026-10-16：既有 OpenSpec 已批准本轮范围，直接执行，不另设确认门。历史预览报告不作为本轮证据。
- 素材检查点 `da412d7`；正式动画检查点 `977c72a`。本轮素材人工放行、用户步态观感和桌面验收仍待进行。
- 完整来源缓存新增重建入口，覆盖 16 项原补洞、22 层精修和备用掌心；锁定结果重放不发起新的模型推理。
- 产线检查点 `7451211`；独立审查发现的清单缺项预检已修复，9 类异常在写输出前拒绝。OpenSpec 7.3 已勾选，当前 14/29。
- Word 第十六节已生成并渲染为 16 页，检查变化页 1、15、16，其他页面与上一版像素一致。追踪自动化已改用正式动画和完整来源缓存作为当前基线。
- 2026-10-16 续做：4.2 循环三轮连续性自检与 9.1 极限摆位包络验收完成（OpenSpec 16/29）；重跑证据时帧图逐字节不变。4.1 的实机观察与 9.2 的悬停反馈留下一步实机验收。
