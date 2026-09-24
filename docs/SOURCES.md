# 技术参考与依赖来源

以下用于格式/API核对，不等于完成过真实依赖编译。项目没有复制原 Litematica 源码或依赖 MaLiLib。

- Fabric 开发配置：https://fabricmc.net/develop/
- Fabric Java/环境说明：https://wiki.fabricmc.net/tutorial:setup
- Fabric Loom 1.6.12：https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.6.12/
- Fabric Loader 0.15.11：https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.15.11/
- Fabric API 0.92.2+1.20.1：https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.92.2+1.20.1/
- Yarn 1.20.1+build.10 VertexBuffer：https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/net/minecraft/client/gl/VertexBuffer.html
- Yarn BufferBuilder：https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/net/minecraft/client/render/BufferBuilder.html
- Yarn BakedQuad：https://maven.fabricmc.net/docs/yarn-1.20.1+build.10/net/minecraft/client/render/model/BakedQuad.html
- Fabric 1.20.1 世界渲染上下文：https://raw.githubusercontent.com/FabricMC/fabric/1.20.1/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext.java
- Sponge schematic v2：https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-2.md
- Sponge schematic v3：https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-3.md
- Litemapy 文件区域与负尺寸说明：https://litemapy.readthedocs.io/en/latest/litematics.html
- Gradle 官方校验值：https://gradle.org/release-checksums/

锁定 Gradle 8.6 binary distribution SHA-256：

```text
9631d53cf3e74bfa726893aee1f8994fee4e060c401335946dba2156f440f24c
```

第三方依赖保持各自许可证；本包没有捆绑 Minecraft 游戏二进制、Gradle 二进制或任何字体文件。尚未替用户决定公开发布许可证。
