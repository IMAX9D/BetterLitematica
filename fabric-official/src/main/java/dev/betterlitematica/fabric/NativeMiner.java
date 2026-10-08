package dev.betterlitematica.fabric;

import dev.betterlitematica.core.BedrockPlan;
import dev.betterlitematica.core.Vec3i;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** A single, bounded vanilla piston transaction. Only server updates settle writes. */
final class NativeMiner {
    private static NativeMiner owner;
    private static final Set<NativeMiner> parked=Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean callback;
    private final Minecraft client;
    private final InventoryTransfers transfers;
    private final Supplier<BedrockSettings> settings;
    private final Map<BlockPos,BlockState> receipts=new HashMap<>();
    private final LinkedHashMap<BlockPos,BlockState> placed=new LinkedHashMap<>();
    private Object world,connection;
    private BlockPos target;
    private BlockState targetState;
    private BedrockPlan.Layout plan;
    private long generation;
    private int phase,attempt,started,next,lastTick=Integer.MIN_VALUE,prepareIndex;
    private boolean removed,failed,suspended;
    private boolean lookLocked;private float aimYaw,aimPitch;private int aimTick;
    private String reason="",failure="";
    NativeMiner(Minecraft client,InventoryTransfers transfers,Supplier<BedrockSettings> settings){this.client=client;this.transfers=transfers;this.settings=settings;}
    static boolean acting(){return callback;}
    public static float packetYaw(float original){return owner!=null&&owner.lookLocked&&owner.client.isSameThread()?owner.aimYaw:original;}
    public static float packetPitch(float original){return owner!=null&&owner.lookLocked&&owner.client.isSameThread()?owner.aimPitch:original;}
    static boolean busy(){return owner!=null||!parked.isEmpty();}
    /** The controller owns two instances (manual and printer); switching modes drains
     * their retained temporary structures before either starts new world writes. */
    static boolean recoverSuspended(int tick){for(var miner:List.copyOf(parked))if(miner.recoverPending(tick))return true;return false;}
    static void update(BlockPos pos,BlockState state){if(owner!=null)owner.confirmed(pos,state);for(var miner:parked)if(miner!=owner)miner.confirmed(pos,state);}
    static void disconnectAll(){if(owner!=null)owner.discard();for(var miner:List.copyOf(parked))miner.discard();}
    boolean active(){return target!=null&&!suspended;}
    boolean owns(long generation,long position){return active()&&this.generation==generation&&target.asLong()==position;}
    String problem(){return "";}
    String reason(){return reason;}
    void check(){
        if(client.player==null||client.level==null||client.gameMode==null||client.gameMode.getPlayerMode()!=GameType.SURVIVAL)throw new IllegalStateException("破基岩需要生存模式");
        if(suspended&&!placed.isEmpty())return;
        if(count(Items.PISTON)<2)throw new IllegalStateException("破基岩需要至少 2 个普通活塞");
        if(count(Items.REDSTONE_TORCH)<1)throw new IllegalStateException("缺少红石火把");
    }
    void confirmed(BlockPos pos,BlockState state){
        if(target==null||world!=client.level||connection!=client.getConnection())return;
        if(pos.equals(target)){if(!state.is(targetState.getBlock()))removed=true;return;}
        if(plan!=null&&(pos.equals(at(plan.piston()))||pos.equals(at(plan.torch()))||pos.equals(at(plan.support()))||pos.equals(at(plan.head())))){
            receipts.put(pos.immutable(),state);
            // The stale retract event keeps its original facing in the moving block
            // entity, so the replacement settles back to the initial facing.
            if(phase>=6&&pos.equals(at(plan.piston()))&&state.is(Blocks.PISTON)
                &&(state.getValue(BlockStateProperties.FACING).get3DDataValue()==plan.initialFace()||state.getValue(BlockStateProperties.FACING).get3DDataValue()==plan.breakFace())&&placed.containsKey(pos))placed.put(pos.immutable(),state);
            if(placed.containsKey(pos)&&ownedState(pos,state))placed.put(pos.immutable(),state);
        }
    }
    void reset(){
        releaseLook();
        if(target!=null&&!placed.isEmpty()&&world==client.level&&connection==client.getConnection()){
            suspended=true;if(owner==this)owner=null;parked.add(this);callback=false;return;
        }
        discard();
    }
    private void discard(){
        releaseLook();
        if(owner==this)owner=null;
        parked.remove(this);suspended=false;target=null;plan=null;world=null;connection=null;placed.clear();receipts.clear();lastTick=Integer.MIN_VALUE;callback=false;
    }
    boolean recoverPending(int tick){
        if(!suspended)return false;
        if(world!=client.level||connection!=client.getConnection()){discard();return false;}
        if(owner!=null&&owner!=this)return true;
        if(ClientUi.screen(client)!=null||!client.isWindowActive()||client.player==null||client.player.isDeadOrDying())return true;
        if(client.player.containerMenu!=client.player.inventoryMenu||transfers.inFlight()){reason="等待背包操作确认";return true;}
        owner=this;callback=true;reason="回收上次施工材料";
        try{if(cleanup(tick)){discard();return false;}return true;}finally{callback=false;}
    }
    boolean step(long gen,BlockPos pos,int tick){
        if(recoverPending(tick))return false;
        if(owner!=null&&owner!=this){reason="等待另一项破基岩任务";return false;}
        if(active()&&(world!=client.level||connection!=client.getConnection()||generation!=gen||!target.equals(pos))){reset();throw new IllegalStateException("破基岩任务已变更");}
        if(!active()){
            check();if(!WorldChunks.loaded(client.level,pos))throw new IllegalStateException("等待目标区块加载");
            owner=this;target=pos.immutable();targetState=client.level.getBlockState(pos);world=client.level;connection=client.getConnection();generation=gen;
            phase=0;attempt=0;started=tick;next=tick;removed=false;failed=false;failure="";prepareIndex=0;
        }
        if(lastTick==tick)return false;lastTick=tick;
        if(ClientUi.screen(client)!=null||!client.isWindowActive()||client.player==null||client.player.isDeadOrDying()||client.player.containerMenu!=client.player.inventoryMenu){reset();throw new IllegalStateException("破基岩已暂停");}
        if(!reachable(target)){releaseLook();reason="等待靠近目标";return false;}
        if(phase==4&&tick<next)return false;
        if(tick-started>settings.get().timeoutTicks&&phase<7){releaseLook();failed=true;failure="破基岩超时，服务器可能不支持此机制";phase=7;}
        callback=true;
        try{
            if(phase==0){
                reason="准备破基岩材料";
                if(!prepare(tick))return false;
                plan=choose();if(plan==null){reset();throw new IllegalStateException("没有可用的活塞方案，请靠近并留出空间");}
                phase=1;
            }
            if(phase<7&&!allLoaded()){releaseLook();reason="等待施工区块加载";return false;}
            if(phase<7&&!allReachable()){releaseLook();reason="等待靠近施工位置";return false;}
            if(removed&&phase<7)phase=7;
            if(phase==1){
                var support=at(plan.support());
                if(!placed.containsKey(support)&&client.level.getBlockState(support).isFaceSturdy(client.level,support,Direction.from3DDataValue(plan.torchFace()))){phase=2;}
                else if(placeConfirmed(support,Blocks.SLIME_BLOCK.defaultBlockState(),Direction.UP,tick)){phase=2;}else return false;
            }
            if(phase==2){
                reason="放置活塞";
                if(placeConfirmed(at(plan.piston()),Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.from3DDataValue(plan.initialFace())),Direction.from3DDataValue(plan.initialFace()),tick)){phase=3;}else return false;
            }
            if(phase==3){
                reason="放置红石火把";var face=Direction.from3DDataValue(plan.torchFace());
                var state=face==Direction.UP?Blocks.REDSTONE_TORCH.defaultBlockState():Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,face);
                if(placeConfirmed(at(plan.torch()),state,face,tick)){phase=4;next=tick+(settings.get().shortWait?1:3);}return false;
            }
            if(phase==4){
                reason="等待活塞伸出";
                var state=receipts.get(at(plan.piston()));
                if(state==null||!state.is(Blocks.PISTON)||!state.getValue(BlockStateProperties.EXTENDED))return false;
                if(!prepare(tick))return false;
                if(!aim(Direction.from3DDataValue(plan.breakFace()),tick))return false;
                if(!selectTool(at(plan.piston())))throw new IllegalStateException("需要能瞬挖活塞的工具（效率 V、急迫 II，站稳且无疲劳）");
                if(count(Items.PISTON)<1)throw new IllegalStateException("缺少重放活塞");
                // The unpower, break and replacement must reach the server in this order
                // without a tick boundary or inventory SWAP in between.
                reason="破除目标";
                if(!ownedState(at(plan.torch()),client.level.getBlockState(at(plan.torch())))||!ownedState(at(plan.piston()),client.level.getBlockState(at(plan.piston()))))throw new IllegalStateException("施工结构已被更改");
                instantBreak(at(plan.torch()));instantBreak(at(plan.piston()));
                var face=Direction.from3DDataValue(plan.breakFace());
                receipts.remove(at(plan.piston()));
                try{if(!place(at(plan.piston()),Items.PISTON,face))throw new IllegalStateException("反向活塞放置未接受");}finally{releaseLook();}
                placed.put(at(plan.piston()),Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING,face));
                phase=6;next=tick+settings.get().timeoutTicks;started=tick;return false;
            }
            if(phase==6){
                reason="等待破除确认";
                if(removed){phase=7;}else if(tick>=next){failed=true;failure="目标未破除";phase=7;}else return false;
            }
            if(phase==7){
                reason="回收施工材料";
                if(!cleanup(tick))return false;
                if(removed){reset();reason="破基岩完成";return true;}
                if(failed&&attempt++<settings.get().retries){phase=0;started=tick;prepareIndex=0;plan=null;receipts.clear();failed=false;return false;}
                String message=failure.isEmpty()?"目标未破除":failure;reset();throw new IllegalStateException(message);
            }
            return false;
        }finally{callback=false;}
    }
    private boolean prepare(int tick){
        Item[] materials={Items.PISTON,Items.REDSTONE_TORCH,Items.SLIME_BLOCK};
        while(prepareIndex<materials.length){var item=materials[prepareIndex];if(item==Items.SLIME_BLOCK&&count(item)==0){prepareIndex++;continue;}
            var result=transfers.equipForPrinter(item,tick);if(result==InventoryTransfers.Result.MISSING)throw new IllegalStateException("缺少 "+item.getName(new ItemStack(item)).getString());if(result==InventoryTransfers.Result.WAIT)return false;prepareIndex++;
        }
        var result=transfers.equipForPrinter(stack->fastTool(stack),tick);if(result==InventoryTransfers.Result.MISSING)throw new IllegalStateException("需要能瞬挖活塞且剩余耐久充足的工具");
        if(result==InventoryTransfers.Result.WAIT)return false;
        if(hotbar(Items.PISTON)<0||hotbar(Items.REDSTONE_TORCH)<0)throw new IllegalStateException("请为活塞、火把和工具留出快捷栏位置");
        return true;
    }
    private boolean fastTool(ItemStack stack){
        if(!transfers.usableForPrinter(stack)||!(stack.is(ItemTags.PICKAXES))||stack.getMaxDamage()-stack.getDamageValue()<=5)return false;
        var inv=client.player.getInventory();var old=inv.getItem(inv.getSelectedSlot());
        try{inv.setItem(inv.getSelectedSlot(),stack);return Blocks.PISTON.defaultBlockState().getDestroyProgress(client.player,client.level,target)>=.7f;}
        finally{inv.setItem(inv.getSelectedSlot(),old);}
    }
    private boolean selectTool(BlockPos pos){
        var inv=client.player.getInventory();for(int i=0;i<9;i++)if(fastTool(inv.getItem(i))){inv.setSelectedSlot(i);client.gameMode.ensureHasSentCarriedItem();return client.level.getBlockState(pos).getDestroyProgress(client.player,client.level,pos)>=.7f;}return false;
    }
    private BedrockPlan.Layout choose(){
        var source=new Vec3i(target.getX(),target.getY(),target.getZ());var options=settings.get();
        return BedrockPlan.layouts(source).stream().filter(p->options.breakDirections.contains(BedrockSettings.Face.values()[p.breakFace()])&&options.initialFacings.contains(BedrockSettings.Face.values()[p.initialFace()]))
            .filter(this::valid).min(Comparator.comparingDouble(p->score(p))).orElse(null);
    }
    private double score(BedrockPlan.Layout p){return (p.breakFace()==0?0:20)+(p.initialFace()==1?0:5)+(client.level.getBlockState(at(p.support())).isAir()?5:0)+client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(at(p.piston())));}
    private boolean valid(BedrockPlan.Layout p){
        for(var pos:List.of(at(p.piston()),at(p.head()),at(p.torch()),at(p.support())))if(!reachable(pos))return false;
        for(var pos:List.of(at(p.piston()),at(p.head()),at(p.torch())))if(!client.level.getBlockState(pos).isAir()||!client.level.getFluidState(pos).isEmpty())return false;
        var support=at(p.support());var base=client.level.getBlockState(support);
        if(!base.isFaceSturdy(client.level,support,Direction.from3DDataValue(p.torchFace()))&&(!base.isAir()||hotbar(Items.SLIME_BLOCK)<0))return false;
        if(p.throughTarget()&&!targetState.isRedstoneConductor(client.level,target))return false;
        for(var pos:List.of(at(p.piston()),at(p.head()),support))if(!client.level.getBlockState(pos).isFaceSturdy(client.level,pos,Direction.UP)&&!client.level.isUnobstructed(Blocks.PISTON.defaultBlockState(),pos,net.minecraft.world.phys.shapes.CollisionContext.empty()))return false;
        // Never disturb somebody else's power source or start with an already powered piston.
        var piston=at(p.piston());if(client.level.hasNeighborSignal(piston)||client.level.hasNeighborSignal(piston.above()))return false;
        return true;
    }
    private boolean reachable(BlockPos pos){return client.level!=null&&WorldChunks.loaded(client.level,pos)&&!client.level.isOutsideBuildHeight(pos)&&client.level.getWorldBorder().isWithinBounds(pos)&&client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))<=Math.pow(client.player.blockInteractionRange(),2);}
    private boolean allLoaded(){return List.of(at(plan.piston()),at(plan.head()),at(plan.torch()),at(plan.support())).stream().allMatch(p->WorldChunks.loaded(client.level,p));}
    private boolean allReachable(){return List.of(at(plan.piston()),at(plan.torch()),at(plan.support())).stream().allMatch(this::reachable);}
    private boolean placeConfirmed(BlockPos pos,BlockState expected,Direction face,int tick){
        var receipt=receipts.get(pos);
        if(receipt!=null&&samePlacement(receipt,expected))return true;
        if(placed.containsKey(pos))return false;
        if(!client.level.getBlockState(pos).isAir())throw new IllegalStateException("施工位置已被占用");
        if(!aim(face,tick))return false;
        try{if(!place(pos,expected.getBlock().asItem(),face))throw new IllegalStateException("施工方块放置未接受");}finally{releaseLook();}
        placed.put(pos,expected);return false;
    }
    private boolean aim(Direction face,int tick){
        if(face.getAxis().isVertical())return true;
        float yaw=rotation(face,client.player.getYRot());
        if(!lookLocked||aimYaw!=yaw){aimYaw=yaw;aimPitch=0;aimTick=tick;lookLocked=true;client.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw,0,client.player.onGround(),client.player.horizontalCollision));return false;}
        // PlayerEntity updates headYaw in its next tick; the placement path reads
        // that head rotation, not the body yaw just received in the movement packet.
        return tick-aimTick>=2;
    }
    private void releaseLook(){
        boolean restore=lookLocked;lookLocked=false;
        if(restore&&client.player!=null&&world==client.level&&connection==client.getConnection())client.getConnection().send(new ServerboundMovePlayerPacket.Rot(client.player.getYRot(),client.player.getXRot(),client.player.onGround(),client.player.horizontalCollision));
    }
    private static float rotation(Direction face,float fallback){return switch(face){case NORTH->0;case SOUTH->180;case EAST->90;case WEST->-90;default->fallback;};}
    private boolean place(BlockPos pos,Item item,Direction face){
        int slot=hotbar(item);if(slot<0)return false;
        var player=client.player;float yaw=player.getYRot(),pitch=player.getXRot(),headYaw=player.getYHeadRot();boolean sneak=player.input.keyPresses.shift();
        float rotatedYaw=rotation(face,yaw);float rotatedPitch=face==Direction.UP?90:face==Direction.DOWN?-90:0;
        try{
            player.getInventory().setSelectedSlot(slot);client.gameMode.ensureHasSentCarriedItem();player.setYRot(rotatedYaw);player.setYHeadRot(rotatedYaw);player.setXRot(rotatedPitch);PlayerInputBridge.shift(player,true);
            client.getConnection().send(new ServerboundMovePlayerPacket.Rot(rotatedYaw,rotatedPitch,player.onGround(),player.horizontalCollision));
            if(!sneak)PlayerInputBridge.sendShift(player,true);
            var hit=new BlockHitResult(Vec3.atCenterOf(pos).add(-face.getStepX()*.49,-face.getStepY()*.49,-face.getStepZ()*.49),face,pos,false);
            return client.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit).consumesAction();
        }finally{
            player.setYRot(yaw);player.setYHeadRot(headYaw);player.setXRot(pitch);PlayerInputBridge.shift(player,sneak);
            if(!sneak)PlayerInputBridge.sendShift(player,false);
            client.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw,pitch,player.onGround(),player.horizontalCollision));
        }
    }
    private void instantBreak(BlockPos pos){
        var state=client.level.getBlockState(pos);if(state.isAir())return;
        float delta=state.getDestroyProgress(client.player,client.level,pos);if(delta<.7f)throw new IllegalStateException("挖掘速度不足，已停止破基岩");
        client.gameMode.startDestroyBlock(pos,Direction.UP);
        if(delta<1){
            client.gameMode.startPrediction(client.level,sequence->new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,pos,Direction.UP,sequence));
            client.gameMode.destroyBlock(pos); // Vanilla prediction only; never a completion receipt.
        }
    }
    private boolean cleanup(int tick){
        for(var it=placed.entrySet().iterator();it.hasNext();){var entry=it.next();var pos=entry.getKey();
            if(!reachable(pos)){reason="等待靠近以回收材料";return false;}
            var actual=client.level.getBlockState(pos);var receipt=receipts.get(pos);
            if(receipt!=null&&receipt.isAir()&&actual.isAir()){it.remove();continue;}
            if(pos.equals(at(plan.piston()))&&actual.is(Blocks.MOVING_PISTON))return false;
            if(!samePlacement(actual,entry.getValue())){if(!actual.isAir())it.remove();return false;}
            if(!selectTool(pos))throw new IllegalStateException("工具不足，施工材料等待手动回收");
            instantBreak(pos);return false;
        }return true;
    }
    private boolean ownedState(BlockPos pos,BlockState actual){var expected=placed.get(pos);return expected!=null&&samePlacement(actual,expected);}
    private static boolean samePlacement(BlockState actual,BlockState expected){return actual.is(expected.getBlock())&&(!expected.hasProperty(BlockStateProperties.FACING)||actual.getValue(BlockStateProperties.FACING)==expected.getValue(BlockStateProperties.FACING))&&(!expected.hasProperty(BlockStateProperties.HORIZONTAL_FACING)||actual.getValue(BlockStateProperties.HORIZONTAL_FACING)==expected.getValue(BlockStateProperties.HORIZONTAL_FACING));}
    private int hotbar(Item item){var inv=client.player.getInventory();for(int i=0;i<9;i++)if(inv.getItem(i).is(item)&&transfers.usableForPrinter(inv.getItem(i)))return i;return -1;}
    private int count(Item item){int result=0;var inv=client.player.getInventory();for(int i=0;i<36;i++)if(inv.getItem(i).is(item))result+=inv.getItem(i).getCount();return result;}
    private static BlockPos at(Vec3i p){return new BlockPos(p.x(),p.y(),p.z());}
}
