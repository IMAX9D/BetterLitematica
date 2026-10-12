# 第三方说明

## Sodium 的区段遍历规则

1.20.1 中的 `SectionVisibilityGraph` 参考并改写了 Sodium 0.5.13 的 `OcclusionCuller` 中向外遍历和角度裁剪的规则，作者为 JellySquid 及 Sodium 贡献者。

- [对应版本源文件](https://github.com/CaffeineMC/sodium/blob/mc1.20.1-0.5.13/src/main/java/me/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java)
- 许可：PolyForm Shield 1.0.0；完整文本保留于 [sodium-LICENSE.md](versions/review-1.20.1/src/main/resources/assets/betterlitematica/third-party/sodium-LICENSE.md)，并随相应 JAR 打包。

此项只改写遍历决策。区段数据、模型重建、绘制和资源生命周期仍由 Minecraft 原有流程处理；没有包含 Sodium 的 JAR，也不注册 Sodium 的模组标识。安装外部 Sodium 时，这组原版地形优化不加载。

## 拼音数据

拼音数据的来源及许可保留在各版本资源目录的 `pinyin-LICENSE.txt`，随模组离线提供。
