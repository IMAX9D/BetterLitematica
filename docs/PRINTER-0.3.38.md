# 0.3.38：打印执行链与四改对照

用户指出静止打印也慢，本批重新检查整个消费链，不能用上一版移动修复代替吞吐验收。

## 参考证据

重新读取 water2004/litematica-printer，HEAD 仍为 `4b62a1166bdce57bd7a2244959b1ba29571d1f38`。源码位于项目外只读参考目录 `D:/BetterLitematica/reference/litematica-printer-fourth`，未加入编译依赖。版本目录是 26.x，只取行为理解，不冒充 1.20.1 的已验证实现。

- [JobPool](https://github.com/water2004/litematica-printer/blob/4b62a1166bdce57bd7a2244959b1ba29571d1f38/core/src/main/java/me/aleksilassila/litematica/printer/core/job/JobPool.java)：从发布的有限批次接管事务桶，取完桶再轮转；生产者持续补充下一批。
- [Module](https://github.com/water2004/litematica-printer/blob/4b62a1166bdce57bd7a2244959b1ba29571d1f38/versions/26.2/src/main/java/me/aleksilassila/litematica/printer/handler/Module.java)：消费者有自己的执行时间预算，同种任务在数量额度内批量处理；必要事务会等待并续作。
- `Print.getSearchTransactionKey` 保留缺失方块候选，执行时实时找支撑；不会因为快照时没有支撑就提前丢掉整页的后续格。
- `PlacementGuide` 依据方块类型构造动作，`ActionManager` 执行准备好的交互；可选 packet 路径不等于绕过服务器验证。
- `InventoryUtils` 的创造取料也可能返回 WAITING。参考并非取消所有确认，本批仍保留多人/生存/已在途真实换槽的回执等待。

## 独立实现变化

| 原路径 | 当前路径 |
|---|---|
| 每取出 64 项触发材质公平切换，失效项也计数 | 冻结开始时该材质的有限桶长度，连续处理完再轮转；后来同材质任务不延长批次，第一次优先手持材料 |
| 有现成任务时仍先抓一页快照，消耗共享 8 ms | 先消费现有任务，空队列当 tick 同步补页；剩余额度及等待期间预取，工作总预算未扩大 |
| 单人创造的主背包来源仍走 SWAP 等待 | 复制完整栈到允许的快捷栏，按原版创造取物顺序发包/选槽，同 tick 继续；源格不改，NBT/名称/数量保留 |
| 找到缓存放置面前也构造角度候选集合 | 先用实时世界重新验证缓存面，成功直接返回；失败再构造完整回退候选 |
| 普通 BlockItem 的不变预测多次 canPlace/canPlaceAt | 实际 1.20.1 字节码证明 getPlacementState 已验证；仅普通精确类型且未改状态跳过重复检查，子类/状态变化保留验证 |
| 已完成的纯打印格仍构建其他模式标志和流体字符串 | 完整匹配直接进入统计快路径；只在相应模式启用时计算流体/基岩标志 |
| 接纳时丢弃快照尚无支撑的普通格 | 保留到执行时实时判断，避免同批前一格已放好后仍等下 tick 重新发现 |

仅同材料调度和校验次数有自动证据；没有以这些检查推导每秒方块数。范围、已加载状态、源目标、实际观察状态、受保护快捷栏、代际、确认和总资源预算继续检查。

## 诊断与验收

`latest.log` 在运行打印时每 5 秒聚合一条 `Printer profile`：扫描、预测、取料、交互及总工作耗时；候选/未知、尝试/发送/接受/失效/等待/重试、额度耗尽、队列长度和实际参数。没有每格写日志、没有新增界面文案。`sent/accepted` 是交互指标，不代表服务端确认完成数。

本次读到开发实例磁盘设置为 `interval=0, perTick=64, cooldown=0, workBudgetMillis=8`，与之前口述三项全 0 不同。本轮没有改动参数，也不能据磁盘值认定用户另一实例的内存配置。日志会记录实际生效值。

117 项核心测试 / 562934 次断言；95615 项真实 Minecraft 无窗口适配检查；完整构建通过。新增8304项有限批次检查、完整创造物品栈复制与回滚、真实注册表普通/自定义物品验证。字节码证据见 `validation/block-item-bytecode-0.3.38.txt`。

没有启动 Minecraft 或修改用户世界；静止/移动打印吞吐、复杂模型、超大投影 22 区块帧时间仍待实际游戏验收，不宣称已达到四改性能或全部功能等价。
