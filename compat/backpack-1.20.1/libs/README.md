# 本地构建依赖

在此放入 `backpack-retrieve-original.jar`、`betterlitematica-reference.jar`（正式 1.20.1 / 0.4.17）、`trashslot-reference.jar`（15.1.1）、`inventoryprofilesnext-reference.jar`（1.10.20），以及 Fabric API 0.92.7 的嵌套模块 JAR。原模组与依赖二进制不提交到源码仓库。

`build.ps1` 另外接收 Minecraft 1.20.1 intermediary 合并 JAR 和游戏 libraries 目录。正式包的版本、输入与输出哈希见上级 `release-manifest.json`。
