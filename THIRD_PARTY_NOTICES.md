# 第三方说明

## Sodium 的区段遍历规则

1.20.1 的早期实验版本曾通过 `SectionVisibilityGraph` 参考并改写 Sodium 0.5.13 的 `OcclusionCuller` 中向外遍历和角度裁剪的规则，作者为 JellySquid 及 Sodium 贡献者。review.23 保留遍历优化入口，但改为独立实现的原版等价采样与同轮成功路径复用，不再使用此前的方向和主轴角度裁剪；这里保留历史来源与许可证记录。

- [对应版本源文件](https://github.com/CaffeineMC/sodium/blob/mc1.20.1-0.5.13/src/main/java/me/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java)
- 许可：PolyForm Shield 1.0.0；完整文本保留于 [sodium-LICENSE.md](versions/review-1.20.1/src/main/resources/assets/betterlitematica/third-party/sodium-LICENSE.md)，并随相应 JAR 打包。

review.24 的 `TerrainRegions`、`TerrainBuffer`、`TerrainUploadQueue`、`TerrainWorkers` 及相应注入入口为独立实现：采用后台构建、区域共享缓冲与批量提交的架构思路，具体缓冲分配实现延续本项目的 `ProjectionPages` 和 `RangeAllocator`，未复制或改名打包 Sodium 的对应实现。Minecraft 的模型、流体、排序与着色器接口继续作为适配边界。

后续 `TerrainTraversal` 重新实现了六面连通、向外广度搜索、距离与视锥提前过滤的完整可见性流程，取代原版的可见区块遍历。架构参考上述 Sodium 0.5.13 源文件，代码独立编写：使用本项目的数组队列、按列邻接步长、保守候选缓存、加载边界依赖及仅封闭视线时的合并刷新，不包含 Sodium 的主轴角度掩码或对应源代码。未构建区段按可通视处理。

模组没有包含 Sodium 的 JAR，也不注册 Sodium 的模组标识。安装外部 Sodium 时，全部原生地形入口（包括上述新增管线）不加载。

## 拼音数据

拼音数据的来源及许可保留在各版本资源目录的 `pinyin-LICENSE.txt`，随模组离线提供。
