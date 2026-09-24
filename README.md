# BetterLitematica · Minecraft 1.20.1 / Fabric

独立实现的投影模组，不 fork、不复制 Litematica 代码、不依赖 MaLiLib。行为对照固定为 Litematica 0.15.3（Minecraft 1.20.1）。**0.3.55-dev 仍未达到全部功能和逻辑等价。** 逐项差距见 [功能对照](docs/LITEMATICA-PARITY.md)。

源码仓库仅包含代码、文档和生成的演示投影；游戏存档、个人投影、缓存、构建产物和本地验收资料不纳入版本管理。

## 当前验证

Windows / JDK 17：119 项核心测试、562,980 次断言及 327,459 项适配检查通过，Fabric 构建成功。0.3.54 的轮盘独立开关和仅挖掘已有隔离游戏验证；0.3.55 的总渲染入口调整已通过构建与自动检查，未追加游戏内验收。历史证据和边界见 [VALIDATION.md](VALIDATION.md)，其中 `validation/` 路径为本地资料，不随仓库上传。

产物：`fabric-1.20.1/build/libs/betterlitematica-fabric-1.20.1-0.3.55-dev.jar`。需要 Minecraft 1.20.1、Fabric Loader 和 Fabric API。没有其他版本的兼容性声明。

## 启动与文件位置

双击本项目的 `Start-Test.cmd` 启动开发测试客户端。先设置 JDK 17 的 `JAVA_HOME` 与 `PATH`；启动脚本兼容现有本机 JDK 路径。进入世界后按 **M**；退出菜单按 Esc。

开发实例投影目录：`fabric-1.20.1/run/schematics`。普通启动器实例使用对应游戏目录的 `schematics`。不要把开发实例目录和正式实例目录混用。

## 验收与打印机

现有视觉和项目合格性两项独立审查；结论、修复证据及尚未通过的门槛见 [项目验收](docs/PROJECT-ACCEPTANCE.md)。打印机在主菜单进入，M+B 打开，CapsLock 开始/暂停，Ctrl+CapsLock 停止。当前仍在补齐参考功能，已实现范围与差距见 [打印机对照](docs/PRINTER-PARITY.md)，分版本实机验证范围见验证记录。

0.3.38 对照四改调整打印执行链：有限材料批次、现有任务优先、单人创造主背包同 tick 取料、复用放置面并减少重复合法性检查；日志聚合实际耗时和等待。见 [打印吞吐对照](docs/PRINTER-0.3.38.md)。逐格消隐沿用 0.3.37。

## 已接入的操作

| 入口 | 当前实现 |
|---|---|
| M 主菜单 | 左侧当前投影列表（选择、状态、分页），右侧为功能与工具入口；独立字体叠加层 |
| 加载/摆放 | .litematic v5/v6、Sponge .schem v2/v3、原版 .nbt、旧 .schematic；最多 8 个摆放；移动、旋转、镜像、复制、名称、锁定、重载；按世界/维度恢复 |
| 材料/校验 | 分步扫描、暂停、忽略、错误位置样本/描边；按物品计数、背包比较、过滤、倍数、组/盒换算、HUD、CSV 导出 |
| 选区 | 简单/多盒模式、角点、原点、木棍工具、滚轮微调、按世界保存；分区捕获、临时投影及新 .litematic 保存 |
| 空间编辑 | 在投影中放置、删除、替换、取样；单格/直线，子区域与分层约束；撤销重做、草稿恢复、新文件关联保存；[范围与验证](docs/EDITING-0.3.34.md) |
| 文件编辑/转换 | 精确状态或方块类型替换，保留未修改源标签；单区域转换为 .schem / .nbt；始终另存新文件 |
| 创造任务 | 单人服务端分步粘贴、NBT/实体选项、替换规则；选区填充/替换/删除；暂停或取消 |
| 多人命令 | 服务器授予相应命令权限且创造模式时发送限速 setblock/fill、NBT 回填和实体命令；可导出 .mcfunction |
| 施工辅助 | 投影拾取、自动选料、简单放置、按住连续放置、基础放置限制；M+E 切换简单放置（默认关闭） |
| 项目快照 | 独立保存源文件与摆放设置；列出版本；原位恢复或新建摆放，保留其他摆放 |

## 快捷键与工具

按住 Tab 打开轮盘：一级切换总渲染或进入模式选择，二级打印/挖掘/排流体/填充为可叠加的独立开关，松开 Tab 收起。隐藏投影不停止打印。

