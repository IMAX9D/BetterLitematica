# BetterLitematica

Minecraft **1.20.1 / Fabric** 的独立投影与建造辅助模组，当前版本 **0.3.83-dev**。不依赖 Litematica 或 MaLiLib，仍在开发中，尚未实现原模组的全部功能。

## 安装与使用

需要 Java 17、Fabric Loader 0.15.11 或更高版本，以及适用于 Minecraft 1.20.1 的 Fabric API。将构建出的 JAR 放入游戏实例的 `mods/`，投影文件放入同实例的 `schematics/`。

- 多投影加载、独立摆放、旋转镜像、分层显示及可旋转的缓存预览。
- 完整源文件材料统计、动态校验、方块信息与容器内容展示。
- 木棍工具、选区保存、三轴拖动、简单放置和可叠加施工模式。
- 创造粘贴、创造/生存打印及容器填充；多人未加载区块排队等待，施工遵守服务器权限与物品规则。

| 默认操作 | 功能 |
| --- | --- |
| `M` | 主菜单 |
| 按住 `Tab` | 快捷轮盘；松开关闭，点击层数输入框后可松手输入 |
| `Caps Lock` / `Ctrl + Caps Lock` | 开始或暂停打印 / 停止打印 |
| `M + R` | 总渲染开关；隐藏投影仍可打印 |
| 木棍 + `Ctrl` 滚轮 | 切换工具模式 |
| 对准三轴，按住中键拖动 | 预览移动，松开确认，`Esc` 取消 |

快捷键可在设置中修改。轮盘“执行操作”使用当前木棍模式，执行快捷键默认未绑定。材料清单和校验位于各投影的配置页。

左上打印徽章在启用时平滑展开“打印中”，暂停或停止后收回；木棍信息靠左下边缘显示并避让热栏，两种主题均适用。

每个投影的“配置 → 显示过滤”支持方块白名单／黑名单，可按名称或 ID 搜索全部已注册方块（含模组方块），按“保存”应用。两种名单独立保存，切换模式、批选和清空不会修改另一种名单。白名单为空时不显示方块；关闭过滤会保留两套勾选。旧版共享勾选迁入当时使用的模式（关闭状态迁入黑名单），另一套初始为空。只影响世界中的投影方块显示，不改变材料统计、校验、打印、粘贴和导出的数据。

投影预览采用简化几何，不等同完整游戏材质。未加载世界状态按未知处理；取消写入只停止后续步骤，不回滚已经修改的方块。跨版本、全部特殊方块和任意模组组合尚未全面验证。

## 从源码构建

准备 JDK 17，将 `java`、`javac` 加入 PATH。构建脚本会下载并校验固定版本的 Gradle 8.6。

```powershell
./scripts/test-core.ps1
./scripts/build.ps1
```

Linux/macOS 使用 `bash scripts/test-core.sh` 和 `bash scripts/build.sh`。中文界面及字体检查需要系统中文字体，例如 Noto Sans CJK；模组不附带字体。

产物位于 `fabric-1.20.1/build/libs/`，完整构建成功后只保留最新可安装 JAR。Windows 开发客户端可通过 `Start-Test.cmd` 启动，游戏目录为 `fabric-1.20.1/run/`；该脚本使用 JAVA_HOME 或 PATH 中的 JDK。也可在 `.tools/java-home.txt` 第一行填写本机 JDK 目录，无需修改系统环境；该文件不会提交到 Git。`Start-Test.cmd --dry-run` 可检查启动任务而不打开游戏。

构建默认执行核心回归和 Fabric 适配检查。已有隔离游戏专项验证不代表所有服务器、投影或整合包均已通过。

## 目录

| 目录 | 内容 |
| --- | --- |
| `core/` | 与 Minecraft 无关的数据模型与算法 |
| `blueprint-io/` | 投影文件读写 |
| `runtime/` | 缓存、调度与后台任务 |
| `fabric-1.20.1/` | 游戏适配、渲染、界面与施工 |
| `selftest/` | 核心回归测试 |
| `scripts/`、`.github/` | 构建脚本与 CI |
| `examples/` | 自动生成的演示投影 |

仓库只保存源码、测试、构建配置和这份说明及 [开发约束](AGENTS.md)。个人投影、存档、日志、缓存、工具二进制及本地验收资料不入库。

格式与行为参考：[Litematica](https://github.com/maruohon/litematica)、[litematica-printer](https://github.com/water2004/litematica-printer)、[Sponge Schematic Specification](https://github.com/SpongePowered/Schematic-Specification)。第三方依赖遵循各自许可证；本项目尚未指定公开发布许可证。
