# BetterLitematica

面向 Minecraft Fabric 的独立投影与建造辅助模组，集成投影查看、编辑、材料统计和打印功能，不依赖 Litematica 或 MaLiLib。当前发布版本为 **0.4.1-dev**。本分支提供 **1.20.1 / 0.4.2-review.1 审查版**，本轮修复尚未推广到其他版本。

## 核心功能

- **投影管理**：加载多个投影，独立移动、旋转、镜像，按层或方块类型过滤显示。
- **建造辅助**：创造／生存打印、简单放置、创造粘贴，以及容器填充等施工工具。
- **实时待建列表**：显示施工范围内尚未完成的投影方块，暂停打印时继续更新；每行四项，自动换行，不扣除背包材料。
- **选区与编辑**：选区保存、三轴拖动、投影编辑与项目版本管理。
- **材料与校验**：完整投影材料统计、世界差异检查，以及方块和容器信息查看。
- **统一界面**：瓷白／曜石双主题、快捷轮盘、自定义快捷键和展开动画。

## 支持版本与安装

支持 Minecraft **1.18.1–1.18.2、1.19–1.19.4、1.20–1.20.6、1.21–1.21.11、26.1、26.1.1、26.1.2、26.2、26.3**，共 31 个版本，各有独立 JAR。具体依赖见 [targets.json](targets.json)。

安装 Fabric Loader **0.19.5 或更高版本**及对应版本的 Fabric API，将匹配游戏版本的一个 JAR 放入 `mods/`，投影文件放入 `schematics/`。

运行环境：1.18.1–1.20.4 使用 Java 17，1.20.5–1.21.11 使用 Java 21，26.x 使用 Java 25。通过主菜单加载投影，操作按键可在“设置与快捷键”中调整。

## 默认操作

| 操作 | 默认按键 |
| --- | --- |
| 主菜单 | `M` |
| 快捷轮盘（同时保留玩家列表） | 按住 `Tab` |
| 开始／暂停打印 | `Caps Lock` |
| 停止打印 | `Ctrl + Caps Lock` |
| 总投影渲染开关 | `M + R` |
| 打印机设置 | `M + B` |

组合键先按住 `M` 再按另一键。已有配置和自定义按键会保留，以“设置与快捷键”显示的绑定为准。

- 方块过滤：投影行的配置 → 方块显示，选择黑名单或白名单后勾选投影内方块；搜索可用名称或 ID，“全选搜索结果”仅作用于当前搜索结果，最后点“保存”。未保存返回会要求再次确认。
- 预览：左键拖动旋转，中键拖动平移，滚轮缩放，双击或复位按钮恢复视角。
- 1.20.1 审查版：“周围待建 HUD”对新配置默认关闭，开启后即使停止打印也持续更新，统计所有未完成位置，不扣除背包。橙框表示含错误方块或状态；溢出不自动翻页，按住轮盘键、悬停卡片后滚轮翻页。已有开启偏好不变。
- 缺料徽章独立于待建列表：生存施工等待材料时显示“缺料”，补料后随实际施工状态恢复。
- 独立破基岩队列与打印机的破基岩模式互斥。审查版中切换只暂停另一方并保留独立队列；只有“清空独立队列”会丢弃目标。设置页可以启动队列，返回游戏后才施工。

## 与其他模组共存

本模组会接管下面的功能，不会改写它们的配置文件；移除 BetterLitematica 后接管解除。

| 模组 | 接管内容 |
| --- | --- |
| Litematica | 投影渲染、键盘鼠标输入、快捷键、简易放置 |
| litematica-printer | 打印逻辑和打印快捷键 |
| Tom’s Storage | 内置打印逻辑和打印快捷键；存储功能保留 |
| Tweakeroo | 与施工冲突的放置、自动点击和换物品功能，见下文 |

**1.20.1 审查版**首次进入世界检测到这些模组时，会在聊天栏提示一次；“设置与快捷键 → 交互设置 → 模组共存”可随时查看。Tweakeroo 拦截仅在打印运行、独立破基岩启用或本模组执行放置动作时生效，停止／暂停后恢复其原设置；普通手动游玩不再全时关闭这些功能。本次还修复了可选模组兼容钩子的类名格式识别问题；其他游戏版本尚未应用这些修复。

临时拦截项：精确／灵活／快速放置、放置网格与限制、假潜行放置、放置后点击、放置前取块、手持补货、自动换工具，以及快速／按住／周期点击。对应配置键：`TWEAK_ACCURATE_BLOCK_PLACEMENT`、`TWEAK_AFTER_CLICKER`、`TWEAK_FAKE_SNEAK_PLACEMENT`、`TWEAK_FAST_BLOCK_PLACEMENT`、`TWEAK_FAST_LEFT_CLICK`、`TWEAK_FAST_RIGHT_CLICK`、`TWEAK_FLEXIBLE_BLOCK_PLACEMENT`、`TWEAK_HOLD_USE`、`TWEAK_PERIODIC_USE`、`TWEAK_PERIODIC_HOLD_USE`、`TWEAK_PLACEMENT_GRID`、`TWEAK_PLACEMENT_LIMIT`、`TWEAK_PLACEMENT_RESTRICTION`、`TWEAK_PLACEMENT_REST_FIRST`、`TWEAK_PLACEMENT_REST_HAND`、`TWEAK_PICK_BEFORE_PLACE`、`TWEAK_HAND_RESTOCK`、`TWEAK_TOOL_SWITCH`。

## 源码结构与构建

`core/` 提供数据模型与算法，`blueprint-io/` 负责投影读写，`runtime/` 负责缓存与后台调度；`fabric-1.20.1/`、`fabric-official/` 和 `versions/` 负责各游戏版本的适配。

构建需要 **JDK 25 或更高版本**。Windows 使用 `./scripts/build.ps1`，Linux/macOS 使用 `bash scripts/build.sh`，默认构建 1.20.1；指定其他版本时追加任务，例如 `:fabric-1.21.11:build`。全部版本分别使用 `-AllVersions` 或 `--all`。

核心回归检查位于 `core/src/test/java`，运行 `bash scripts/build.sh :core:selfTest`（Windows 可用 `./scripts/build.ps1 :core:selfTest`）。CI 单独执行这些检查，失败会使流水线失败；测试代码不打入模组 JAR。

项目仍在开发中。施工遵守服务器权限与物品规则，不保证任意模组组合均兼容；26.3 在不加载本模组的 Fabric 对照中也观察到偶发 JVM 原生崩溃，该环境问题尚未确认解决。