默认 M 打开菜单，M+P 摆放，M+L 材料，M+V 校验，M+S 选区，M+C 设置，M+R 总渲染，M+T 选区工具开关，PageUp/PageDown 调整分层。组合键可在设置中修改；M 单键在松开时打开，避免与组合键冲突。

工具物品默认木棍，可在设置中修改；持工具时 Ctrl+滚轮切换选区/摆放模式，Alt+滚轮沿视线方向微调。选区模式左右键设置角点；摆放模式左右键设置摆放原点。材料总量来自完整投影文件；世界校验独立分步扫描，未加载位置延后处理。校验会增量复查已加载世界变化，未知位置延期重试。

## 命令示例

所有带空格的参数及复杂方块状态请用双引号包围。

| 命令 | 行为 |
|---|---|
| `/bl load demo.litematic` | 新建摆放 |
| `/bl here`、`/bl move 100 64 200` | 设置选中摆放的原点 |
| `/bl rotate 90`、`/bl mirror x` | 设置旋转或镜像，先镜像后旋转；镜像支持 none/x/z/xz |
| `/bl layer y 64 64`、`/bl layer all` | 世界坐标分层 |
| `/bl verify`、`/bl materials` | 启动扫描或打开材料页面 |
| `/bl area room`、`/bl pos1`、`/bl pos2` | 创建盒子并以玩家位置设置角点 |
| `/bl capture room-copy` | 捕获选区到新 .litematic |
| `/bl replace "minecraft:stone" "minecraft:glass" "glass-copy"` | 替换选中源文件的精确状态，另存 .litematic |
| `/bl convert schem copy`、`/bl convert nbt copy` | 单区域格式转换 |
| `/bl fill "minecraft:stone"` | 单人创造模式填充选区 |
| `/bl replaceworld "minecraft:stone" "minecraft:glass"` | 单人创造模式替换选区中的指定状态 |
| `/bl deletearea` | 单人创造模式清空选区方块 |
| `/bl mcfunction build-copy` | 导出方块及 NBT 命令 |
| `/bl pastecommands none` | 创造模式合法命令粘贴；none 仅空气、non_air 忽略源空气、all 全部 |
| `/bl tasks`、`/bl projects`、`/bl settings` | 打开相应管理页面 |

取消写入任务只停止后续步骤，已经修改的世界内容不会回滚。多人命令是否执行以服务器反馈为准。任务不绕过服务器权限。

## 当前明确限制

全功能平替仍未完成。无限规模统一编辑、完整移动工作流、告示牌文本、全部配置与高级打印机兼容仍有缺口。临时投影可在选区页创建，另存 .litematic 后保留当前摆放；未保存的临时来源不在退出后恢复；进入编辑的临时来源会先建立持久底本。

同源解码缓存共享 32 MiB。所有摆放共用 1 GiB 顶点缓冲 / 65536 个 GPU 对象预算，包含在建和退休未释放资源；单摆放方块缓存仍不超过 512 MiB，活动区段 8 MiB。共享索引、合成帧缓冲及驱动开销不包含在上述顶点预算中。最多 8 个摆放，源资源目录为 16 份/估算 1 GiB。临时完整源另有 16 份/256 MiB 压缩预算。特殊模型/实体有数量和字节上限。这些不是实际显存或 FPS 的保证。

已有隔离游戏验证不代表所有投影、多人服务器或模组组合均已通过。开发、构建和使用边界详见 [验证记录](VALIDATION.md) 与 [合格性验收](docs/PROJECT-ACCEPTANCE.md)。


## 从源码构建

需要 JDK 17 和网络连接。脚本会下载并校验固定版本的 Gradle 8.6。

```powershell
./scripts/test-core.ps1
./scripts/build.ps1
```

Linux/macOS 对应 `bash scripts/test-core.sh` 和 `bash scripts/build.sh`。成功构建的可安装 JAR 位于 `fabric-1.20.1/build/libs/`，该目录只保留最新版本。

## 目录

- `core/`：不依赖 Minecraft 的核心模型与算法。
- `blueprint-io/`：投影文件读写。
- `runtime/`：缓存与后台任务。
- `fabric-1.20.1/`：游戏适配、渲染、界面和打印机。
- `selftest/`：核心回归测试。
- `scripts/`、`.github/workflows/`：构建、测试与 CI。
- `docs/`、`VALIDATION.md`：设计、功能差距与验证记录。
- `examples/`：生成的演示文件。

`Install-Workspace.ps1` 和 `MANIFEST.sha256` 是旧源码压缩包的本地安装资料；Git 克隆无需使用。
