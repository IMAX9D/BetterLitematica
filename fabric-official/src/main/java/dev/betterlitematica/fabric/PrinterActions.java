package dev.betterlitematica.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import dev.betterlitematica.core.PrinterQueue;
import java.util.*;
/** Bounded vanilla interactions. No direct client world mutations and no invented server acknowledgements. */
final class PrinterActions {
    enum Outcome { SENT,WAIT,MISSING,UNSUPPORTED,RETRY,STALE }
    private final Minecraft client;final PrinterDiagnostics diagnostics=new PrinterDiagnostics();
    private final InventoryTransfers transfers;private final PrinterSupply supply;private final NativeMiner miner;private PrinterSettings currentSettings;private BlockPos breaking;
    private PrinterReason reason=PrinterReason.NONE;private Item missing;
    private boolean acting,dispatched,breakDispatched;private int materialTick=Integer.MIN_VALUE;private final Set<Item> inventoryItems=new HashSet<>();
    private dev.betterlitematica.core.IceWaterPlan ice;private BlockPos icePosition;
    private int iceNextBreak;
    private final LinkedHashMap<BlockPos,Integer> corals=new LinkedHashMap<>();
    private static final Direction[] SIDES=Direction.values();private static final double[] HEIGHTS={0.25,0.75};
    private final Map<BlockState,PlacementHint> placementHints=new LinkedHashMap<>();
    private final java.util.function.Supplier<AccuratePlacement.Mode> protocol;
    private final PrinterContainers containers;
    private final PrinterSigns signs;private final ProjectionController controller;
    PrinterActions(Minecraft client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed){this(client,transfers,protocol,allowed,null);}
    PrinterActions(Minecraft client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed,PrinterContainers containers){this(client,transfers,protocol,allowed,containers,null,null);}
    PrinterActions(Minecraft client,InventoryTransfers transfers,java.util.function.Supplier<AccuratePlacement.Mode> protocol,java.util.function.BooleanSupplier allowed,PrinterContainers containers,PrinterSigns signs,ProjectionController controller){this.client=client;this.protocol=protocol;this.transfers=transfers;this.supply=new PrinterSupply(client);this.miner=new NativeMiner(client,transfers,()->controller==null?new BedrockSettings():controller.options().bedrock);this.containers=containers;this.signs=signs;this.controller=controller;}
    void checkMiner(){miner.arm();}
    String cleanupError(){return miner.problem();}
    boolean supplyTick(int tick){if(NativeMiner.recoverSuspended(tick)){reason=PrinterReason.of(PrinterReason.Id.RECOVERING,"回收上次施工材料");return true;}boolean work=supply.tick(tick);if(work)reason=supply.typedReason();return work;}
    void supplyOpened(int sync,net.minecraft.world.inventory.MenuType<?> type){supply.opened(sync,type);}
    void supplyInventory(int sync){supply.inventory(sync);}
    void manualInventory(){if(supply.active()){supply.reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已由玩家接管"));}}
    boolean dispatched(){return dispatched;}boolean breakDispatched(){return breakDispatched;}
    void prepare(int tick){if(materialTick==tick)return;materialTick=tick;inventoryItems.clear();if(client.player!=null)for(int i=0;i<36;i++)inventoryItems.add(client.player.getInventory().getItem(i).getItem());}
    boolean acting(){return acting||NativeMiner.acting();}
    boolean breaking(){return breaking!=null||miner.active();}
    String reason(){return reason.description();} PrinterReason typedReason(){return reason;}
    Item missing(){return missing;}
    MaterialStock stock(){return supply.stock();}
    void reset(){miner.reset();supply.reset(true);transfers.reset();breaking=null;iceNextBreak=0;corals.clear();placementHints.clear();if(ice!=null)ice.cancel();ice=null;icePosition=null;if(client.gameMode!=null)client.gameMode.stopDestroyBlock();}
    boolean owns(PrinterQueue.Job job){return miner.owns(job.generation(),job.position())||ice!=null&&ice.owns(job.generation(),job.position());}
    boolean inProgress(PrinterQueue.Job job){return owns(job)||breaking!=null&&job.kind()==PrinterQueue.Kind.BREAK&&breaking.asLong()==job.position();}
    boolean needsBreak(PrinterQueue.Job job){return job.kind()==PrinterQueue.Kind.BREAK||job.kind()==PrinterQueue.Kind.BEDROCK||ice!=null&&ice.owns(job.generation(),job.position())&&ice.requiresBreaking();}
    boolean waitingCoral(BlockPos pos,BlockState current,BlockState wanted,int tick){Integer until=corals.get(pos);if(until==null)return false;if(tick>=until||!PrinterRules.pendingCoral(current,wanted)||!dry(pos,current)){corals.remove(pos);return false;}return true;}
    void confirmed(BlockPos pos,BlockState value){miner.confirmed(pos,value);if(ice!=null&&pos.equals(icePosition))ice.confirm(observation(value));}
    private static dev.betterlitematica.core.IceWaterPlan.Observation observation(BlockState value){return value.is(Blocks.ICE)?dev.betterlitematica.core.IceWaterPlan.Observation.ICE:value.is(Blocks.WATER)&&value.getValue(LiquidBlock.LEVEL)==0?dev.betterlitematica.core.IceWaterPlan.Observation.WATER:value.isAir()?dev.betterlitematica.core.IceWaterPlan.Observation.AIR:dev.betterlitematica.core.IceWaterPlan.Observation.OTHER;}
    private Outcome fail(Outcome result,PrinterReason reason){this.reason=reason;return result;}
    private boolean available(Item item){return client.player.isCreative()||inventoryItems.contains(item);}
    static boolean iceTool(ItemStack stack){return stack.is(ItemTags.PICKAXES)&&net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(ItemDataBridge.registries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH),stack)==0;}
    private Outcome equip(Item item,int tick){long start=System.nanoTime();try{return equipInternal(item,tick);}finally{diagnostics.equipNanos+=System.nanoTime()-start;}}
    private Outcome equipInternal(Item item,int tick){
        missing=null;return switch(transfers.equipForPrinter(item,tick)){case READY->null;case WAIT->fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));case MISSING->{if(item!=Items.AIR&&currentSettings!=null&&supply.request(item,currentSettings.supply,tick))yield fail(Outcome.WAIT,supply.typedReason());missing=item;yield fail(item==Items.AIR?Outcome.UNSUPPORTED:Outcome.MISSING,item==Items.AIR?PrinterReason.of(PrinterReason.Id.INVENTORY_FULL,"调节方块需要一个空背包格"):!supply.typedReason().isEmpty()?supply.typedReason():PrinterReason.of(PrinterReason.Id.MISSING,"缺少"+item.getName(new ItemStack(item)).getString()));}};
    }
    Outcome execute(PrinterQueue.Job job,PrinterSettings settings,int tick){
        prepare(tick);dispatched=breakDispatched=false;currentSettings=settings;reason=PrinterReason.of(PrinterReason.Id.NONE,"");missing=null;var pos=BlockPos.of(job.position());var expected=Block.stateById(job.expected());var actual=client.level.getBlockState(pos);
        if(Block.getId(actual)!=job.observed()&&!owns(job))return Outcome.STALE;
        double reach=client.player.blockInteractionRange();if(PrinterReach.distanceSquared(client.player.getEyePosition(),pos)>reach*reach)return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OUT_OF_REACH,"目标超出交互距离"));
        if(job.kind()==PrinterQueue.Kind.BEDROCK){dispatched=breakDispatched=!miner.owns(job.generation(),job.position());return miner.step(job.generation(),pos,tick)?Outcome.SENT:fail(Outcome.WAIT,miner.typedReason());}
        if(ice!=null&&ice.owns(job.generation(),job.position()))return iceWater(job,pos,actual,settings,tick);
        if(job.kind()==PrinterQueue.Kind.BREAK)return destroyBlock(pos,actual,settings);
        if(job.kind()==PrinterQueue.Kind.ADJUST){var adjusted=adjust(pos,actual,expected,settings,tick);if(adjusted!=null)return adjusted;}
        if(expected.getBlock() instanceof LiquidBlock){if(settings.iceWater&&expected.is(Blocks.WATER)&&expected.getValue(LiquidBlock.LEVEL)==0&&actual.isAir()&&!client.player.isCreative()&&available(Items.ICE))return iceWater(job,pos,actual,settings,tick);return fluid(pos,expected.is(Blocks.LAVA)?Items.LAVA_BUCKET:Items.WATER_BUCKET,settings,tick);}
        BlockState place=placementState(actual,expected,settings,pos);Item item=place.getBlock().asItem();boolean coral=PrinterRules.coralSubstitute(expected)!=null&&place.getBlock()!=expected.getBlock();
        if(item==Items.AIR||!(item instanceof BlockItem))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此方块不能直接放置"));
        if(settings.fallingCheck&&place.getBlock() instanceof FallingBlock&&FallingBlock.isFree(client.level.getBlockState(pos.below())))return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_SUPPORT,"等待下方支撑"));
        if(!available(item)){var selection=equip(item,tick);if(selection!=null)return selection;}
        // Find an actionable face before selecting an item, so blocked work does not churn the hand.
        ItemStack requested=place.is(Blocks.LIGHT)
            ?LightBlock.setLightOnStack(new ItemStack(item),place.getValue(LightBlock.LEVEL))
            :stack(item);
        ContainerPrintTarget container=null;
        SignPrintTarget sign=null;boolean dataSign=false,inlineSign=false;
        if(signs!=null&&SignPrintTarget.supported(place)){
            try{sign=job.kind()==PrinterQueue.Kind.FILL?SignPrintTarget.from(place,null):SignPrintTarget.read(controller,pos,place);}
            catch(IllegalArgumentException invalid){return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.SIGN_BLOCKED,"告示牌数据无法用于放置"));}
            catch(IllegalStateException pending){return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.SIGN_BLOCKED,"等待告示牌数据"));}
            dataSign=client.player.canUseGameMasterBlocks();inlineSign=dataSign&&sign.nonDefault();
            // Even an empty sign must replace a previously held sign's nonempty NBT.
            if(dataSign)requested=sign.placementStack();
        }
        if(settings.containerFill&&containers!=null&&ContainerPrintTarget.supported(place)){
            try{container=containers.placement(pos);}catch(RuntimeException pending){return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.CONTAINER_BLOCKED,"等待容器数据"));}
            if(client.player.isCreative())requested=container.placementStack();
            else{int slot=InventoryTransfers.find(client.player.getInventory(),container::safeSurvivalItem);if(slot>=0)requested=client.player.getInventory().getItem(slot);else{missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少可用容器"));}}
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
        }else if(place.is(Blocks.LIGHT)){
            var selected=transfers.equipForPrinter(requested,tick);
            if(selected==InventoryTransfers.Result.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"));
            if(selected==InventoryTransfers.Result.MISSING){missing=item;return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"缺少匹配光源方块"));}
        }else{var selected=equip(item,tick);if(selected!=null)return selected;}
        var intent=sign==null?null:signs.arm(pos,sign,inlineSign);
        if(sign!=null&&intent==null)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待告示牌确认"));
        Outcome outcome;
        try{outcome=use(plan.hit(),plan.yaw(),plan.pitch(),plan.sneak(),pos,place);}
        catch(RuntimeException failure){if(intent!=null&&!dispatched)signs.abandon(pos,intent);throw failure;}
        // A local prediction can return PASS even though its sequenced packet was sent.
        // Keep that transaction until the server opens its editor or the ticket expires.
        if(intent!=null&&!dispatched)signs.abandon(pos,intent);
        if(coral&&outcome==Outcome.SENT){if(corals.size()>=128)corals.remove(corals.keySet().iterator().next());corals.put(pos.immutable(),tick+200);}return outcome;
    }
    private ItemStack stack(Item item){var inv=client.player.getInventory();if(inv.getSelectedItem().is(item))return inv.getSelectedItem();for(int i=0;i<36;i++)if(inv.getItem(i).is(item))return inv.getItem(i);return new ItemStack(item);}
    int material(PrinterQueue.Job job,PrinterSettings settings){
        if(job.kind()==PrinterQueue.Kind.BREAK||job.kind()==PrinterQueue.Kind.BEDROCK)return -1;
        var expected=Block.stateById(job.expected());var actual=Block.stateById(job.observed());Item item=null;
        if(job.kind()==PrinterQueue.Kind.ADJUST)item=adjustmentItem(actual,expected,settings);
        if(item==null&&expected.getBlock() instanceof LiquidBlock)item=settings.iceWater&&expected.is(Blocks.WATER)&&expected.getValue(LiquidBlock.LEVEL)==0&&actual.isAir()&&!client.player.isCreative()&&available(Items.ICE)?Items.ICE:expected.is(Blocks.LAVA)?Items.LAVA_BUCKET:Items.WATER_BUCKET;
        if(item==null){var place=placementState(actual,expected,settings,null);if(place.is(Blocks.LIGHT))return materialKey(place);item=place.getBlock().asItem();}
        return BuiltInRegistries.ITEM.getId(item);
    }
    private static int lightKey(int level){return -2-level;}
    static int materialKey(BlockState state){return state.is(Blocks.LIGHT)?lightKey(state.getValue(LightBlock.LEVEL)):BuiltInRegistries.ITEM.getId(state.getBlock().asItem());}
    static int heldMaterial(ItemStack held){return held.is(Items.LIGHT)?lightKey(StateResolver1201.itemState(Blocks.LIGHT.defaultBlockState(),held).getValue(LightBlock.LEVEL)):BuiltInRegistries.ITEM.getId(held.getItem());}
    private BlockState placementState(BlockState actual,BlockState expected,PrinterSettings settings,BlockPos pos){
        BlockState place=expected;if(expected.getBlock() instanceof FlowerPotBlock&&!expected.is(Blocks.FLOWER_POT)&&actual.isAir())place=Blocks.FLOWER_POT.defaultBlockState();
        if(expected.is(Blocks.FARMLAND)&&actual.isAir())place=Blocks.DIRT.defaultBlockState();Item item=place.getBlock().asItem();
        if(settings.coralSubstitute&&!available(item)){var live=PrinterRules.coralSubstitute(place);if(live!=null&&available(live.getBlock().asItem())&&(pos==null||dry(pos,live)))place=live;}
        if(settings.stripLogs&&!available(place.getBlock().asItem())){var id=BuiltInRegistries.BLOCK.getKey(place.getBlock());if(id.getPath().startsWith("stripped_")){var raw=BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(),id.getPath().substring(9))).defaultBlockState();if(raw.hasProperty(BlockStateProperties.AXIS)&&place.hasProperty(BlockStateProperties.AXIS))raw=raw.setValue(BlockStateProperties.AXIS,place.getValue(BlockStateProperties.AXIS));if(raw.getBlock().asItem()!=Items.AIR)place=raw;}}
        return place;
    }
    private Item tool(boolean axe){
        var held=client.player.getMainHandItem().getItem();if(client.player.getMainHandItem().is(axe?ItemTags.AXES:ItemTags.HOES))return held;
        for(Item item:axe?List.of(Items.NETHERITE_AXE,Items.DIAMOND_AXE,Items.IRON_AXE,Items.STONE_AXE,Items.WOODEN_AXE,Items.GOLDEN_AXE):List.of(Items.NETHERITE_HOE,Items.DIAMOND_HOE,Items.IRON_HOE,Items.STONE_HOE,Items.WOODEN_HOE,Items.GOLDEN_HOE))if(available(item))return item;
        return axe?Items.IRON_AXE:Items.IRON_HOE;
    }
    private Item adjustmentItem(BlockState actual,BlockState expected,PrinterSettings s){
        if(s.stripLogs&&PrinterRules.isUnstripped(actual,expected))return tool(true);
        if(expected.is(Blocks.FARMLAND)&&actual.is(Blocks.DIRT))return tool(false);
        if(actual.is(Blocks.FLOWER_POT)&&expected.getBlock() instanceof FlowerPotBlock pot)return pot.getPotted().asItem();
        if(actual.getBlock()==expected.getBlock()&&expected.hasProperty(BlockStateProperties.WATERLOGGED)&&actual.getValue(BlockStateProperties.WATERLOGGED)!=expected.getValue(BlockStateProperties.WATERLOGGED))return expected.getValue(BlockStateProperties.WATERLOGGED)?Items.WATER_BUCKET:Items.BUCKET;
        if(s.bonemeal&&expected.getBlock() instanceof CropBlock)return Items.BONE_MEAL;
        if(s.composter&&expected.is(Blocks.COMPOSTER))return compostMaterial(client.player.getMainHandItem().getItem(),s.compostItems,this::available);
        if(actual.is(Blocks.NOTE_BLOCK)||actual.is(Blocks.REPEATER)||actual.is(Blocks.COMPARATOR)||actual.is(Blocks.LEVER)||PrinterRules.manualOpen(actual))return Items.AIR;
        return null;
    }
    static Item compostMaterial(Item held,List<String> allowed,java.util.function.Predicate<Item> available){
        Item first=Items.AIR,found=Items.AIR;
        for(String name:allowed){var id=Identifier.tryParse(name);if(id==null)continue;Item item=BuiltInRegistries.ITEM.getValue(id);if(!VersionGameplay.compostable(item))continue;
            if(first==Items.AIR)first=item;if(item==held)return item;if(found==Items.AIR&&available.test(item))found=item;
        }
        return found!=Items.AIR?found:first;
    }
    private boolean dry(BlockPos pos,BlockState state){if(state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED))return false;for(Direction side:Direction.values()){BlockPos next=pos.relative(side);if(!WorldChunks.loaded(client.level,next)||!client.level.getFluidState(next).isEmpty())return false;}return true;}
    private Outcome iceWater(PrinterQueue.Job job,BlockPos pos,BlockState actual,PrinterSettings settings,int tick){
        boolean eligible=!client.player.isCreative()&&!client.level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES,pos)&&WorldChunks.loaded(client.level,pos.below())&&(VersionGameplay.blocksMotion(client.level.getBlockState(pos.below()))||!client.level.getFluidState(pos.below()).isEmpty());
        if(!eligible){reset();return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此处无法破冰成水"));}
        if(ice==null){var plan=placement(pos,Blocks.ICE.defaultBlockState(),settings,stack(Items.ICE));if(plan==null)return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_FACE,"等待可用放置面"));var selected=equip(Items.ICE,tick);if(selected!=null)return selected;var placed=use(plan.hit(),plan.yaw(),plan.pitch(),plan.sneak(),pos,Blocks.ICE.defaultBlockState());if(placed!=Outcome.SENT)return placed;ice=new dev.betterlitematica.core.IceWaterPlan(job.generation(),job.position(),tick);icePosition=pos.immutable();return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待放冰确认"));}
        var step=ice.next(tick,observation(actual),eligible);if(step==dev.betterlitematica.core.IceWaterPlan.Step.ABORT){reset();return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"破冰放水未完成"));}if(step==dev.betterlitematica.core.IceWaterPlan.Step.DONE){reset();return Outcome.SENT;}
        if(step==dev.betterlitematica.core.IceWaterPlan.Step.WAIT)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.BLOCK_UPDATE,"等待方块更新"));
        if(net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(ItemDataBridge.registries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH),client.player.getMainHandItem())>0){
            boolean hasPick=InventoryTransfers.find(client.player.getInventory(),PrinterActions::iceTool)>=0;
            var selected=hasPick?transfers.equipForPrinter(PrinterActions::iceTool,tick):transfers.equipForPrinter(Items.AIR,tick);
            if(selected!=InventoryTransfers.Result.READY)return fail(selected==InventoryTransfers.Result.WAIT?Outcome.WAIT:Outcome.UNSUPPORTED,selected==InventoryTransfers.Result.WAIT?PrinterReason.of(PrinterReason.Id.EQUIPPING,"等待换手"):PrinterReason.of(PrinterReason.Id.TOOL_REQUIRED,"需要无精准采集的工具或空背包格"));
        }
        if(tick<iceNextBreak)return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.ICE_BREAK,"等待破冰"));iceNextBreak=tick+Math.max(1,settings.breakInterval);
        Outcome result=destroyBlock(pos,actual,settings);if(result==Outcome.UNSUPPORTED){reset();return result;}ice.breakingSent();if(!client.level.getBlockState(pos).is(Blocks.ICE)){breaking=null;client.gameMode.stopDestroyBlock();}return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待水源确认"));
    }
    private Outcome adjust(BlockPos pos,BlockState actual,BlockState expected,PrinterSettings s,int tick){
        Item item=adjustmentItem(actual,expected,s);if(item==null)return null;
        if(s.composter&&expected.is(Blocks.COMPOSTER)&&item==Items.AIR)return fail(Outcome.MISSING,PrinterReason.of(PrinterReason.Id.MISSING,"没有可用的堆肥材料"));
        if(actual.getBlock()==expected.getBlock()&&expected.hasProperty(BlockStateProperties.WATERLOGGED)&&actual.getValue(BlockStateProperties.WATERLOGGED)!=expected.getValue(BlockStateProperties.WATERLOGGED))return fluid(pos,item,s,tick);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos).add(0,0.49,0),Direction.UP,pos,false);if(client.player.getEyePosition().distanceToSqr(hit.getLocation())>Math.pow(client.player.blockInteractionRange(),2))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OUT_OF_REACH,"目标超出交互距离"));
        var selected=equip(item,tick);if(selected!=null)return selected;return use(hit,client.player.getYRot(),client.player.getXRot(),false);
    }
    private record PlacementPlan(BlockHitResult hit,float yaw,float pitch,boolean sneak){}
    private record PlacementHint(Direction side,double height,float yaw,float pitch,boolean followYaw,boolean followPitch){}
    private PlacementPlan placement(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack){
        long started=System.nanoTime();try{return findPlacement(pos,wanted,settings,stack);}finally{diagnostics.planNanos+=System.nanoTime()-started;}
    }
    private PlacementPlan findPlacement(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack){
        var player=client.player;float originalYaw=player.getYRot(),originalPitch=player.getXRot();boolean originalSneak=player.isShiftKeyDown();
        var eye=player.getEyePosition();double reach=client.player.blockInteractionRange();var mode=AccuratePlacement.resolve(client,protocol.get());
        BlockState actual=client.level.getBlockState(pos);var clicked=new BlockPos[6];var sneaking=new boolean[6];
        try{
            var hint=placementHints.get(wanted);float hintYaw=0,hintPitch=0;
            if(hint!=null){
                hintYaw=hint.followYaw()?originalYaw:hint.yaw();hintPitch=hint.followPitch()?originalPitch:hint.pitch();
                var plan=placementCandidate(pos,wanted,settings,stack,actual,mode,eye,reach*reach,clicked,sneaking,hint.side(),hint.height(),hintYaw,hintPitch);
                if(plan!=null)return plan;
            }
            // Allocate fallback orientation candidates only when the cached live-validated face fails.
            var angles=new LinkedHashSet<Float>();angles.add(originalYaw);
            if(wanted.hasProperty(BlockStateProperties.HORIZONTAL_FACING)){float angle=wanted.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();angles.add(angle);angles.add(angle+180);}
            if(wanted.hasProperty(BlockStateProperties.FACING)){float angle=wanted.getValue(BlockStateProperties.FACING).get2DDataValue()<0?originalYaw:wanted.getValue(BlockStateProperties.FACING).toYRot();angles.add(angle);angles.add(angle+180);}
            if(wanted.hasProperty(BlockStateProperties.ROTATION_16)){float angle=wanted.getValue(BlockStateProperties.ROTATION_16)*22.5f;angles.add(angle);angles.add(angle-180);}
            boolean vertical=wanted.hasProperty(BlockStateProperties.FACING);float[] pitches=vertical?new float[]{0,-90,90}:new float[]{originalPitch};
            for(float yaw:angles)for(float pitch:pitches)for(Direction side:SIDES)for(double height:HEIGHTS){
                if(hint!=null&&hint.side()==side&&hint.height()==height&&hintYaw==yaw&&hintPitch==pitch)continue;
                var plan=placementCandidate(pos,wanted,settings,stack,actual,mode,eye,reach*reach,clicked,sneaking,side,height,yaw,pitch);
                if(plan!=null){if(placementHints.size()>=256)placementHints.remove(placementHints.keySet().iterator().next());placementHints.put(wanted,new PlacementHint(side,height,yaw,pitch,yaw==originalYaw,!vertical));return plan;}
            }
        }finally{player.setYRot(originalYaw);player.setXRot(originalPitch);PlayerInputBridge.shift(player,originalSneak);}
        return null;
    }
    private PlacementPlan placementCandidate(BlockPos pos,BlockState wanted,PrinterSettings settings,ItemStack stack,BlockState actual,AccuratePlacement.Mode mode,Vec3 eye,double reachSquared,BlockPos[] faces,boolean[] sneaking,Direction side,double height,float yaw,float pitch){
        int index=side.ordinal();BlockPos clicked=faces[index];
        if(clicked==null){BlockPos support=pos.relative(side.getOpposite());var supportState=client.level.getBlockState(support);
            clicked=actual.canBeReplaced()&&settings.airPlace?pos:actual.canBeReplaced()&&!supportState.isAir()&&!supportState.canBeReplaced()?support:pos;
            faces[index]=clicked;sneaking[index]=(settings.forceSneak||!clicked.equals(pos))&&actual.getBlock()!=wanted.getBlock();}
        if(clicked.equals(pos)&&actual.canBeReplaced()&&!settings.airPlace)return null;
        var direct=placementOnFace(pos,wanted,stack,mode,eye,reachSquared,clicked,sneaking[index],side,height,yaw,pitch);
        if(direct!=null||!settings.airPlace||!actual.canBeReplaced()||!clicked.equals(pos))return direct;
        BlockPos support=pos.relative(side.getOpposite());var supportState=client.level.getBlockState(support);
        if(supportState.isAir()||supportState.canBeReplaced())return null;
        return placementOnFace(pos,wanted,stack,mode,eye,reachSquared,support,actual.getBlock()!=wanted.getBlock(),side,height,yaw,pitch);
    }
    private PlacementPlan placementOnFace(BlockPos pos,BlockState wanted,ItemStack stack,AccuratePlacement.Mode mode,Vec3 eye,double reachSquared,BlockPos clicked,boolean sneak,Direction side,double height,float yaw,float pitch){
        boolean nearestFace=stack.getItem().getClass()==BlockItem.class&&(wanted.getProperties().isEmpty()||wanted.is(Blocks.LIGHT));
        Vec3 point=nearestFace&&height==HEIGHTS[0]?EasyPlacementRules.nearestHitPoint(clicked,side,eye):EasyPlacementRules.hitPoint(clicked,side,height);if(eye.distanceToSqr(point)>reachSquared)return null;
        var player=client.player;player.setYRot(yaw);player.setXRot(pitch);PlayerInputBridge.shift(player,sneak);
        var hit=new BlockHitResult(point,side,clicked,false);var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,hit);
        if(!context.canPlace()||!context.getClickedPos().equals(pos))return null;
        var predicted=AccuratePlacement.predict(mode,context,wanted,hit);
        if(!PrinterRules.placementMatches(predicted,wanted)||(stack.getItem().getClass()!=BlockItem.class&&!predicted.canSurvive(client.level,pos)))return null;
        return new PlacementPlan(hit,yaw,pitch,sneak);
    }
    private Outcome use(BlockHitResult hit,float yaw,float pitch,boolean sneak){return use(hit,yaw,pitch,sneak,null,null);}
    private Outcome use(BlockHitResult hit,float yaw,float pitch,boolean sneak,BlockPos target,BlockState wanted){
        long started=System.nanoTime();try{return useInternal(hit,yaw,pitch,sneak,target,wanted);}finally{diagnostics.sendNanos+=System.nanoTime()-started;}
    }
    private Outcome useInternal(BlockHitResult hit,float yaw,float pitch,boolean sneak,BlockPos target,BlockState wanted){
        var player=client.player;float oldYaw=player.getYRot(),oldPitch=player.getXRot();boolean oldSneak=player.isShiftKeyDown();
        if(player.getEyePosition().distanceToSqr(hit.getLocation())>Math.pow(client.player.blockInteractionRange(),2))return Outcome.UNSUPPORTED;
        var mode=AccuratePlacement.resolve(client,protocol.get());
        if(target!=null&&!AccuratePlacement.canUse(client,mode,target,wanted,hit))return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待放置确认"));
        boolean turn=yaw!=oldYaw||pitch!=oldPitch;acting=true;
        try{
            player.setYRot(yaw);player.setXRot(pitch);PlayerInputBridge.shift(player,sneak);
            if(turn)client.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw,pitch,player.onGround(),player.horizontalCollision));
            if(sneak!=oldSneak)PlayerInputBridge.sendShift(player,sneak);
            dispatched=true;var result=target==null?client.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit):AccuratePlacement.use(client,mode,target,wanted,hit);VersionGameplay.swingUse(player,InteractionHand.MAIN_HAND,result);
            return result.consumesAction()?Outcome.SENT:fail(client.getSingleplayerServer()!=null?Outcome.RETRY:Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.REJECTED,"放置未接受"));
        }finally{
            player.setYRot(oldYaw);player.setXRot(oldPitch);PlayerInputBridge.shift(player,oldSneak);
            if(sneak!=oldSneak)PlayerInputBridge.sendShift(player,oldSneak);
            if(turn)client.getConnection().send(new ServerboundMovePlayerPacket.Rot(oldYaw,oldPitch,player.onGround(),player.horizontalCollision));acting=false;
        }
    }
    private Outcome fluid(BlockPos pos,Item bucket,PrinterSettings s,int tick){
        var player=client.player;float yaw=player.getYRot(),pitch=player.getXRot();acting=true;
        try{
            for(Direction side:Direction.values()){
                Vec3 point=bucket==Items.BUCKET?Vec3.atCenterOf(pos):Vec3.atCenterOf(pos).add(side.getStepX()*0.49,side.getStepY()*0.49,side.getStepZ()*0.49);
                Vec3 delta=point.subtract(player.getEyePosition());float lookYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),lookPitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z)));
                player.setYRot(lookYaw);player.setXRot(lookPitch);
                var hit=client.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),player.getEyePosition().add(player.getViewVector(1).scale(client.player.blockInteractionRange())),net.minecraft.world.level.ClipContext.Block.OUTLINE,bucket==Items.BUCKET?net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY:net.minecraft.world.level.ClipContext.Fluid.NONE,player));
                if(hit.getType()!=HitResult.Type.BLOCK)continue;BlockPos target=hit.getBlockPos();
                if(bucket!=Items.BUCKET&&!(client.level.getBlockState(target).getBlock() instanceof LiquidBlockContainer))target=target.relative(hit.getDirection());
                if(!target.equals(pos))continue;
                var selected=equip(bucket,tick);if(selected!=null)return selected;
                client.getConnection().send(new ServerboundMovePlayerPacket.Rot(lookYaw,lookPitch,player.onGround(),player.horizontalCollision));
                dispatched=true;var result=client.gameMode.useItem(player,InteractionHand.MAIN_HAND);return result.consumesAction()?Outcome.SENT:Outcome.UNSUPPORTED;
            }
            return fail(Outcome.RETRY,PrinterReason.of(PrinterReason.Id.NO_FACE,"等待可用放水面"));
        }finally{player.setYRot(yaw);player.setXRot(pitch);client.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw,pitch,player.onGround(),player.horizontalCollision));acting=false;}
    }
    private Outcome destroyBlock(BlockPos pos,BlockState actual,PrinterSettings settings){
        if(actual.isAir()){breaking=null;return Outcome.STALE;}
        if(actual.getDestroySpeed(client.level,pos)<0)return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.UNSUPPORTED,"此方块不可直接破坏"));
        var ray=client.level.clip(new net.minecraft.world.level.ClipContext(client.player.getEyePosition(),Vec3.atCenterOf(pos),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,client.player));
        if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(pos))return fail(Outcome.UNSUPPORTED,PrinterReason.of(PrinterReason.Id.OBSTRUCTED,"目标被遮挡"));
        acting=true;try{
            dispatched=breakDispatched=true;
            if(!pos.equals(breaking)){client.gameMode.stopDestroyBlock();client.gameMode.startDestroyBlock(pos,ray.getDirection());breaking=pos.immutable();}
            else client.gameMode.continueDestroyBlock(pos,ray.getDirection());
            VersionGameplay.swingBreak(client.player,InteractionHand.MAIN_HAND);if(client.level.getBlockState(pos).isAir()){breaking=null;return Outcome.SENT;}
            return fail(Outcome.WAIT,PrinterReason.of(PrinterReason.Id.BREAKING,"正在破坏"));
        }finally{acting=false;}
    }
}
