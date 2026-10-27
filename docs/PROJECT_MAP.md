# 项目结构

| 目录 | 内容 |
| --- | --- |
| `core/` | 无 Minecraft 依赖的投影数据、几何、编辑、施工队列、预算与 UI 数据逻辑；回归测试也在这里 |
| `blueprint-io/` | NBT、投影格式、缓存、导入导出、预览读取与动态内存预算 |
| `runtime/` | 运行时通用能力、文件对话框等平台桥接 |
| `fabric-1.20.1/` | Yarn 系列基础客户端实现、资源和适配检查 |
| `fabric-official/` | 26.x 官方映射系列基础实现与 GPU 接口 |
| `versions/` | 按游戏 API 差异覆盖必要文件；组合顺序由 `targets.json` 指定 |
| `gradle/`、`scripts/` | 组合实际源码和资源、构建与检查每个游戏目标 |
| `compat/backpack-1.20.1/` | 独立背包补货兼容包源码和测试；不属于 BetterLitematica 主 JAR |
| `docs/performance/` | 各阶段完整测试报告、图表与统计数据 |
| `docs/testing/` | 跨阶段测试索引、验证摘要、机器可读结果 |
| `docs/releases/` | 正式版本功能推广与 JAR 校验清单 |

## 构建入口

JDK 25 或更高版本。Windows：`./scripts/build.ps1`；Linux/macOS：`bash scripts/build.sh`。默认目标 1.20.1，追加 `:fabric-26.3:build` 等任务可指定版本；`-AllVersions` / `--all` 构建全部 31 个目标。

`core:selfTest` 运行核心回归。每个适配目标的 `check` 会检查设置、连续放置、用户流程、投影管线、原生地形 Mixin，以及新推广功能的实际编译调用和着色器资源。

## 当前推广状态

0.4.17 的公共功能已进入基础源码与共享模块，31 个目标全部构建。1.20.1 临时审查层仅剩游戏 API 所需 access widener；没有仍需合并的 review 功能层。投影渲染、原生地形、打印与其他施工模式、选区和编辑、材料与校验、预览、界面及共存功能均随各版本构建。

backpack 独立包继续限定 1.20.1，适配 BetterLitematica 0.4.17；其“满库存、队列轮换、垃圾桶、IPN 协调”不会进入其他版本本体。

本地游戏、个人投影、存档、账户配置、网络凭据和构建缓存不进入源码仓库。成品与原始数值测试资料通过 GitHub Release 提供。
