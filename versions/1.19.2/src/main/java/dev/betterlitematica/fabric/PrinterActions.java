package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.state.property.Properties;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.registry.Registry;
import dev.betterlitematica.core.PrinterQueue;
import java.util.*;

final class PrinterActions {
    enum Outcome { SENT,WAIT,MISSING,UNSUPPORTED,RETRY,STALE }
    final PrinterInteractionGuard interactions=new PrinterInteractionGuard(MinecraftClient.getInstance());
    private final MinecraftClient client;final PrinterDiagnostics diagnostics=new PrinterDiagnostics();
    private final InventoryTransfers transfers;private final PrinterSupply supply;private final NativeMiner miner;private PrinterSettings currentSettings;private BlockPos breaking;
    private PrinterReason reason=PrinterReason.NONE;private Item missing;
    private boolean acting,dispatched,breakDispatched;private int materialTick=Integer.MIN_VALUE;private final Set<Item> inventoryItems=new HashSet<>();
    private static final class IceTask {
        final PrinterQueue.Job job;dev.betterlitematica.core.IceWaterPlan plan;int nextBreak,instantSentAt=-1;
        IceTask(PrinterQueue.Job job){this.job=job;}
    }
    private final LinkedHashMap<Long,IceTask> iceTasks=new LinkedHashMap<>();
    private int iceTimeout;private float iceToolSpeed;
    private final LinkedHashMap<BlockPos,Integer> corals=new LinkedHashMap<>();
    private static final Direction[] SIDES=Direction.values();private static final double[] HEIGHTS={0.25,0.75};
    private final Map<BlockState,PlacementHint> placementHints=new LinkedHashMap<>();
    private final java.util.function.Supplier<AccuratePlacement.Mode> protocol;
    private final PrinterContainers containers;
    private final PrinterSigns signs;private final ProjectionController controller;
    PrinterActions(MinecraftClient client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed){this(client,transfers,protocol,allowed,null);}
    PrinterActions(MinecraftClient client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed,PrinterContainers containers){this(client,transfers,protocol,allowed,containers,null,null);}
    PrinterActions(MinecraftClient client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed,PrinterContainers containers,PrinterSigns signs,ProjectionController controller){this.client=client;this.protocol=protocol;this.transfers=transfers;this.supply=new PrinterSupply(client);this.miner=new NativeMiner(client,transfers,()->controller==null?new BedrockSettings():controller.options().bedrock);this.containers=containers;this.signs=signs;this.controller=controller;}
    void checkMiner(){miner.arm();}
    String cleanupError(){return miner.problem();}
    boolean supplyTick(int tick){if(NativeMiner.recoverSuspended(tick)){reason=PrinterReason.of(PrinterReason.Id.RECOVERING,"回收上次施工材料");return true;}boolean work=supply.tick(tick);if(work)reason=supply.typedReason();return work;}
    void supplyOpened(int sync,net.minecraft.screen.ScreenHandlerType<?> type){supply.opened(sync,type);}
    void supplyInventory(int sync){supply.inventory(sync);}
    void manualInventory(){if(supply.active())supply.reset(true);}
    boolean dispatched(){return dispatched;}boolean breakDispatched(){return breakDispatched;}
    void prepare(int tick){if(materialTick==tick)return;materialTick=tick;inventoryItems.clear();if(client.player!=null)for(int i=0;i<36;i++)inventoryItems.add(client.player.getInventory().getStack(i).getItem());}
    boolean acting(){return acting||NativeMiner.acting();}
    boolean breaking(){return breaking!=null||miner.active();}
    String reason(){return reason.description();} PrinterReason typedReason(){return reason;}
    Item missing(){return missing;}
    MaterialStock stock(){return supply.stock();}
    void reset(){interactions.cancel();miner.reset();supply.reset(true);transfers.reset();breaking=null;corals.clear();placementHints.clear();for(var task:iceTasks.values())if(task.plan!=null)task.plan.cancel();iceTasks.clear();iceToolSpeed=0;if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();}
    boolean ownsIce(PrinterQueue.Job job){var task=iceTasks.get(job.position());return task!=null&&task.job.generation()==job.generation();}
    void cancelIce(PrinterQueue.Job job){var task=iceTasks.remove(job.position());if(task!=null&&task.plan!=null)task.plan.cancel();if(breaking!=null&&breaking.asLong()==job.position()){breaking=null;client.interactionManager.cancelBlockBreaking();}}
    boolean owns(PrinterQueue.Job job){return miner.owns(job.generation(),job.position())||ownsIce(job);}
    boolean inProgress(PrinterQueue.Job job){return owns(job)||breaking!=null&&job.kind()==PrinterQueue.Kind.BREAK&&breaking.asLong()==job.position();}
    boolean needsBreak(PrinterQueue.Job job){var ice=iceTasks.get(job.position());return job.kind()==PrinterQueue.Kind.BREAK||job.kind()==PrinterQueue.Kind.BEDROCK||job.kind()==PrinterQueue.Kind.ADJUST&&Block.getStateFromRawId(job.observed()).isOf(Blocks.ICE)&&PrinterIcePolicy.waterSource(Block.getStateFromRawId(job.expected()))||ice!=null&&ice.plan!=null&&ice.plan.requiresBreaking();}
    boolean waitingCoral(BlockPos pos,BlockState current,BlockState wanted,int tick){Integer until=corals.get(pos);if(until==null)return false;if(tick>=until||!PrinterRules.pendingCoral(current,wanted)||!dry(pos,current)){corals.remove(pos);return false;}return true;}
    void confirmed(BlockPos pos,BlockState value){interactions.confirmed(pos,value);miner.confirmed(pos,value);var ice=iceTasks.get(pos.asLong());if(ice!=null&&ice.plan!=null)ice.plan.confirm(observation(value));}
    private static dev.betterlitematica.core.IceWaterPlan.Observation observation(BlockState value){return value.isOf(Blocks.ICE)?dev.betterlitematica.core.IceWaterPlan.Observation.ICE:value.isOf(Blocks.WATER)&&value.get(FluidBlock.LEVEL)==0?dev.betterlitematica.core.IceWaterPlan.Observation.WATER:value.isAir()?dev.betterlitematica.core.IceWaterPlan.Observation.AIR:dev.betterlitematica.core.IceWaterPlan.Observation.OTHER;}
    private Outcome fail(Outcome result,PrinterReason reason){this.reason=reason;return result;}
    private boolean available(Item item){return client.player.isCreative()||inventoryItems.contains(item);}
    static boolean iceTool(ItemStack stack){return stack.getItem() instanceof PickaxeItem&&net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.SILK_TOUCH,stack)==0;}
    private Outcome equip(Item item,int tick){long start=System.nanoTime();try{return equipInternal(item,tick);}finally{diagnostics.equipNanos+=System.nanoTime()-start;}}
    private Outcome equipInternal(Item item,int tick){
        missing=null;return switch(transfers.equipForPrinter(item,tick)){case READY->null;case WAIT->fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));case MISSING->{if(item!=Items.AIR&&currentSettings!=null&&supply.request(item,currentSettings.supply,tick))yield fail(Outcome.WAIT,supply.typedReason());missing=item;yield fail(item==Items.AIR?Outcome.UNSUPPORTED:Outcome.MISSING,item==Items.AIR?PrinterReason.of(PrinterReason.Id.INVENTORY_FULL,"调节方块需要一个空背包格"):!supply.typedReason().isEmpty()?supply.typedReason():PrinterReason.of(PrinterReason.Id.MISSING,"缺少"+item.getName().getString()));}};
    }
    Outcome execute(PrinterQueue.Job job,PrinterSettings settings,int tick){
        prepare(tick);dispatched=breakDispatched=false;if(interactions.waiting())return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待异常交互确认"));currentSettings=settings;reason=PrinterReason.of(PrinterReason.Id.NONE,"");missing=null;var pos=BlockPos.fromLong(job.position());var expected=Block.getStateFromRawId(job.expected());var actual=client.world.getBlockState(pos);
        if(PrinterRules.forbiddenPrint(expected))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"已跳过玩家头颅"));
        if(Block.getRawIdFromState(actual)!=job.observed()&&!owns(job))return Outcome.STALE;
        double reach=client.interactionManager.getReachDistance();if(PrinterReach.distanceSquared(client.player.getEyePos(),pos)>reach*reach)return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OUT_OF_REACH,"目标超出交互距离"));
        if(job.kind()==PrinterQueue.Kind.BEDROCK){dispatched=breakDispatched=!miner.owns(job.generation(),job.position());return miner.step(job.generation(),pos,tick)?Outcome.SENT:fail(Outcome.WAIT,miner.typedReason());}
        if(ownsIce(job))return iceWater(job,pos,actual,settings,tick);
        if(job.kind()==PrinterQueue.Kind.BREAK)return breakBlock(pos,actual,settings);
        if(job.kind()==PrinterQueue.Kind.ADJUST){var adjusted=adjust(pos,actual,expected,settings,tick);if(adjusted!=null)return adjusted;}
        if(expected.getBlock() instanceof FluidBlock){if(iceTarget(actual,expected,settings))return iceWater(job,pos,actual,settings,tick);return fluid(pos,expected.isOf(Blocks.LAVA)?Items.LAVA_BUCKET:Items.WATER_BUCKET,settings,tick);}
        BlockState place=placementState(actual,expected,settings,pos);Item item=place.getBlock().asItem();boolean coral=PrinterRules.coralSubstitute(expected)!=null&&place.getBlock()!=expected.getBlock();
        if(item==Items.AIR||!(item instanceof BlockItem))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此方块不能直接放置"));
        if(settings.fallingCheck&&place.getBlock() instanceof FallingBlock&&FallingBlock.canFallThrough(client.world.getBlockState(pos.down())))return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_SUPPORT,"等待下方支撑"));

        ItemStack requested=place.isOf(Blocks.LIGHT)
            ?LegacyGame.light(new ItemStack(item),place.get(LightBlock.LEVEL_15))
            :stack(item);
        ContainerPrintTarget container=null;
        SignPrintTarget sign=null;boolean dataSign=false,inlineSign=false;
        if(signs!=null&&SignPrintTarget.supported(place)){
            try{sign=job.kind()==PrinterQueue.Kind.FILL?SignPrintTarget.from(place,null):SignPrintTarget.read(controller,pos,place);}
            catch(IllegalArgumentException invalid){return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.SIGN_BLOCKED,"告示牌数据无法用于放置"));}
            catch(IllegalStateException pending){return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.SIGN_BLOCKED,"等待告示牌数据"));}
            dataSign=client.player.isCreativeLevelTwoOp();inlineSign=dataSign&&sign.nonDefault();

            if(dataSign)requested=sign.placementStack();
        }
        if(settings.containerFill&&containers!=null&&ContainerPrintTarget.supported(place)){
            try{container=containers.placement(pos);}catch(RuntimeException pending){return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.CONTAINER_BLOCKED,"等待容器数据"));}
            if(client.player.isCreative())requested=container.placementStack();
            else{int slot=InventoryTransfers.find(client.player.getInventory(),container::safeSurvivalItem);if(slot>=0)requested=client.player.getInventory().getStack(slot);else{missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少可用容器"));}}
        }
        var plan=placement(pos,place,settings,requested);if(plan==null)return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_FACE,"等待可用放置面"));
        if(dataSign){
            var selected=transfers.equipContainerForPrinter(requested,tick);
            if(selected==InventoryTransfers.Result.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));
            if(selected==InventoryTransfers.Result.MISSING){missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少可用告示牌"));}
        }else if(container!=null){
            var selected=client.player.isCreative()?transfers.equipContainerForPrinter(requested,tick):transfers.equipForPrinter(container::safeSurvivalItem,tick);
            if(selected==InventoryTransfers.Result.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));
            if(selected==InventoryTransfers.Result.MISSING){missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少可用容器"));}
        }else if(place.isOf(Blocks.LIGHT)){
            var selected=transfers.equipForPrinter(requested,tick);
            if(selected==InventoryTransfers.Result.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));
            if(selected==InventoryTransfers.Result.MISSING){missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少匹配光源方块"));}
        }else{var selected=equip(item,tick);if(selected!=null)return selected;}
        var intent=sign==null?null:signs.arm(pos,sign,inlineSign);
        if(sign!=null&&intent==null)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待告示牌确认"));
        Outcome outcome;
        try{outcome=use(plan.hit(),plan.yaw(),plan.pitch(),plan.sneak(),pos,place);}
        catch(RuntimeException failure){if(intent!=null&&!dispatched)signs.abandon(pos,intent);throw failure;}

        if(intent!=null&&!dispatched)signs.abandon(pos,intent);
        if(coral&&outcome==Outcome.SENT){if(corals.size()>=128)corals.remove(corals.keySet().iterator().next());corals.put(pos.toImmutable(),tick+200);}return outcome;
    }
    private ItemStack stack(Item item){var inv=client.player.getInventory();if(inv.getMainHandStack().isOf(item))return inv.getMainHandStack();for(int i=0;i<36;i++)if(inv.getStack(i).isOf(item))return inv.getStack(i);return new ItemStack(item);}
    int material(PrinterQueue.Job job,PrinterSettings settings){
        if(PrinterRules.forbiddenPrint(Block.getStateFromRawId(job.expected())))return -1;
        if(job.kind()==PrinterQueue.Kind.BREAK||job.kind()==PrinterQueue.Kind.BEDROCK)return -1;
        var expected=Block.getStateFromRawId(job.expected());var actual=Block.getStateFromRawId(job.observed());if(settings.autoBreakIce&&actual.isOf(Blocks.ICE)&&PrinterIcePolicy.waterSource(expected))return -1;Item item=null;
        if(job.kind()==PrinterQueue.Kind.ADJUST)item=adjustmentItem(actual,expected,settings);
        if(item==null&&expected.getBlock() instanceof FluidBlock)item=iceTarget(actual,expected,settings)?Items.ICE:expected.isOf(Blocks.LAVA)?Items.LAVA_BUCKET:Items.WATER_BUCKET;
        if(item==null){var place=placementState(actual,expected,settings,null);if(place.isOf(Blocks.LIGHT))return materialKey(place);item=place.getBlock().asItem();}
        return Registry.ITEM.getRawId(item);
    }
    private static int lightKey(int level){return -2-level;}
    static int materialKey(BlockState state){return state.isOf(Blocks.LIGHT)?lightKey(state.get(LightBlock.LEVEL_15)):Registry.ITEM.getRawId(state.getBlock().asItem());}
    boolean materialAvailable(int material){return material==-1||available(material<=-2?Items.LIGHT:Registry.ITEM.get(material));}
    static int heldMaterial(ItemStack held){return held.isOf(Items.LIGHT)?lightKey(StateResolver1201.itemState(Blocks.LIGHT.getDefaultState(),held).get(LightBlock.LEVEL_15)):Registry.ITEM.getRawId(held.getItem());}
    private BlockState placementState(BlockState actual,BlockState expected,PrinterSettings settings,BlockPos pos){
        BlockState place=expected;if(expected.getBlock() instanceof FlowerPotBlock&&!expected.isOf(Blocks.FLOWER_POT)&&actual.isAir())place=Blocks.FLOWER_POT.getDefaultState();
        if(expected.isOf(Blocks.FARMLAND)&&actual.isAir())place=Blocks.DIRT.getDefaultState();Item item=place.getBlock().asItem();
        if(settings.coralSubstitute&&!available(item)){var live=PrinterRules.coralSubstitute(place);if(live!=null&&available(live.getBlock().asItem())&&(pos==null||dry(pos,live)))place=live;}
        if(settings.stripLogs&&!available(place.getBlock().asItem())){var id=Registry.BLOCK.getId(place.getBlock());if(id.getPath().startsWith("stripped_")){var raw=Registry.BLOCK.get(new Identifier(id.getNamespace(),id.getPath().substring(9))).getDefaultState();if(raw.contains(Properties.AXIS)&&place.contains(Properties.AXIS))raw=raw.with(Properties.AXIS,place.get(Properties.AXIS));if(raw.getBlock().asItem()!=Items.AIR)place=raw;}}
        return place;
    }
    private Item tool(boolean axe){
        var held=client.player.getMainHandStack().getItem();if(axe?held instanceof AxeItem:held instanceof HoeItem)return held;
        for(Item item:axe?List.of(Items.NETHERITE_AXE,Items.DIAMOND_AXE,Items.IRON_AXE,Items.STONE_AXE,Items.WOODEN_AXE,Items.GOLDEN_AXE):List.of(Items.NETHERITE_HOE,Items.DIAMOND_HOE,Items.IRON_HOE,Items.STONE_HOE,Items.WOODEN_HOE,Items.GOLDEN_HOE))if(available(item))return item;
        return axe?Items.IRON_AXE:Items.IRON_HOE;
    }
    private Item adjustmentItem(BlockState actual,BlockState expected,PrinterSettings s){
        if(s.stripLogs&&PrinterRules.isUnstripped(actual,expected))return tool(true);
        if(expected.isOf(Blocks.FARMLAND)&&actual.isOf(Blocks.DIRT))return tool(false);
        if(actual.isOf(Blocks.FLOWER_POT)&&expected.getBlock() instanceof FlowerPotBlock pot)return pot.getContent().asItem();
        if(PrinterRules.sameBlock(actual,expected)&&expected.contains(Properties.WATERLOGGED)&&actual.get(Properties.WATERLOGGED)!=expected.get(Properties.WATERLOGGED))return expected.get(Properties.WATERLOGGED)?Items.WATER_BUCKET:Items.BUCKET;
        if(s.bonemeal&&expected.getBlock() instanceof CropBlock)return Items.BONE_MEAL;
        if(s.composter&&expected.isOf(Blocks.COMPOSTER))return compostMaterial(client.player.getMainHandStack().getItem(),s.compostItems,this::available);
        if(actual.isOf(Blocks.NOTE_BLOCK)||actual.isOf(Blocks.REPEATER)||actual.isOf(Blocks.COMPARATOR)||actual.isOf(Blocks.LEVER)||PrinterRules.manualOpen(actual))return Items.AIR;
        return null;
    }
    static Item compostMaterial(Item held,List<String> allowed,java.util.function.Predicate<Item> available){
        Item first=Items.AIR,found=Items.AIR;
        for(String name:allowed){var id=Identifier.tryParse(name);if(id==null)continue;Item item=Registry.ITEM.get(id);if(!ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE.containsKey(item))continue;
            if(first==Items.AIR)first=item;if(item==held)return item;if(found==Items.AIR&&available.test(item))found=item;
        }
        return found!=Items.AIR?found:first;
    }
    private boolean dry(BlockPos pos,BlockState state){if(state.contains(Properties.WATERLOGGED)&&state.get(Properties.WATERLOGGED))return false;for(Direction side:Direction.values()){BlockPos next=pos.offset(side);if(!WorldChunks.loaded(client.world,next)||!client.world.getFluidState(next).isEmpty())return false;}return true;}
    boolean iceTarget(BlockState actual,BlockState expected,PrinterSettings settings){return settings.autoBreakIce&&PrinterIcePolicy.waterSource(expected)&&(actual.isOf(Blocks.ICE)||settings.waterToIce&&(actual.isAir()||actual.isOf(Blocks.WATER)&&actual.get(FluidBlock.LEVEL)>0))&&!client.player.isCreative();}
    boolean hasIceWork(){return !iceTasks.isEmpty();}
    private static float iceSpeed(ItemStack stack){float speed=stack.getMiningSpeedMultiplier(Blocks.ICE.getDefaultState());int level=net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.EFFICIENCY,stack);return speed>1&&level>0?speed+level*level+1:speed;}
    PrinterQueue.Job nextIceJob(int tick){
        if(iceTasks.isEmpty())return null;
        for(var it=iceTasks.values().iterator();it.hasNext();){var task=it.next();if(task.plan!=null&&task.plan.next(tick,observation(client.world.getBlockState(BlockPos.fromLong(task.job.position()))),true)==dev.betterlitematica.core.IceWaterPlan.Step.DONE)it.remove();}
        for(var task:iceTasks.values())if(task.plan==null)return task.job;
        if(breaking!=null){var task=iceTasks.get(breaking.asLong());if(task!=null&&client.world.getBlockState(breaking).isOf(Blocks.ICE))return task.job;}
        for(var task:iceTasks.values())if((task.instantSentAt<0||tick-task.instantSentAt>=10)&&task.plan.next(tick,observation(client.world.getBlockState(BlockPos.fromLong(task.job.position()))),true)==dev.betterlitematica.core.IceWaterPlan.Step.BREAK)return task.job;
        return iceTasks.isEmpty()?null:iceTasks.values().iterator().next().job;
    }
    private void startIceBatch(PrinterQueue.Job first){
        iceToolSpeed=0;var inv=client.player.getInventory();for(int i=0;i<36;i++){var stack=inv.getStack(i);if(iceTool(stack)&&transfers.usableForPrinter(stack))iceToolSpeed=Math.max(iceToolSpeed,iceSpeed(stack));}
        var candidates=controller==null?List.of(first):controller.printer().iceCandidates(first,iceToolSpeed>0?32:8);
        for(var job:candidates)iceTasks.putIfAbsent(job.position(),new IceTask(job));iceTimeout=200+iceTasks.size()*40;
    }
    private Outcome iceWater(PrinterQueue.Job job,BlockPos pos,BlockState actual,PrinterSettings settings,int tick){
        if(!settings.autoBreakIce){cancelIce(job);return Outcome.STALE;}
        boolean eligible=!client.player.isCreative()&&!client.world.getDimension().ultrawarm()&&WorldChunks.loaded(client.world,pos.down())&&(client.world.getBlockState(pos.down()).getMaterial().blocksMovement()||!client.world.getFluidState(pos.down()).isEmpty());
        if(!eligible){cancelIce(job);return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此处无法破冰成水"));}
        if(!ownsIce(job))startIceBatch(job);var task=iceTasks.get(job.position());
        if(task.plan==null&&actual.isOf(Blocks.ICE)){

            task.plan=new dev.betterlitematica.core.IceWaterPlan(job.generation(),job.position(),tick,iceTimeout);
            task.plan.confirm(dev.betterlitematica.core.IceWaterPlan.Observation.ICE);
        }
        if(task.plan==null){
            if(!iceTarget(actual,Block.getStateFromRawId(job.expected()),settings)){cancelIce(job);return Outcome.STALE;}
            var plan=placement(pos,Blocks.ICE.getDefaultState(),settings,stack(Items.ICE));if(plan==null){cancelIce(job);return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_FACE,"等待可用放置面"));}
            var selected=equip(Items.ICE,tick);if(selected!=null){if(selected!=Outcome.WAIT)cancelIce(job);return selected;}
            var placed=use(plan.hit(),plan.yaw(),plan.pitch(),plan.sneak(),pos,Blocks.ICE.getDefaultState());if(placed!=Outcome.SENT){if(placed!=Outcome.WAIT)cancelIce(job);return placed;}
            task.plan=new dev.betterlitematica.core.IceWaterPlan(job.generation(),job.position(),tick,iceTimeout);return Outcome.SENT;
        }
        var step=task.plan.next(tick,observation(actual),eligible);if(step==dev.betterlitematica.core.IceWaterPlan.Step.ABORT){cancelIce(job);return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"破冰放水未完成"));}if(step==dev.betterlitematica.core.IceWaterPlan.Step.DONE){iceTasks.remove(job.position());return Outcome.SENT;}
        if(step==dev.betterlitematica.core.IceWaterPlan.Step.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.BLOCK_UPDATE,"等待方块更新"));
        if(task.instantSentAt>=0&&tick-task.instantSentAt<10)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待水源确认"));
        if(iceToolSpeed>0&&InventoryTransfers.find(client.player.getInventory(),stack->iceTool(stack)&&iceSpeed(stack)>=iceToolSpeed)<0){
            iceToolSpeed=0;var inv=client.player.getInventory();for(int i=0;i<36;i++){var stack=inv.getStack(i);if(iceTool(stack)&&transfers.usableForPrinter(stack))iceToolSpeed=Math.max(iceToolSpeed,iceSpeed(stack));}
        }
        if(iceToolSpeed>0||net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.SILK_TOUCH,client.player.getMainHandStack())>0){
            var selected=iceToolSpeed>0?transfers.equipForPrinter(stack->iceTool(stack)&&iceSpeed(stack)>=iceToolSpeed,tick):transfers.equipForPrinter(Items.AIR,tick);
            if(selected!=InventoryTransfers.Result.READY){if(selected!=InventoryTransfers.Result.WAIT)cancelIce(job);return fail(selected==InventoryTransfers.Result.WAIT?Outcome.WAIT:Outcome.UNSUPPORTED,selected==InventoryTransfers.Result.WAIT?PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"):PrinterReason.of(PrinterReason.Id.TOOL_REQUIRED,"需要无精准采集的工具或空背包格"));}
        }
        if(tick<task.nextBreak)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.ICE_BREAK,"等待破冰"));task.nextBreak=tick+Math.max(1,settings.breakInterval);

        if(task.instantSentAt<0&&actual.calcBlockBreakingDelta(client.player,client.world,pos)>=1){
            var delta=client.player.getEyePos().subtract(Vec3d.ofCenter(pos));var side=Direction.getFacing(delta.x,delta.y,delta.z);acting=true;
            try{client.interactionManager.sendSequencedPacket(client.world,sequence->new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,pos,side,sequence));dispatched=breakDispatched=true;task.plan.breakingSent();task.instantSentAt=tick;client.player.swingHand(Hand.MAIN_HAND);return Outcome.SENT;}
            finally{acting=false;}
        }
        Outcome result=breakBlock(pos,actual,settings,true);if(result==Outcome.UNSUPPORTED){cancelIce(job);return result;}task.plan.breakingSent();
        if(!client.world.getBlockState(pos).isOf(Blocks.ICE)){breaking=null;client.interactionManager.cancelBlockBreaking();return Outcome.SENT;}
        return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待水源确认"));
    }
    private Outcome adjust(BlockPos pos,BlockState actual,BlockState expected,PrinterSettings s,int tick){
        Item item=adjustmentItem(actual,expected,s);if(item==null)return null;
        if(s.composter&&expected.isOf(Blocks.COMPOSTER)&&item==Items.AIR)return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"没有可用的堆肥材料"));
        if(PrinterRules.sameBlock(actual,expected)&&expected.contains(Properties.WATERLOGGED)&&actual.get(Properties.WATERLOGGED)!=expected.get(Properties.WATERLOGGED))return fluid(pos,item,s,tick);
        var hit=new BlockHitResult(Vec3d.ofCenter(pos).add(0,0.49,0),Direction.UP,pos,false);if(client.player.getEyePos().squaredDistanceTo(hit.getPos())>Math.pow(client.interactionManager.getReachDistance(),2))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OUT_OF_REACH,"目标超出交互距离"));
        var selected=equip(item,tick);if(selected!=null)return selected;

        float yaw=expected.getBlock() instanceof FenceGateBlock?expected.get(Properties.HORIZONTAL_FACING).asRotation():client.player.getYaw();
        return use(hit,yaw,client.player.getPitch(),false);
    }
    private record PlacementPlan(BlockHitResult hit,float yaw,float pitch,boolean sneak){}
    private record PlacementHint(Direction side,double height,float yaw,float pitch,boolean followYaw,boolean followPitch){}
    private PlacementPlan placement(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack){
        long started=System.nanoTime();try{return findPlacement(pos,wanted,settings,stack);}finally{diagnostics.planNanos+=System.nanoTime()-started;}
    }
    private PlacementPlan findPlacement(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack){
        var player=client.player;float originalYaw=player.getYaw(),originalPitch=player.getPitch();boolean originalSneak=player.isSneaking();
        var eye=player.getEyePos();double reach=client.interactionManager.getReachDistance();var mode=AccuratePlacement.resolve(client,protocol.get());
        BlockState actual=client.world.getBlockState(pos);var clicked=new BlockPos[6];var sneaking=new boolean[6];
        try{
            var hint=placementHints.get(wanted);float hintYaw=0,hintPitch=0;
            if(hint!=null){
                hintYaw=hint.followYaw()?originalYaw:hint.yaw();hintPitch=hint.followPitch()?originalPitch:hint.pitch();
                var plan=placementCandidate(pos,wanted,settings,stack,actual,mode,eye,reach*reach,clicked,sneaking,hint.side(),hint.height(),hintYaw,hintPitch);
                if(plan!=null)return plan;
            }

            var angles=new LinkedHashSet<Float>();angles.add(originalYaw);
            if(wanted.contains(Properties.HORIZONTAL_FACING)){float angle=wanted.get(Properties.HORIZONTAL_FACING).asRotation();angles.add(angle);angles.add(angle+180);}
            if(wanted.contains(Properties.FACING)){float angle=wanted.get(Properties.FACING).getHorizontal()<0?originalYaw:wanted.get(Properties.FACING).asRotation();angles.add(angle);angles.add(angle+180);}
            if(wanted.contains(Properties.ROTATION)){float angle=wanted.get(Properties.ROTATION)*22.5f;angles.add(angle);angles.add(angle-180);}
            boolean vertical=wanted.contains(Properties.FACING);float[] pitches=vertical?new float[]{0,-90,90}:new float[]{originalPitch};
            for(float yaw:angles)for(float pitch:pitches)for(Direction side:SIDES)for(double height:HEIGHTS){
                if(hint!=null&&hint.side()==side&&hint.height()==height&&hintYaw==yaw&&hintPitch==pitch)continue;
                var plan=placementCandidate(pos,wanted,settings,stack,actual,mode,eye,reach*reach,clicked,sneaking,side,height,yaw,pitch);
                if(plan!=null){if(placementHints.size()>=256)placementHints.remove(placementHints.keySet().iterator().next());placementHints.put(wanted,new PlacementHint(side,height,yaw,pitch,yaw==originalYaw,!vertical));return plan;}
            }
        }finally{player.setYaw(originalYaw);player.setPitch(originalPitch);player.input.sneaking=originalSneak;}
        return null;
    }
    private PlacementPlan placementCandidate(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack,BlockState actual,AccuratePlacement.Mode mode,Vec3d eye,double reachSquared,BlockPos[] faces,boolean[] sneaking,Direction side,double height,float yaw,float pitch){
        int index=side.ordinal();BlockPos clicked=faces[index];
        if(clicked==null){BlockPos support=pos.offset(side.getOpposite());var supportState=client.world.getBlockState(support);
            clicked=actual.getMaterial().isReplaceable()&&settings.airPlace?pos:actual.getMaterial().isReplaceable()&&!supportState.isAir()&&!supportState.getMaterial().isReplaceable()?support:pos;
            faces[index]=clicked;sneaking[index]=(settings.forceSneak||!clicked.equals(pos))&&actual.getBlock()!=wanted.getBlock();}
        if(clicked.equals(pos)&&actual.getMaterial().isReplaceable()&&!settings.airPlace)return null;
        var direct=placementOnFace(pos,wanted,stack,mode,eye,reachSquared,clicked,sneaking[index],side,height,yaw,pitch);
        if(direct!=null||!settings.airPlace||!actual.getMaterial().isReplaceable()||!clicked.equals(pos))return direct;
        BlockPos support=pos.offset(side.getOpposite());var supportState=client.world.getBlockState(support);
        if(supportState.isAir()||supportState.getMaterial().isReplaceable())return null;
        return placementOnFace(pos,wanted,stack,mode,eye,reachSquared,support,actual.getBlock()!=wanted.getBlock(),side,height,yaw,pitch);
    }
    private PlacementPlan placementOnFace(BlockPos pos,BlockState wanted,ItemStack stack,AccuratePlacement.Mode mode,Vec3d eye,double reachSquared,BlockPos clicked,boolean sneak,Direction side,double height,float yaw,float pitch){
        boolean nearestFace=stack.getItem().getClass()==BlockItem.class&&(wanted.getProperties().isEmpty()||wanted.isOf(Blocks.LIGHT));
        Vec3d point=nearestFace&&height==HEIGHTS[0]?EasyPlacementRules.nearestHitPoint(clicked,side,eye):EasyPlacementRules.hitPoint(clicked,side,height);if(eye.squaredDistanceTo(point)>reachSquared)return null;
        var player=client.player;player.setYaw(yaw);player.setPitch(pitch);player.input.sneaking=sneak;
        var hit=new BlockHitResult(point,side,clicked,false);var context=new ItemPlacementContext(player,Hand.MAIN_HAND,stack,hit);
        if(!context.canPlace()||!context.getBlockPos().equals(pos))return null;
        var predicted=AccuratePlacement.predict(mode,context,wanted,hit);
        if(!PrinterRules.placementMatches(predicted,wanted)||(stack.getItem().getClass()!=BlockItem.class&&!predicted.canPlaceAt(client.world,pos)))return null;
        return new PlacementPlan(hit,yaw,pitch,sneak);
    }
    private Outcome use(BlockHitResult hit,float yaw,float pitch,boolean sneak){return use(hit,yaw,pitch,sneak,null,null);}
    private Outcome use(BlockHitResult hit,float yaw,float pitch,boolean sneak,BlockPos target,BlockState wanted){
        long started=System.nanoTime();try{return useInternal(hit,yaw,pitch,sneak,target,wanted);}finally{diagnostics.sendNanos+=System.nanoTime()-started;}
    }
    private Outcome useInternal(BlockHitResult hit,float yaw,float pitch,boolean sneak,BlockPos target,BlockState wanted){
        var player=client.player;float oldYaw=player.getYaw(),oldPitch=player.getPitch();boolean oldSneak=player.isSneaking();
        if(player.getEyePos().squaredDistanceTo(hit.getPos())>Math.pow(client.interactionManager.getReachDistance(),2))return Outcome.UNSUPPORTED;
        var mode=AccuratePlacement.resolve(client,protocol.get());
        if(target!=null&&!AccuratePlacement.canUse(client,mode,target,wanted,hit))return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待放置确认"));
        if(target!=null&&wanted!=null&&!interactions.allow(target,wanted))return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待异常交互确认"));
        boolean turn=yaw!=oldYaw||pitch!=oldPitch;acting=true;interactions.sending(true);
        try{
            player.setYaw(yaw);player.setPitch(pitch);player.input.sneaking=sneak;
            if(turn)client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw,pitch,player.isOnGround()));
            if(sneak!=oldSneak)client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(player,sneak?ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY:ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            long beforePacket=PrinterInteractionGuard.blockPackets();var result=target==null?client.interactionManager.interactBlock(player,Hand.MAIN_HAND,hit):AccuratePlacement.use(client,mode,target,wanted,hit);dispatched=PrinterInteractionGuard.blockPackets()!=beforePacket;if(result.shouldSwingHand())player.swingHand(Hand.MAIN_HAND);
            return result.isAccepted()?Outcome.SENT:fail(client.getServer()!=null?Outcome.RETRY:Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.REJECTED,"放置未接受"));
        }finally{
            player.setYaw(oldYaw);player.setPitch(oldPitch);player.input.sneaking=oldSneak;
            if(sneak!=oldSneak)client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(player,oldSneak?ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY:ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            if(turn)client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(oldYaw,oldPitch,player.isOnGround()));acting=false;interactions.sending(false);
        }
    }
    private Outcome fluid(BlockPos pos,Item bucket,PrinterSettings s,int tick){
        var player=client.player;float yaw=player.getYaw(),pitch=player.getPitch();acting=true;
        try{
            for(Direction side:Direction.values()){
                Vec3d point=bucket==Items.BUCKET?Vec3d.ofCenter(pos):Vec3d.ofCenter(pos).add(side.getOffsetX()*0.49,side.getOffsetY()*0.49,side.getOffsetZ()*0.49);
                Vec3d delta=point.subtract(player.getEyePos());float lookYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),lookPitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z)));
                player.setYaw(lookYaw);player.setPitch(lookPitch);
                var hit=client.world.raycast(new net.minecraft.world.RaycastContext(player.getEyePos(),player.getEyePos().add(player.getRotationVec(1).multiply(client.interactionManager.getReachDistance())),net.minecraft.world.RaycastContext.ShapeType.OUTLINE,bucket==Items.BUCKET?net.minecraft.world.RaycastContext.FluidHandling.SOURCE_ONLY:net.minecraft.world.RaycastContext.FluidHandling.NONE,player));
                if(hit.getType()!=HitResult.Type.BLOCK)continue;BlockPos target=hit.getBlockPos();
                if(bucket!=Items.BUCKET&&!(client.world.getBlockState(target).getBlock() instanceof FluidFillable))target=target.offset(hit.getSide());
                if(!target.equals(pos))continue;
                var selected=equip(bucket,tick);if(selected!=null)return selected;
                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(lookYaw,lookPitch,player.isOnGround()));
                dispatched=true;var result=client.interactionManager.interactItem(player,Hand.MAIN_HAND);return result.isAccepted()?Outcome.SENT:Outcome.UNSUPPORTED;
            }
            return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_FACE,"等待可用放水面"));
        }finally{player.setYaw(yaw);player.setPitch(pitch);client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw,pitch,player.isOnGround()));acting=false;}
    }
    private Outcome breakBlock(BlockPos pos,BlockState actual,PrinterSettings settings){
        return breakBlock(pos,actual,settings,false);
    }
    private Outcome breakBlock(BlockPos pos,BlockState actual,PrinterSettings settings,boolean ownedIce){
        if(actual.isAir()){breaking=null;return Outcome.STALE;}
        if(actual.getHardness(client.world,pos)<0)return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此方块不可直接破坏"));
        Direction side;
        if(ownedIce&&actual.isOf(Blocks.ICE)){
            var eye=client.player.getEyePos();var delta=eye.subtract(Vec3d.ofCenter(pos));side=Direction.getFacing(delta.x,delta.y,delta.z);
        }else{
            var ray=client.world.raycast(new net.minecraft.world.RaycastContext(client.player.getEyePos(),Vec3d.ofCenter(pos),net.minecraft.world.RaycastContext.ShapeType.OUTLINE,net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
            if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(pos))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OBSTRUCTED,"目标被遮挡"));
            side=ray.getSide();
        }
        acting=true;try{
            dispatched=breakDispatched=true;
            if(!pos.equals(breaking)){client.interactionManager.cancelBlockBreaking();client.interactionManager.attackBlock(pos,side);breaking=pos.toImmutable();}
            else client.interactionManager.updateBlockBreakingProgress(pos,side);
            client.player.swingHand(Hand.MAIN_HAND);if(client.world.getBlockState(pos).isAir()){breaking=null;return Outcome.SENT;}
            return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.BREAKING,"正在破坏"));
        }finally{acting=false;}
    }
}
