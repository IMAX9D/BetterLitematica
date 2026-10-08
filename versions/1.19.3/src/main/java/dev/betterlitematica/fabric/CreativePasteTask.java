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
    private final PasteChunks chunks;
    private final Map<SectionKey,SectionWork> sections=new HashMap<>();
    private int part,extra;
    private final DeferredSections queue;private final List<Set<Integer>> accepted=new ArrayList<>();private final List<BitSet> extraDone=new ArrayList<>();private long extrasRemaining,ticks,processed;
    private volatile boolean cancelled,paused;private volatile String status="检查投影";
    private long changed,skippedUnknown;
    private static final class SectionWork {
        final PlacementBounds bounds;final Vec3i origin;final List<PlacementLayout.Part> overlaps;
        final int baseIndex,strideY,strideZ;final PlacementTransform transform;
        net.minecraft.world.chunk.WorldChunk[] targets;
        SectionWork(SchematicDocument.Part part,SectionKey key,PlacementBounds bounds,PlacementTransform transform,List<PlacementLayout.Part> overlaps){
            this.bounds=bounds;this.transform=transform;this.overlaps=overlaps;origin=part.region().sectionOrigin(key);
            strideZ=part.region().size().x();strideY=strideZ*part.region().size().z();var offset=origin.subtract(part.region().min());baseIndex=offset.x()+offset.y()*strideY+offset.z()*strideZ;
        }
        net.minecraft.world.chunk.WorldChunk chunk(BlockPos pos){int nx=(bounds.max().x()>>4)-(bounds.min().x()>>4)+1;return targets[(pos.getX()>>4)-(bounds.min().x()>>4)+((pos.getZ()>>4)-(bounds.min().z()>>4))*nx];}
        boolean empty(){for(var chunk:targets)for(int y=bounds.min().y()>>4;y<=bounds.max().y()>>4;y++)if(!chunk.getSection(chunk.sectionCoordToIndex(y)).isEmpty())return false;return true;}
    }
    CreativePasteTask(ServerWorld world,UUID player,SchematicDocument document,Placement placement,LayerRange layer,ReplaceRule rule,boolean entities,boolean nbt){
        this.world=world;this.player=player;this.document=document;layout=new PlacementLayout(placement,document.parts().stream().map(SchematicDocument.Part::region).toList());cells=new PlacementLayout.Source(){public int state(PlacementLayout.Part part,Vec3i local){var p=document.parts().get(part.index());var offset=local.subtract(p.region().min());return p.blocks().get(offset.x()+offset.z()*p.region().size().x()+offset.y()*p.region().size().x()*p.region().size().z());}public BlockStateSpec spec(int region,int state){return document.parts().get(region).palette().get(state);}};this.layer=layer;this.rule=rule;this.entities=entities;this.nbt=nbt;queue=new DeferredSections(document.parts().stream().map(SchematicDocument.Part::region).toList(),true);chunks=new PasteChunks(world);
        for(var region:document.parts()){int regionIndex=states.size();var transform=layout.part(regionIndex).transform();accepted.add(new HashSet<>());extraDone.add(new BitSet());if(layout.enabled(regionIndex))extrasRemaining+=region.blockTicks().size()+region.fluidTicks().size()+(entities?region.entities().size():0);states.add(new StateResolver1201(region.palette(),transform));var bounds=PlacementBounds.clipped(region.region(),transform,layer);
            if(!layout.enabled(regionIndex)||bounds==null)continue;
            int height=bounds.max().y()-bounds.min().y()+1;
            if(height>world.getHeight())throw new IllegalArgumentException("投影高 "+height+" 格，超过世界高度 "+world.getHeight()+" 格");
            for(int x:new int[]{bounds.min().x(),bounds.max().x()})for(int y:new int[]{bounds.min().y(),bounds.max().y()})for(int z:new int[]{bounds.min().z(),bounds.max().z()}){if(y<world.getBottomY()||y>=world.getTopY())throw new IllegalArgumentException("投影 Y "+bounds.min().y()+"～"+bounds.max().y()+" 超出世界 "+world.getBottomY()+"～"+(world.getTopY()-1));if(!world.getWorldBorder().contains(new BlockPos(x,y,z)))throw new IllegalArgumentException("投影超出世界边界：X="+x+"，Z="+z);}
        }
        result.whenComplete((value,failure)->{chunks.close();sections.clear();queue.clear();});
    }
    CompletableFuture<String> result(){return result;}String status(){return status;}boolean paused(){return paused;}void paused(boolean value){paused=value;}void cancel(){cancelled=true;world.getServer().execute(this::cancelled);}void pause(){paused=!paused;}
    private void cancelled(){if(!result.isDone()){status="已取消（已写入的方块保留）";result.complete(status);}}
    void tick(){
        if(result.isDone())return;if(cancelled){cancelled();return;}if(paused){status="已暂停";return;}
        var actor=world.getServer().getPlayerManager().getPlayer(player);if(actor==null||!actor.isCreative()||actor.getWorld()!=world){result.completeExceptionally(new IllegalStateException("玩家已离开当前世界或创造模式"));return;}
        ticks++;chunks.beginTick();long until=System.nanoTime()+16_000_000L;int budget=32768;
        try{
            queue.discover(512,this::admit,until);
            int turns=Math.min(queue.pending(),512);boolean waiting=false;
            while(turns-->0&&budget>0&&!cancelled&&System.nanoTime()<until){
                var work=queue.poll(ticks);if(work==null)break;var p=document.parts().get(work.key.region());var r=p.region();var context=sections.get(work.key);var origin=context.origin;var transform=context.transform;
                if(context.targets==null){
                    context.targets=chunks.ready(context.bounds);
                    if(context.targets==null){waiting=true;work.retryAt=ticks+1;queue.defer(work);continue;}
                    // This read and marking happen atomically on the server thread. ALL still clears occupied destinations.
                    if(rule==ReplaceRule.ALL&&context.empty()){
                        int cursor=0;
                        while(cursor<4096){int next=p.nonAir().next(work.key,cursor);work.done.set(cursor,next);cursor=next+1;}
                    }
                }
                while(work.cursor<4096&&budget>0&&!cancelled&&System.nanoTime()<until){
                    if(rule!=ReplaceRule.ALL){int next=p.nonAir().next(work.key,work.cursor);work.done.set(work.cursor,next);work.cursor=next;if(next==4096)break;}
                    work.cursor=work.done.nextClearBit(work.cursor);if(work.cursor>=4096)break;
                    int i=work.cursor++;
                    var local=origin.add(new Vec3i(i&15,i>>>8,(i>>>4)&15));if(!r.contains(local)){work.done.set(i);continue;}
                    var at=transform.apply(local);if(!layer.contains(at)){work.done.set(i);continue;}
                    if(context.overlaps.size()!=1||layout.placement().overlapRule()==ReplaceRule.NON_AIR){var owner=layout.sample(context.overlaps,at,cells);if(owner==null||owner.part().index()!=work.key.region()){work.done.set(i);continue;}}
                    BlockPos pos=new BlockPos(at.x(),at.y(),at.z());var chunk=context.chunk(pos);
                    int index=context.baseIndex+(i&15)+((i>>>4)&15)*context.strideZ+(i>>>8)*context.strideY;
                    var resolver=states.get(work.key.region());int id=p.blocks().get(index);budget--;
                    if(resolver.unresolvedState(id)){work.done.set(i);processed++;skippedUnknown++;continue;}
                    var expected=resolver.resolve(id);var actual=chunk.getBlockState(pos);
                    if(rule.permits(expected.isAir(),actual.isAir())&&!expected.isOf(net.minecraft.block.Blocks.STRUCTURE_VOID)){
                        // Validate detached payload before replacing a block or clearing its inventory.
                        NbtCompound tag=null;
                        if(nbt&&p.blockEntities().containsKey(index)){
                            tag=(NbtCompound)NbtBridge.game(p.blockEntities().get(index));
                            try{BlockEntityNbtTransform.placed(tag,transform);}
                            catch(BlockEntityNbtTransform.UnknownSourceState unknown){work.done.set(i);processed++;skippedUnknown++;continue;}
                        }
                        if(p.tickCells().contains(index))accepted.get(work.key.region()).add(index);
                        if(!expected.equals(actual)){
                            var saved=replacementInventory(actual,expected,world.getBlockEntity(pos));boolean placed;
                            try{placed=PasteBlockUpdates.set(world,pos,expected,Block.NOTIFY_LISTENERS|Block.FORCE_STATE|Block.SKIP_DROPS);}
                            catch(RuntimeException failure){if(world.getBlockState(pos).equals(actual))CreativeFillTask.restoreInventory(world.getBlockEntity(pos),saved);throw failure;}
                            if(!placed){if(world.getBlockState(pos).equals(actual))CreativeFillTask.restoreInventory(world.getBlockEntity(pos),saved);throw new IllegalStateException("粘贴方块被拒绝："+pos);}
                            changed++;
                        }
                        if(tag!=null){
                            var be=world.getBlockEntity(pos);if(be!=null){tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());be.readNbt(tag);
                                // BlockEntity.markDirty also probes comparator neighbors and can synchronously load far chunks.
                                // Bulk writes retain source states; persist the owned chunk and send the block-entity update directly.
                                chunk.setNeedsSaving(true);world.updateListeners(pos,expected,expected,Block.NOTIFY_LISTENERS);}
                        }
                    }
                    work.done.set(i);processed++;
                }
                if(work.finished()){chunks.release(context.bounds);sections.remove(work.key);}
                else {if(work.cursor>=4096)work.cursor=0;queue.resume(work);}
            }
            if(cancelled){cancelled();return;}
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
            if(queue.finished()&&extrasRemaining==0){status="粘贴完成，修改 "+changed+" 格"+skippedStatus();result.complete(status);}
            else status="粘贴 "+(100L*queue.completed()/Math.max(1,queue.total()))+"% · 修改 "+changed+skippedStatus()+(waiting?" · 准备区块":"");
        }catch(Exception e){status="粘贴失败："+e.getMessage();result.completeExceptionally(e);}
    }
    private String skippedStatus(){return skippedUnknown==0?"":" · 跳过未知 "+skippedUnknown+" 格";}
    static NbtCompound replacementInventory(net.minecraft.block.BlockState actual,net.minecraft.block.BlockState expected,net.minecraft.block.entity.BlockEntity entity){
        // Property-only edits retain the existing container; changing its block type can scatter items.
        return actual.getBlock()==expected.getBlock()?null:CreativeFillTask.emptyInventory(entity);
    }
    private int admit(SectionKey key){
        var part=document.parts().get(key.region());if(!layout.enabled(key.region())||rule!=ReplaceRule.ALL&&part.nonAir().sectionEmpty(key))return 0;
        var r=part.region();var origin=r.sectionOrigin(key);var transform=layout.part(key.region()).transform();
        var size=new Vec3i(Math.min(16,r.size().x()-key.x()*16),Math.min(16,r.size().y()-key.y()*16),Math.min(16,r.size().z()-key.z()*16));
        var bounds=PlacementBounds.clipped(new Region("section",origin,size),transform,layer);if(bounds==null)return 0;
        if(!chunks.retain(bounds))return -2;
        sections.put(key,new SectionWork(part,key,bounds,transform,layout.overlapping(bounds)));return 1;
    }
    private boolean extraLoaded(BlockPos pos){var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());var bounds=new PlacementBounds(at,at);if(!chunks.retain(bounds))return false;try{return chunks.ready(bounds)!=null;}finally{chunks.release(bounds);}}
    private boolean schedule(Map<String,Object> tag,Region region,boolean fluid,int partIndex)throws java.io.IOException{
        Vec3i local=SchematicDocument.vector(tag);if(local.x()<0||local.y()<0||local.z()<0||local.x()>=region.size().x()||local.y()>=region.size().y()||local.z()>=region.size().z())return true;
        if(!accepted.get(partIndex).contains(local.x()+local.z()*region.size().x()+local.y()*region.size().x()*region.size().z()))return true;
        Vec3i at=layout.part(partIndex).transform().apply(region.min().add(local));if(!layer.contains(at))return true;BlockPos pos=new BlockPos(at.x(),at.y(),at.z());if(!extraLoaded(pos))return false;
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
    private boolean loadedEntity(NbtCompound tag){var pos=tag.getList("Pos",6);if(pos.size()!=3||!extraLoaded(new BlockPos(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2))))return false;if(tag.contains("TileX")&&!extraLoaded(new BlockPos(tag.getInt("TileX"),tag.getInt("TileY"),tag.getInt("TileZ"))))return false;var passengers=tag.getList("Passengers",10);for(int i=0;i<passengers.size();i++)if(!loadedEntity(passengers.getCompound(i)))return false;return true;}
}
