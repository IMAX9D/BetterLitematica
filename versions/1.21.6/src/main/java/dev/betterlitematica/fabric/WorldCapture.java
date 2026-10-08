package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.LitematicExport;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.tick.OrderedTick;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owning-world-thread capture. Unavailable chunks remain pending; workers only receive detached data. */
final class WorldCapture {
    private static final class Part {
        final Region region;final int[] blocks;final LinkedHashMap<BlockStateSpec,Integer> palette=new LinkedHashMap<>();
        final List<Map<String,Object>> blockEntities=new ArrayList<>(),entities=new ArrayList<>(),blockTicks=new ArrayList<>(),fluidTicks=new ArrayList<>();
        final BitSet extrasDone=new BitSet();final int firstX,firstZ,chunksX,chunksZ;int extraCursor;
        Part(Region region){this.region=region;blocks=new int[Math.toIntExact(region.volume())];palette.put(BlockStateSpec.AIR,0);firstX=Math.floorDiv(region.min().x(),16);firstZ=Math.floorDiv(region.min().z(),16);chunksX=Math.floorDiv(region.min().x()+region.size().x()-1,16)-firstX+1;chunksZ=Math.floorDiv(region.min().z()+region.size().z()-1,16)-firstZ+1;}
        int chunks(){return Math.multiplyExact(chunksX,chunksZ);}
        LitematicExport.Capture capture(){return new LitematicExport.Capture(region,new ArrayList<>(palette.keySet()),blocks,blockEntities,entities,blockTicks,fluidTicks);}
    }
    private final World world;private final List<Part> parts;private final DeferredSections queue;private final long volume;
    private final CompletableFuture<List<LitematicExport.Capture>> result=new CompletableFuture<>();
    private final NbtBridge.Budget nbtBudget=new NbtBridge.Budget(64L*1024*1024);
    private final IdentityHashMap<BlockState,BlockStateSpec> specs=new IdentityHashMap<>();
    private final CapturedEntities capturedEntities=new CapturedEntities();
    private record TickIdentity(boolean fluid,BlockPos pos,String id){}
    private final Set<TickIdentity> capturedTicks=new HashSet<>();
    private static final class Extras {
        final Part part;final int index,chunkX,chunkZ;final Box box;final List<net.minecraft.entity.Entity> entities=new ArrayList<>();
        List<OrderedTick<?>> scheduled=List.of();int stage,cursor;
        Extras(Part part,int index){this.part=part;this.index=index;chunkX=part.firstX+index%part.chunksX;chunkZ=part.firstZ+index/part.chunksX;var region=part.region;box=new Box(Math.max(region.min().x(),chunkX*16),region.min().y(),Math.max(region.min().z(),chunkZ*16),Math.min(region.min().x()+region.size().x(),chunkX*16+16),region.min().y()+region.size().y(),Math.min(region.min().z()+region.size().z(),chunkZ*16+16));}
    }
    private Extras extras;
    private long ticks,processed;private int extraPart,extrasRemaining,paletteEntries,blockEntityCount;
    private volatile boolean cancelled;private volatile String status="准备捕获";
    WorldCapture(World world,List<SelectionBox> selection){
        this.world=world;var regions=selection.stream().map(SelectionBox::region).toList();volume=regions.stream().mapToLong(Region::volume).sum();
        if(regions.isEmpty()||regions.size()>128||volume>4_194_304)throw new IllegalArgumentException("选区捕获上限为 128 区域 / 4194304 格");
        for(int i=0;i<regions.size();i++)for(int j=0;j<i;j++)if(regions.get(i).intersects(regions.get(j)))throw new IllegalArgumentException("捕获选区不能重叠");
        for(var region:regions)if(region.min().y()<world.getBottomY()||(long)region.min().y()+region.size().y()>(world.getTopYInclusive()+1))throw new IllegalArgumentException("选区超出世界高度");
        parts=regions.stream().map(Part::new).toList();queue=new DeferredSections(regions);for(var part:parts)extrasRemaining=Math.addExact(extrasRemaining,part.chunks());paletteEntries=parts.size();
    }
    World world(){return world;}CompletableFuture<List<LitematicExport.Capture>> result(){return result;}String status(){return status;}void cancel(){cancelled=true;}
    private boolean available(SectionKey key){var part=parts.get(key.region());var region=part.region;var base=region.sectionOrigin(key);for(int x:new int[]{0,Math.min(15,region.size().x()-key.x()*16-1)})for(int z:new int[]{0,Math.min(15,region.size().z()-key.z()*16-1)})if(WorldChunks.loaded(world,(base.x()+x)>>4,(base.z()+z)>>4))return true;return false;}
    private BlockStateSpec spec(BlockState state){var known=specs.get(state);if(known!=null)return known;if(specs.size()>=65536)throw new IllegalStateException("捕获方块状态过多");Map<String,String> properties=new TreeMap<>();state.getEntries().forEach((p,v)->properties.put(p.getName(),v.toString().toLowerCase(Locale.ROOT)));var value=state.isAir()?BlockStateSpec.AIR:new BlockStateSpec(Registries.BLOCK.getId(state.getBlock()).toString(),properties);specs.put(state,value);return value;}
    void tick(){
        if(result.isDone())return;if(cancelled){status="已取消捕获";result.cancel(false);return;}ticks++;
        try{
            long deadline=System.nanoTime()+2_000_000L;int budget=512;
            queue.discover(256,key->available(key)?1:-1,deadline);int turns=Math.min(queue.pending(),32);
            while(turns-->0&&budget>0&&System.nanoTime()<deadline){
                var work=queue.poll(ticks);if(work==null)break;var part=parts.get(work.key.region());var region=part.region;var base=region.sectionOrigin(work.key);
                if(!available(work.key)){work.retryAt=ticks+20;queue.unavailable(work);continue;}
                while(work.cursor<4096&&budget>0&&System.nanoTime()<deadline){
                    int cell=work.cursor++;if(work.done.get(cell))continue;var at=base.add(new Vec3i(cell&15,cell>>>8,(cell>>>4)&15));if(!region.contains(at)){work.done.set(cell);continue;}var pos=new BlockPos(at.x(),at.y(),at.z());if(!WorldChunks.loaded(world,pos))continue;budget--;
                    var state=spec(world.getBlockState(pos));Integer id=part.palette.get(state);if(id==null){if(part.palette.size()>=65536||paletteEntries>=262144)throw new IllegalStateException("捕获调色板过大");id=part.palette.size();part.palette.put(state,id);paletteEntries++;}
                    var offset=at.subtract(region.min());part.blocks[offset.x()+offset.z()*region.size().x()+offset.y()*region.size().x()*region.size().z()]=id;
                    var entity=world.getBlockEntity(pos);if(entity!=null){if(++blockEntityCount>65536)throw new IllegalStateException("方块实体超过捕获预算");var tag=NbtBridge.compound(entity.createNbtWithIdentifyingData(world.getRegistryManager()),nbtBudget);tag.put("x",offset.x());tag.put("y",offset.y());tag.put("z",offset.z());part.blockEntities.add(tag);}
                    work.done.set(cell);processed++;
                }
                if(work.cursor==4096&&!work.finished()){work.cursor=0;work.retryAt=ticks+20;}
                if(work.retryAt<=ticks)queue.resume(work);else queue.defer(work);
            }
            // Cycle across parts and chunks. One missing chunk cannot block all other extras.
            if(queue.finished()){
                if(extras!=null&&!WorldChunks.loaded(world,extras.chunkX,extras.chunkZ))extras=null;
                int attempts=256;while(extras==null&&extrasRemaining>0&&attempts-->0&&System.nanoTime()<deadline){var part=parts.get(extraPart);extraPart=(extraPart+1)%parts.size();int index=part.extraCursor++;if(part.extraCursor>=part.chunks())part.extraCursor=0;if(part.extrasDone.get(index))continue;int chunkX=part.firstX+index%part.chunksX,chunkZ=part.firstZ+index/part.chunksX;if(WorldChunks.loaded(world,chunkX,chunkZ))extras=new Extras(part,index);}
                if(extras!=null&&System.nanoTime()<deadline)captureExtras(deadline);
            }
            if(queue.finished()&&extrasRemaining==0){status="捕获完成";result.complete(parts.stream().map(Part::capture).toList());}
            else status="捕获 "+processed+" / "+volume+(queue.finished()?" · 附加数据 "+extrasRemaining:queue.pending()==0?" · 等待区块":"");
        }catch(RuntimeException e){status="捕获失败："+e.getMessage();result.completeExceptionally(e);}
    }
    private void captureExtras(long deadline){
        var work=extras;var part=work.part;var region=part.region;
        if(work.stage==0){world.collectEntitiesByType(net.minecraft.util.TypeFilter.instanceOf(net.minecraft.entity.Entity.class),work.box,e->!(e instanceof PlayerEntity)&&!e.hasVehicle()&&!capturedEntities.contains(e.getUuid())&&work.box.contains(e.getPos()),work.entities,8193);if(work.entities.size()>8192)throw new IllegalStateException("区块实体超过捕获预算");work.stage=1;return;}
        if(work.stage==1){int budget=4;while(work.cursor<work.entities.size()&&budget-->0&&System.nanoTime()<deadline){var entity=work.entities.get(work.cursor++);if(entity.isRemoved()||entity.getWorld()!=world||entity.hasVehicle()||!work.box.contains(entity.getPos()))continue;var view=StorageData.write(world.getRegistryManager());boolean saved=entity.saveData(view);var tag=view.getNbt();if(saved&&capturedEntities.accept(tag)){EntityNbtTransform.relative(tag,region.min());part.entities.add(NbtBridge.compound(tag,nbtBudget));}}if(work.cursor<work.entities.size())return;work.entities.clear();work.cursor=0;work.stage=2;return;}
        if(work.stage==2||work.stage==4){if(world instanceof ServerWorld server){var chunk=server.getChunk(work.chunkX,work.chunkZ);work.scheduled=ticks(work.stage==2?chunk.getBlockTickScheduler():chunk.getFluidTickScheduler());}work.stage++;work.cursor=0;return;}
        boolean fluid=work.stage==5;int budget=64;
        while(work.cursor<work.scheduled.size()&&budget-->0&&System.nanoTime()<deadline){var tick=work.scheduled.get(work.cursor++);var pos=tick.pos();if(!region.contains(new Vec3i(pos.getX(),pos.getY(),pos.getZ())))continue;String id=fluid?Registries.FLUID.getId((net.minecraft.fluid.Fluid)tick.type()).toString():Registries.BLOCK.getId((net.minecraft.block.Block)tick.type()).toString();if(!capturedTicks.add(new TickIdentity(fluid,pos,id)))continue;if(capturedTicks.size()>65536)throw new IllegalStateException("计划刻超过捕获预算");(fluid?part.fluidTicks:part.blockTicks).add(tickTag(tick,region,id,fluid?"Fluid":"Block"));}
        if(work.cursor<work.scheduled.size())return;work.scheduled=List.of();work.cursor=0;if(!fluid){work.stage=4;return;}part.extrasDone.set(work.index);extrasRemaining--;extras=null;
    }
    private Map<String,Object> tickTag(OrderedTick<?> tick,Region region,String id,String type){var pos=new Vec3i(tick.pos().getX(),tick.pos().getY(),tick.pos().getZ()).subtract(region.min());var tag=new LinkedHashMap<String,Object>(LitematicExport.vector(pos));tag.put(type,id);tag.put("Time",Math.toIntExact(Math.max(0,tick.triggerTick()-world.getTime())));tag.put("Priority",tick.priority().getIndex());return tag;}
    private static List<OrderedTick<?>> ticks(net.minecraft.world.tick.BasicTickScheduler<?> scheduler){if(scheduler instanceof net.minecraft.world.tick.ChunkTickScheduler<?> chunk){if(chunk.getTickCount()>16384)throw new IllegalStateException("区块计划刻过多");return chunk.getQueueAsStream().limit(16385).map(t->(OrderedTick<?>)t).collect(java.util.stream.Collectors.toList());}throw new IllegalStateException("当前区块计划刻实现不支持捕获，未输出不完整投影");}
}
