# backpack 1.20.1 独立兼容包

适配 BetterLitematica **0.4.17**，保留原 backpack 的虚空取物功能，增加打印机的批量备货、库存轮换、垃圾桶回收、换手同步与 IPN 协调。本目录不被 BetterLitematica 的 Gradle 矩阵打包。

组合：Minecraft 1.20.1、TravelersBackpack 9.1.50、TrashSlot 15.1.1、Balm 7.3.38；IPN 1.10.20 可选。构建标识为 `2026-10-25-full-stock.5-bl0.4.17`。

## 库存策略

按可用打印槽位备货。短队列不会再将取料量限制为即时缺口；空槽达到阈值再批量补满，急需材料可提前触发。材料种类较多时用有限 FIFO 轮换，避免 100 格手长的远处候选反复推翻当前库存。普通放置跟踪没有逐次扫描整个队列或分配集合。

按用户选择允许回收当前队列不用的普通无自定义数据建材；保护工具、背包、自定义物品、保护栏和当前使用物品。垃圾桶操作等待源槽与垃圾桶同步，未知结果不重复删除。IPN 自动补充仅在打印期间暂缓，结束恢复，不改它的配置。

## 构建和检查

先按 [libs/README.md](libs/README.md) 准备本地依赖，再执行：

```powershell
./build.ps1 -Jdk 'C:/path/to/jdk-21' -MinecraftJar 'C:/path/to/minecraft-merged-intermediary-1.20.1.jar' -GameLibraries 'C:/path/to/.minecraft/libraries'
./test.ps1 -Jdk 'C:/path/to/jdk-21'
```

输出 `dist/backpack-retrieve-1.0.0.jar`。构建从原 backpack JAR 加入兼容代码，不包含 BetterLitematica 本体。检查覆盖库存策略、批次、真实垃圾桶编解码、反射桥接、Mixin 转换与 Fabric 联合回包处理。

安装时退出游戏、备份旧同名文件，再替换。只用于上述 1.20.1 组合；可通过 `/backpack-printer status` 查看状态。
