package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.SchematicDocument;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Creative-only integrated-server operation. No client world writes and no authorization bypass. */
final class CreativePasteTask {
    private final ServerWorld world;private final UUID player;private final SchematicDocument document;
    private final PlacementLayout layout;private final PlacementLayout.Source cells;private final LayerRange layer;private final ReplaceRule rule;
    private final List<StateResolver1201> states=new ArrayList<>();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final boolean entities,nbt;
    private int part,extra,validationPart,validationState;
    private final DeferredSections queue;private final List<Set<Integer>> accepted=new ArrayList<>();private final List<BitSet> extraDone=new ArrayList<>();private long extrasRemaining,ticks,processed;
    private boolean validated;private volatile boolean cancelled,paused;private volatile String status="检查投影";
    private long changed;
    CreativePasteTask(ServerWorld world,UUID player,SchematicDocument document,Placement placement,LayerRange layer,ReplaceRule rule,boolean entities,boolean nbt){
        this.world=world;this.player=player;this.document=document;layout=new PlacementLayout(placement,document.parts().stream().map(SchematicDocument.Part::region).toList());cells=new PlacementLayout.Source(){public int state(PlacementLayout.Part part,Vec3i local){var p=document.parts().get(part.index());var offset=local.subtract(p.region().min());return p.blocks().get(offset.x()+offset.z()*p.region().size().x()+offset.y()*p.region().size().x()*p.region().size().z());}public BlockStateSpec spec(int region,int state){return document.parts().get(region).palette().get(state);}};this.layer=layer;this.rule=rule;this.entities=entities;this.nbt=nbt;queue=new DeferredSections(document.parts().stream().map(SchematicDocument.Part::region).toList());
        for(var region:document.parts()){int regionIndex=states.size();var transform=layout.part(regionIndex).transform();accepted.add(new HashSet<>());extraDone.add(new BitSet());if(layout.enabled(regionIndex))extrasRemaining+=region.blockTicks().size()+region.fluidTicks().size()+(entities?region.entities().size():0);states.add(new StateResolver1201(region.palette(),transform));var bounds=PlacementBounds.clipped(region.region(),transform,layer);
            if(!layout.enabled(regionIndex)||bounds==null)continue;
            for(int x:new int[]{bounds.min().x(),bounds.max().x()})for(int y:new int[]{bounds.min().y(),bounds.max().y()})for(int z:new int[]{bounds.min().z(),bounds.max().z()}){if(y<world.getBottomY()||y>=world.getTopY())throw new IllegalArgumentException("投影超出世界高度：包含 Y="+y+"，允许 "+world.getBottomY()+"～"+(world.getTopY()-1));if(!world.getWorldBorder().contains(new BlockPos(x,y,z)))throw new IllegalArgumentException("投影超出世界边界：X="+x+"，Z="+z);}
        }
    }
    CompletableFuture<String> result(){return result;}String status(){return status;}boolean paused(){return paused;}void paused(boolean value){paused=value;}void cancel(){cancelled=true;}void pause(){paused=!paused;}
    void tick(){
        if(result.isDone())return;if(cancelled){status="已取消（已写入的方块保留）";result.complete(status);return;}if(paused){status="已暂停";return;}
        var actor=world.getServer().getPlayerManager().getPlayer(player);if(actor==null||!actor.isCreative()||actor.getServerWorld()!=world){result.completeExceptionally(new IllegalStateException("玩家已离开当前世界或创造模式"));return;}
        ticks++;long until=System.nanoTime()+2_000_000L;int budget=2048;
        try{
            if(!validated){while(validationPart<states.size()&&budget-->0&&System.nanoTime()<until){if(!layout.enabled(validationPart)){validationPart++;validationState=0;continue;}var p=document.parts().get(validationPart);var resolver=states.get(validationPart);if(resolver.unresolved(validationState))throw new IllegalArgumentException("源投影包含未知方块，未开始粘贴："+p.palette().get(validationState));if(++validationState==p.palette().size()){validationPart++;validationState=0;}}if(validationPart<states.size())return;validated=true;return;}
            queue.discover(512,key->{var p=document.parts().get(key.region());if(outside(key)||rule!=ReplaceRule.ALL&&p.nonAir().sectionEmpty(key))return 0;return anyLoaded(key)?1:-1;},until);
            int turns=Math.min(queue.pending(),64);
            while(turns-->0&&budget>0&&System.nanoTime()<until){
                var work=queue.poll(ticks);if(work==null)break;var p=document.parts().get(work.key.region());var r=p.region();var origin=r.sectionOrigin(work.key);var transform=layout.part(work.key.region()).transform();var overlaps=layout.overlapping(PlacementBounds.clipped(new Region("section",origin,new Vec3i(16,16,16)),transform,LayerRange.ALL));
                if(!anyLoaded(work.key)){work.retryAt=ticks+20;queue.unavailable(work);continue;}
                while(work.cursor<4096&&budget>0&&System.nanoTime()<until){
                    if(rule!=ReplaceRule.ALL){int next=p.nonAir().next(work.key,work.cursor);work.done.set(work.cursor,next);work.cursor=next;if(next==4096)break;}
                    int i=work.cursor++;if(work.done.get(i))continue;
                    var local=origin.add(new Vec3i(i&15,i>>>8,(i>>>4)&15));if(!r.contains(local)){work.done.set(i);continue;}
                    var at=transform.apply(local);if(!layer.contains(at)){work.done.set(i);continue;}var owner=layout.sample(overlaps,at,cells);if(owner==null||owner.part().index()!=work.key.region()){work.done.set(i);continue;}
                    BlockPos pos=new BlockPos(at.x(),at.y(),at.z());if(!world.isChunkLoaded(pos))continue;
                    var offset=local.subtract(r.min());int index=offset.x()+offset.z()*r.size().x()+offset.y()*r.size().x()*r.size().z();
                    var expected=states.get(work.key.region()).resolve(p.blocks().get(index));var actual=world.getBlockState(pos);budget--;
                    if(rule.permits(expected.isAir(),actual.isAir())&&!expected.isOf(net.minecraft.block.Blocks.STRUCTURE_VOID)){
                        if(p.tickCells().contains(index))accepted.get(work.key.region()).add(index);
                        if(!expected.equals(actual)){world.setBlockState(pos,expected,Block.NOTIFY_LISTENERS|Block.FORCE_STATE|Block.SKIP_DROPS);changed++;}
                        if(nbt&&p.blockEntities().containsKey(index)){
                            var be=world.getBlockEntity(pos);if(be!=null){NbtCompound tag=(NbtCompound)NbtBridge.game(p.blockEntities().get(index));BlockEntityNbtTransform.placed(tag,transform);tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());be.readNbt(tag);be.markDirty();world.updateListeners(pos,expected,expected,Block.NOTIFY_LISTENERS);}
                        }
                    }
                    work.done.set(i);processed++;
                }
                if(work.cursor==4096&&!work.finished()){work.cursor=0;work.retryAt=ticks+20;}
                if(work.retryAt<=ticks)queue.resume(work);else queue.defer(work);
            }
            if(queue.finished()){
                int attempts=256;
                while(extrasRemaining>0&&attempts-->0&&System.nanoTime()<until){
                    var p=document.parts().get(part);int count=layout.enabled(part)?p.blockTicks().size()+p.fluidTicks().size()+(entities?p.entities().size():0):0;
                    if(extra>=count){part=(part+1)%document.parts().size();extra=0;continue;}
                    int item=extra++;if(extraDone.get(part).get(item))continue;boolean ready;
                    if(item<p.blockTicks().size())ready=schedule(p.blockTicks().get(item),p.region(),false,part);
                    else if(item<p.blockTicks().size()+p.fluidTicks().size())ready=schedule(p.fluidTicks().get(item-p.blockTicks().size()),p.region(),true,part);
                    else ready=spawn(p.entities().get(item-p.blockTicks().size()-p.fluidTicks().size()),p.region(),part);
                    if(ready){extraDone.get(part).set(item);extrasRemaining--;}
                }
            }
            if(queue.finished()&&extrasRemaining==0){status="粘贴完成，修改 "+changed+" 格";result.complete(status);}
            else status="粘贴 · 已处理 "+processed+" · 修改 "+changed+(queue.pending()==0?" · 等待区块":"");
        }catch(Exception e){status="粘贴失败："+e.getMessage();result.completeExceptionally(e);}
    }
    private boolean outside(SectionKey key){if(!layout.enabled(key.region()))return true;var transform=layout.part(key.region()).transform();var r=document.parts().get(key.region()).region();var base=r.sectionOrigin(key);var size=new Vec3i(Math.min(16,r.size().x()-key.x()*16),Math.min(16,r.size().y()-key.y()*16),Math.min(16,r.size().z()-key.z()*16));return PlacementBounds.clipped(new Region("section",base,size),transform,layer)==null;}
    private boolean anyLoaded(SectionKey key){var transform=layout.part(key.region()).transform();var r=document.parts().get(key.region()).region();var base=r.sectionOrigin(key);for(int x:new int[]{0,Math.min(15,r.size().x()-key.x()*16-1)})for(int z:new int[]{0,Math.min(15,r.size().z()-key.z()*16-1)}){var p=transform.apply(base.add(new Vec3i(x,0,z)));if(world.isChunkLoaded(p.x()>>4,p.z()>>4))return true;}return false;}
    private boolean schedule(Map<String,Object> tag,Region region,boolean fluid,int partIndex)throws java.io.IOException{
        Vec3i local=SchematicDocument.vector(tag);if(local.x()<0||local.y()<0||local.z()<0||local.x()>=region.size().x()||local.y()>=region.size().y()||local.z()>=region.size().z())return true;
        if(!accepted.get(partIndex).contains(local.x()+local.z()*region.size().x()+local.y()*region.size().x()*region.size().z()))return true;
        Vec3i at=layout.part(partIndex).transform().apply(region.min().add(local));if(!layer.contains(at))return true;BlockPos pos=new BlockPos(at.x(),at.y(),at.z());if(!world.isChunkLoaded(pos))return false;
        int delay=dev.betterlitematica.io.NbtReader.integer(tag,"Time"),priority=dev.betterlitematica.io.NbtReader.integer(tag,"Priority");Identifier id=new Identifier((String)tag.get(fluid?"Fluid":"Block"));
        if(fluid){if(Registries.FLUID.containsId(id)&&world.getFluidState(pos).getFluid()==Registries.FLUID.get(id))world.scheduleFluidTick(pos,Registries.FLUID.get(id),Math.max(0,delay),TickPriority.byIndex(priority));}
        else if(Registries.BLOCK.containsId(id)&&world.getBlockState(pos).getBlock()==Registries.BLOCK.get(id))world.scheduleBlockTick(pos,Registries.BLOCK.get(id),Math.max(0,delay),TickPriority.byIndex(priority));
        return true;
    }
    private boolean spawn(Map<String,Object> data,Region region,int partIndex){
        NbtCompound tag=(NbtCompound)NbtBridge.game(data);EntityNbtTransform.placed(tag,region.min(),layout.part(partIndex).transform());
        var pos=tag.getList("Pos",6);if(pos.size()!=3||!layer.contains(new Vec3i((int)Math.floor(pos.getDouble(0)),(int)Math.floor(pos.getDouble(1)),(int)Math.floor(pos.getDouble(2)))))return true;
        if(!loadedEntity(tag))return false;
        var entity=net.minecraft.entity.EntityType.loadEntityWithPassengers(tag,world,e->e);if(entity!=null)world.spawnNewEntityAndPassengers(entity);return true;
    }
    private boolean loadedEntity(NbtCompound tag){var pos=tag.getList("Pos",6);if(pos.size()!=3||!world.isChunkLoaded(BlockPos.ofFloored(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2))))return false;if(tag.contains("TileX")&&!world.isChunkLoaded(new BlockPos(tag.getInt("TileX"),tag.getInt("TileY"),tag.getInt("TileZ"))))return false;var passengers=tag.getList("Passengers",10);for(int i=0;i<passengers.size();i++)if(!loadedEntity(passengers.getCompound(i)))return false;return true;}
}
