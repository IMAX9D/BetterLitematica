# 当前架构：实现而非远期功能清单

```text
.litematic / .schem（源文件只读）
    -> SchematicImporter + 受限 NbtReader
    -> BlueprintCache：元信息 / 分块独立 deflate / CRC / 目录 / 状态计数
    -> LoadCoordinator：代际、取消、后台索引、单次所有权转移
    -> SpatialIndex + SectionStreamer：按需解码、有界完成队列
    -> Fabric 1.20.1 状态与模型适配（游戏/渲染线程）
    -> 分帧网格构建 / VBO 缓存 / 当前可见绘制
```

`BlueprintMetadata`、`Region`、`BlockStateSpec`、`PackedSection` 都不引用游戏类。`PlacementTransform` 与显示层是独立值对象，客户端支持最多 8 个独立摆放；尚未实现共享投影资源管理器。

## 存储

每个 Section 固定 16³，单个位置用局部调色板索引；局部调色板映射资产全局状态表。坐标顺序 x + 16*z + 256*y。空气 Section 不存 payload，但所属区域边界保留，后续校验可以区分“预期空气”和“不属于投影”。

BPC v1 是内部预览缓存，不是公开无损投影标准。源 SHA-256 用于身份与缓存复用。每个 payload 压缩独立并有原始数据 CRC；打开检查目录和范围，按需读取时检查实际 payload。元信息/索引常驻，因此总内存并不严格独立于源规模。

缓存包含文件头、元信息、Section payload、目录及方块状态计数。写入同目录临时文件，完成后替换缓存，不修改源文件。异常取消清理临时文件。这个版本不保存实体、方块实体等，不能从缓存反向声称源数据已被完整捕获。

## 调度

导入队列为 1，取消会提升 generation；旧任务不能覆盖状态和结果。发布只读缓存句柄与索引后，由主线程接管生命周期。解码 2 worker，队列 32，完成队列 16；每项最大尺寸也有格式限制。请求 ID 用 epoch+serial，避免旧任务删除新任务的 pending 标记。

CPU 缓存按逻辑字节预算管理；GPU 缓存额外限制条目数，避免大量空网格无限积累。卸载不等待整份后台工作完成，但可等待一次受限块读取释放文件锁；不能把它描述为绝对零阻塞。

## 渲染

模型获取和网格生成先放在渲染线程，采用有限步数与时间检查；没有证明可线程安全的游戏 API 不上 worker。生成后的几何保持在 VBO 中。层/方向/资源包变化会失效网格；纯平移在不切层时可以复用局部网格。材质使用原版 atlas 与 baked model 数据。

透明度与深度策略是开发实现，只有 Section 级排序。跨 Section 面剔除、完整环境相关模型、FRAPI、动态实体、流体仍待实现。当前只有主视野附近有预算的部分参与绘制，不是全建筑渲染能力的最终状态。

## 版本隔离

目前真正存在的版本边界是所有 MC 导入都在 `fabric-1.20.1`。尚没有通过第二版本验证的通用平台接口，不提前承诺 API 完全正确。未来新版本先接相同核心，发现重复适配逻辑后再抽稳定接口。

## 0.3.0-dev 新增任务与数据通道

多摆放最多 8 个；每个拥有独立 SectionStreamer 与预览网格，当前没有共享加载资源实体。PlacementStore 按世界/维度持久化源路径和变换；SessionIo 用单 worker、32 项队列串行处理配置、浏览、导出与源数据读取。

新增完整数据通道独立于 BPC：SchematicDocument 从原文件读取状态、方块实体、实体和计划刻；LitematicEdit 保留未修改标签并另存；SchematicFormats 转换原版结构与 Sponge；NbtWriter 有类型、深度、节点、输出预算和新文件保护。

WorldCapture 在世界所属线程每次至多 512 格 / 2 ms，附加数据按区块分步；普通 worker 只接收脱离游戏对象的记录。捕获总格数 4,194,304，附加 NBT 使用 64 MiB 逻辑预算；它不是整个游戏进程内存上限。

PlacementAnalysis 在客户端每次至多 512 格 / 2 ms；未解码等待，未加载世界区块与未知状态记 UNKNOWN。VerificationReport 最多 4096 分组和 2048 坐标样本，覆盖层最多绘制 256 个。材料是映射到物品的需求，不是合成配方原料。

CreativePasteTask / CreativeFillTask 只在集成服务器的世界线程写入，逐 tick 检查玩家创造模式/维度。任务取消以代际及同步发布阻止旧任务重挂，取消后已有世界修改保留。CommandOperation 经服务器命令树检查权限，最多 8 条方块命令/tick；文件输出使用独立有界队列与 128 MiB 上限。

InteractionOptions 保存组合键和基础施工选项；Mixin 仅位于专用 fabric.mixin 包。adapterCheck 使用 Fabric Knot 加载并变换目标类，然后运行真实注册表测试，不调用游戏主循环或打开窗口。ProjectVersions 保存不可变源副本和摆放设置，恢复为新实例。

尚未对齐的功能与行为以 LITEMATICA-PARITY.md 为准；本轮游戏验收由用户之后统一进行。


## 0.3.1 渲染通道更新

ProjectionComposite 在主帧缓冲之外持有投影颜色/深度，复制场景深度后绘制所有实例的最近表面，最终合成一次；主世界深度不被投影改写。它不等同于原版多层透明材质算法。

SectionNeighborhood 固定持有中心和六个面邻域解码结果；准备阶段等待邻居，避免区段接缝。WeightedLru.tryPutWithoutEviction 与可见工作集释放分离，避免显存预算满时循环重建。具体预算、随机千万方块样本和未验证项见 RENDERING-0.3.1.md；前文旧区段排序说明为此前实现。

## 0.3.5 独立菜单 UI

MenuScreen -> IndependentUi -> 物理像素矩形 / 1:1 文字纹理；OutlineFont 在单 worker 生成最终字号的抗锯齿图片，纹理仅由渲染线程上传/释放。OverlayTextField 使用原生编辑模型但不用原生字体。普通菜单已无 TextRenderer 绘制路径。后续界面沿用这一叠加层，预算、生命周期和已验证边界见 INDEPENDENT-UI.md。
