# 1.20.1 放置回归

该辅助模组在真实客户端调用打印机，使用普通放置协议，并从集成服务器读取最终方块状态。它不会被打包进 BetterLitematica。

## 构建

使用 Java 25 和项目 Gradle，在仓库根目录运行：

```text
gradle :fabric-1.20.1:build :fabric-1.20.1:remapPlacementRegression -I checks/printer-placement/init.gradle
```

辅助 JAR 默认为 `../output/printer-placement-check/placement-regression.jar`，可通过 `-PregressionRoot=绝对路径` 改变输出目录；`-PbuildRoot=绝对路径` 可隔离正式构建输出。

## 运行

1. 准备一个独立的 Minecraft 1.20.1 Fabric 测试实例，安装 Fabric API、待测 BetterLitematica 和辅助 JAR。
2. 在此实例创建名为 `fixture` 的超平坦单人世界，退出游戏。辅助模组会清空投影并改写这个世界的 `x/z=-2..6, y=3..8` 区域。
3. 添加 JVM 参数 `-DplacementCheck.enabled=true -DplacementCheck.root=结果目录`，启动客户端并保持游戏窗口前台。它自动打开世界、运行打印、保存 `result.json` 和 `scene.png` 后退出。
4. 对角连接场景另装 Diagonal Fences 8.1.5、Diagonal Windows 8.1.5、Puzzles Lib 8.1.33 和 Forge Config API Port 8.0.3；两组使用各自独立的世界副本。
5. 旧版对照可添加 `-DplacementCheck.rulesOnly=true`，仅检查放置规则，不运行打印。0.4.17 的失败结果属于预期对照。

场景包括 22 个目标格：栅栏、玻璃板、墙、四向打开的栅栏门、打开的木门与活板门、含水栅栏，以及以非完整方块为支撑的石英块。完成后继续检查 60 tick，操作计数不得增长。错误材质、朝向、上下半部及铁活板门等负例单独验证。

门的开关、朝向和含水最终状态严格检查；邻接生成的连接形状允许随世界变化。楼梯形状和栅栏门 `in_wall` 覆盖规则检查；当前真实打印场景不包含楼梯拐角或嵌墙栅栏门。
