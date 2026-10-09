package dev.betterlitematica.fabric;
import java.util.*;
import dev.betterlitematica.core.Vec3i;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.state.BlockState;
/** Manual and region work share the same exclusive transaction used by the printer. */
final class BedrockController {
    private static final int MAX_QUEUE=4096;
    private final Minecraft client;
    private final ProjectionController controller;
    private final NativeMiner miner;
    private final LinkedHashSet<BlockPos> queue=new LinkedHashSet<>();
    private final Map<BlockPos,Integer> cooling=new HashMap<>();
    private final List<BedrockSettings.Region> temporary=new ArrayList<>();
    private BlockPos current;
    private boolean enabled;
    private int ticks,scan;
    private long generation;
    private String status="已停止";
    BedrockController(Minecraft client,ProjectionController controller){this.client=client;this.controller=controller;miner=new NativeMiner(client,controller.inventoryTransfers(),this::settings);}
    BedrockSettings settings(){return controller.options().bedrock;}
    void configure(BedrockSettings settings){settings.validate();pause();generation++;cooling.clear();controller.options().bedrock=settings;controller.saveOptions();}
    boolean enabled(){return enabled;}
    String status(){return status;}
    int queued(){return queue.size()+(current==null?0:1);}
    void toggle(){if(enabled){pause();}else{miner.arm();controller.printer().pause("已暂停");enabled=true;status="等待目标";}client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(enabled?"破基岩：已启用":"破基岩：已暂停"));}
    void pause(){enabled=false;miner.reset();if(current!=null)queue.add(current);current=null;status="已暂停";}
    void check(){miner.check();}
    void clear(){miner.reset();queue.clear();cooling.clear();current=null;generation++;status="已停止";enabled=false;}
    void disconnect(){clear();temporary.clear();scan=0;}
    boolean accepts(BlockPos pos){return accepts(pos,settings());}
    boolean accepts(BlockPos pos,BedrockSettings settings){return client.level!=null&&WorldChunks.loaded(client.level,pos)&&accepts(settings,client.level.getBlockState(pos),pos.getY());}
    static boolean accepts(BedrockSettings settings,BlockState state,int y){
        if(settings.excludedY.contains(y))return false;var block=state.getBlock();
        if(state.isAir()||state.canBeReplaced()||block==Blocks.BARRIER||block instanceof CommandBlock||block==Blocks.STRUCTURE_BLOCK||block==Blocks.STRUCTURE_VOID||block==Blocks.JIGSAW)return false;
        return settings.whitelist.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
    }
    void add(BlockPos pos){if(!accepts(pos))throw new IllegalStateException("目标不在破基岩白名单内或位于排除层");if(queue.size()>=MAX_QUEUE)throw new IllegalStateException("破基岩队列已满");cooling.remove(pos);queue.add(pos.immutable());}
    boolean attack(BlockPos pos){if(NativeMiner.acting()||!enabled||!accepts(pos))return false;add(pos);return true;}
    void manualInput(){if(!enabled||NativeMiner.acting())return;if(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit&&accepts(hit.getBlockPos()))return;miner.reset();if(current!=null)queue.add(current);current=null;enabled=false;status="已暂停";}
    boolean use(BlockPos pos){if(NativeMiner.acting()||!settings().emptyHandToggle||client.player==null||!client.player.getMainHandItem().isEmpty()||!accepts(pos))return false;controller.action(this::toggle);return true;}
    List<BedrockSettings.Region> regions(){var result=new ArrayList<BedrockSettings.Region>();for(var region:settings().regions)if(region.world().equals(controller.worldKey()))result.add(region);result.addAll(temporary);return List.copyOf(result);}
    void removeRegion(UUID id){clear();settings().regions.removeIf(r->r.id().equals(id));temporary.removeIf(r->r.id().equals(id));controller.saveOptions();}
    void addSelection(boolean persistent){
        if(client.level==null)throw new IllegalStateException("请先进入世界");var boxes=controller.selection().boxes();if(boxes.isEmpty())throw new IllegalStateException("请先创建选区");
        if((persistent?settings().regions.size():temporary.size())+boxes.size()>BedrockSettings.MAX_REGIONS)throw new IllegalStateException("区域数量已达上限");
        var additions=new ArrayList<BedrockSettings.Region>();
        for(var box:boxes)additions.add(new BedrockSettings.Region(UUID.randomUUID(),"选区 "+(regions().size()+additions.size()+1),controller.worldKey(),client.level.dimension().identifier().toString(),box.first(),box.second(),persistent));
        (persistent?settings().regions:temporary).addAll(additions);if(persistent)controller.saveOptions();
    }
    void tick(){
        ticks++;if(client.level==null||client.player==null){disconnect();return;}if(!enabled)return;
        if(ClientUi.screen(client)!=null){if(current!=null||miner.active()){miner.reset();if(current!=null)queue.add(current);current=null;}status="等待返回游戏";return;}
        if(!client.isWindowActive()||client.player.isDeadOrDying()||controller.worldWriteBusy()){miner.reset();if(current!=null)queue.add(current);current=null;enabled=false;status="已暂停";return;}
        scanRegions();
        try{if(NativeMiner.recoverSuspended(ticks)){status="回收上次施工材料";return;}}catch(RuntimeException failure){status=failure.getMessage();enabled=false;miner.reset();return;}
        if(current==null){current=queue.stream().filter(this::near).min(Comparator.comparingDouble(p->p.distToCenterSqr(client.player.position()))).orElse(null);if(current==null){status="等待附近目标";return;}queue.remove(current);generation++;}
        try{
            if(!miner.active()&&!accepts(current)){current=null;return;}
            if(miner.step(generation,current,ticks)){current=null;status="破基岩完成";}else status=miner.reason();
        }catch(RuntimeException failure){
            status=failure.getMessage()==null?"破基岩已暂停":failure.getMessage();if(settings().debug)BetterLitematicaClient.LOGGER.info("Native mining: {} at {}",status,current);
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(status));
            if(cooling.size()>=MAX_QUEUE){enabled=false;status="失败目标过多，请清空任务后重试";}else cooling.put(current,Integer.MAX_VALUE);current=null;miner.reset();
        }
    }
    private boolean near(BlockPos pos){return client.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))<=Math.pow(client.player.blockInteractionRange(),2)&&WorldChunks.loaded(client.level,pos);}
    private void scanRegions(){
        var regions=regions();if(regions.isEmpty())return;String dimension=client.level.dimension().identifier().toString();var center=client.player.blockPosition();
        for(int i=0;i<128&&queue.size()<MAX_QUEUE;i++){
            int cell=scan++%1331;var pos=center.offset(cell%11-5,cell/121-5,cell/11%11-5);
            if(cooling.getOrDefault(pos,0)>ticks||pos.equals(current)||!near(pos))continue;
            boolean inside=false;for(var region:regions)if(region.dimension().equals(dimension)&&contains(region,pos)){inside=true;break;}
            if(inside&&accepts(pos))queue.add(pos.immutable());
        }
        if(scan>1_000_000)scan%=1331;if(ticks%100==0)cooling.values().removeIf(until->until<=ticks);
    }
    private static boolean contains(BedrockSettings.Region r,BlockPos p){var a=r.first();var b=r.second();return p.getX()>=Math.min(a.x(),b.x())&&p.getX()<=Math.max(a.x(),b.x())&&p.getY()>=Math.min(a.y(),b.y())&&p.getY()<=Math.max(a.y(),b.y())&&p.getZ()>=Math.min(a.z(),b.z())&&p.getZ()<=Math.max(a.z(),b.z());}
}
