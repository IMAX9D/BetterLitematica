# 源码结构与构建

| 目录 | 内容 |
| --- | --- |
| core/ | 投影模型、几何、编辑、施工队列与工作预算 |
| blueprint-io/ | NBT、投影格式、预览和磁盘缓存 |
| runtime/ | 后台加载、文件对话框及持久化 |
| fabric-1.20.1/ | Yarn 系列客户端与资源 |
| fabric-official/ | 使用官方映射的客户端与渲染资源 |
| versions/ | 各游戏版本的 API 差异，按 targets.json 中的顺序组合 |

使用 JDK 25。Windows 运行 `./scripts/build.ps1 :fabric-1.21.1:build`，Linux 或 macOS 运行 `bash scripts/build.sh :fabric-1.21.1:build`。将版本号换为目标游戏版本即可。构建全部版本分别使用 `./scripts/build.ps1 -AllVersions` 和 `bash scripts/build.sh --all`。每个目标的成品位于该模块的 build/libs 目录。
