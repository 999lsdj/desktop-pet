# Q 版分层连续性与蒙皮预览 Implementation Plan

> **For agentic workers:** 使用 superpowers:subagent-driven-development 执行可独立验证的任务；完成后按 requesting-code-review 检查。

**Goal:** 修复候选素材的衣发断段，建立真正按材质约束的离屏蒙皮预览，为正式替换矩形拆件提供可检查的画面。

**Architecture:** 分层定义继续以 q-chibi.layout.json 为来源。Python 保留可见像素并生成隐藏蒙版，ComfyUI 补图；Java 使用同一份定义导出的 properties 骨架与部件契约，分别蒙皮并按层合成。桌宠保持关闭，候选仍须经过正式动作验收后才能进入 PetWindow。

**Tech Stack:** Python/Pillow、本机 SDXL ComfyUI、Java 17/Java2D；Java 零第三方依赖。

**Spec:** openspec/changes/skeletal-pet-animation/design.md；specs/sprite-asset-pipeline/spec.md；specs/skeletal-animation/spec.md。

## Global Constraints

- 正式来源固定 assets/像素角色/master-transparent-800.png，裁剪 [139,81,664,696]，5 倍降采样，105×123。
- 已有 RGBA 不变，补片只写在前景完全遮住的缺失区域；原姿态重组必须无损。
- 不启动 pet.Main，不改变现有运行素材，不凭数值检查勾选完整美术验收。
- 检查根因：BodyRig 仍按矩形裁切；现有 ChainRig 只用于 RigPreview；初版候选未补齐衣发遮挡。
- 原生 worktree 工具返回 Git is unavailable，本轮使用现有仓库的 q-chibi-motion 分支隔离提交。

## Task 1: 衣发轮廓与遮挡补图

Files: assets/rig/q-chibi.layout.json、tools/build-q-parts.py、tools/test-q-parts.py、assets/rig/q-chibi-draft/。

Interfaces: split_visible(original, parts) 仍返回层与归属蒙版；新增部件语义采样点与 reconstruct 多边形，必须保证重组不变。

- [x] 对实际母图中长发、衣摆、袖子、腿的采样点编写回归断言，并先运行观察错误归属。
- [x] 修正轮廓与隐藏区域，增加部件 target 连通性检查；断段不能被“关节内部缺口 0”掩盖。
- [x] 在独立 _tmp/q-parts-motion 目录生成全部任务并查看结果；只导出通过数据校验的候选。
- [x] 连续重复构建/生成，比较目录结构与缺失区域外的像素；保留真实未通过的视觉问题。

## Task 2: Java 分层蒙皮离屏预览

Files: tools/export-q-rig.py、src/pet/LayeredRig.java、src/pet/LayeredRigPreview.java、必要的 Skeleton/Skin/MeshWarp 扩展及独立自检。

Interfaces: 导出 UTF-8 rig.properties；canvas=105,123；parts=有序部件名；layer.NAME.file/sha256/bones；bones=拓扑序；bone.NAME=父骨或空,x,y,endX,endY（原生绝对静止坐标）。Java 入口接受候选目录与输出目录，生成 rest.png、poses.png、frames/ 和 report.json。

- [x] 先验证限定骨头集合：仅腿部骨头移动时，杖／头发的顶点不得跟随；静止姿态不漂移。
- [x] 读取有 SHA-256 校验的分层素材；从部件定义导出骨架，避免沿用 BodyRig 的旧坐标。
- [x] 完成双腿关节、手臂、头发、双侧袖摆与衣摆的组合姿态预览；材质权重不能跨到其他部件。
- [x] 输出真实图像验证、循环帧、静止差异、求值/绘制耗时；演示驱动与正式动态验收明确区分。

## Task 3: 审查、记录与保存

- [x] 查看预览中的连续性、遮挡和裁切，记录发现，不能把缺陷藏在指标里。
- [x] 独立代码审查，修复重要问题，运行 Python/Java 检查和 OpenSpec 严格校验。
- [x] 同步 OpenSpec、素材说明、骨骼说明与 Word 日志；任务按实际验收状态勾选。
- [x] 按具体路径暂存并检查敏感信息/大文件，提交后将验证结果合入 main 并按已有授权推送。

## 阶段结果与未完成范围

本轮计划的候选修正、离屏绘制和证据归档已完成；这不等于 OpenSpec 美术与正式动作验收完成。
当前 11/29，3.2 的旧勾选已按运行链路证据恢复未完成。髋部遮挡、隐藏发丝、张开手掌和 PetWindow 接入仍须继续。
证据：assets/rig/q-chibi-draft/motion-preview/；Word 日志已增加第十四节。
