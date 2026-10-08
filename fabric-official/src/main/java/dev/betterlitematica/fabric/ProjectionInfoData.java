package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Client-owned immutable snapshots. No world writes, requests, loot generation or render-entity data. */
final class ProjectionInfoData {
    record Snapshot(Vec3i position,String placement,String region,Side projection,Side actual) {}
    record Side(BlockState state,String availability,List<String> nbt,List<ItemStack> items,int columns) {
        Side {nbt=List.copyOf(nbt);items=List.copyOf(items);}
    }
    record BlockData(BlockState state,CompoundTag tag,String error) {}
    private record ActualKey(MinecraftServer server,ResourceKey<Level> dimension,BlockPos pos) {}
    private record ActualResult(BlockState state,Side side) {}
    private static final int MAX_SLOTS=256,MAX_BYTES=131072,MAX_NODES=8192;
    private static ActualKey actualKey;
    private static CompletableFuture<ActualResult> actualRead;
    private static long requestedAt;
    private ProjectionInfoData() {}

    static Snapshot capture(Minecraft client,ProjectionController controller,ProjectionController.Target target) {
        if(target==null)return null;
        var pos=target.position();var bp=new BlockPos(pos.x(),pos.y(),pos.z());
        String placement="",region="";Side projection;
        var owner=controller.informationCell(pos);
        if(owner==null||owner.unknown())projection=unavailable(target.state(),"投影数据未加载");
        else {
            placement=owner.renderer().layout().placement().name();region=owner.region().region().name();
            var data=projectionBlock(owner,pos);
            projection=side(data,at->{var cell=controller.informationCell(vector(at));return cell==null||cell.unknown()?new BlockData(null,null,"投影数据未加载"):projectionBlock(cell,vector(at));},bp);
        }
        Side actual;
        if(client.level==null||!WorldChunks.loaded(client.level,bp))actual=unavailable(null,"未加载");
        else {
            var state=client.level.getBlockState(bp);
            var server=client.getSingleplayerServer();
            if(!state.hasBlockEntity())actual=new Side(state,"",List.of(),List.of(),9);
            else if(server!=null)actual=serverSide(server,client.level.dimension(),bp,state);
            else {
                try {
                    var be=client.level.getBlockEntity(bp);
                    var tag=be==null?null:be.saveWithFullMetadata(client.level.registryAccess());
                    if(tag!=null)check(tag,new Limit(),0);
                    var summary=summary(tag);
                    boolean inventory=slotCount(state,bp)>0;
                    // Vanilla chunk and block-entity packets do not synchronize container inventories.
                    actual=new Side(state,inventory?"库存未同步":state.hasBlockEntity()?"部分 NBT（客户端）":"",summary,List.of(),9);
                }catch(RuntimeException e){actual=unavailable(state,"数据读取失败");}
            }
        }
        return new Snapshot(pos,placement,region,projection,actual);
    }
    static void clear(){actualKey=null;if(actualRead!=null)actualRead.cancel(false);actualRead=null;requestedAt=0;}
    private static Side serverSide(MinecraftServer server,ResourceKey<Level> dimension,BlockPos pos,BlockState clientState){
        var wanted=new ActualKey(server,dimension,pos.immutable());long now=System.nanoTime();
        boolean same=wanted.equals(actualKey);
        ActualResult ready=null;
        if(same&&actualRead!=null&&actualRead.isDone()&&!actualRead.isCompletedExceptionally()&&!actualRead.isCancelled())ready=actualRead.getNow(null);
        if(actualRead==null||actualRead.isDone()&&(!same||now-requestedAt>=500_000_000L)){
            var result=new CompletableFuture<ActualResult>();actualRead=result;actualKey=wanted;requestedAt=now;
            try {server.execute(()->{
                if(result.isCancelled())return;
                try {
                    var world=server.getLevel(dimension);
                    if(world==null||!WorldChunks.loaded(world,pos)){result.complete(new ActualResult(null,unavailable(null,"未加载")));return;}
                    var data=actualBlock(world,pos);
                    result.complete(new ActualResult(data.state(),side(data,at->actualBlock(world,at),pos,world.registryAccess())));
                }catch(RuntimeException e){result.complete(new ActualResult(null,unavailable(null,"数据读取失败")));}
            });}catch(RuntimeException e){result.complete(new ActualResult(null,unavailable(null,"数据读取失败")));}
        }
        if(ready==null)return unavailable(clientState,"读取中");
        if(ready.state()==null)return unavailable(clientState,ready.side().availability());
        if(ready.state()!=clientState)return unavailable(clientState,"数据更新中");
        return ready.side();
    }
    private static BlockData actualBlock(Level world,BlockPos pos){
        if(!WorldChunks.loaded(world,pos))return new BlockData(null,null,"未加载");
        var state=world.getBlockState(pos);
        try {
            var be=world.getBlockEntity(pos);var tag=be==null?null:be.saveWithFullMetadata(world.registryAccess());
            if(tag!=null)check(tag,new Limit(),0);
            return new BlockData(state,tag,state.hasBlockEntity()&&be==null?"方块数据缺失":"");
        }catch(RuntimeException e){return new BlockData(state,null,"数据超过显示预算或读取失败");}
    }
    static BlockData projectionBlock(ProjectionScene.Cell cell,Vec3i pos){
        var owner=cell.renderer();var state=owner.resolve(cell.region().index(),cell.id());
        if(owner.resolver(cell.region().index()).unresolved(cell.id()))return new BlockData(null,null,"未知方块");
        if(!state.hasBlockEntity())return new BlockData(state,null,"");
        try {
            var raw=owner.informationBlockData(cell.region(),cell.local());
            if(raw==null)return sourceData(state,null,new BlockPos(pos.x(),pos.y(),pos.z()));
            check(raw,new Limit(),0);
            var tag=(CompoundTag)NbtBridge.game(raw);
            BlockEntityNbtTransform.placed(tag,cell.region().transform());
            tag.putInt("x",pos.x());tag.putInt("y",pos.y());tag.putInt("z",pos.z());
            return sourceData(state,tag,new BlockPos(pos.x(),pos.y(),pos.z()));
        }catch(RuntimeException e){return new BlockData(state,null,"附加数据未加载、读取失败或超过预算");}
    }
    static BlockData sourceData(BlockState state,CompoundTag tag,BlockPos pos){
        return new BlockData(state,tag,tag==null&&slotCount(state,pos)>0?"投影未包含容器数据":"");
    }
    static Side side(BlockData data,java.util.function.Function<BlockPos,BlockData> reader,BlockPos pos){
        return side(data,reader,pos,ItemDataBridge.registries());
    }
    private static Side side(BlockData data,java.util.function.Function<BlockPos,BlockData> reader,BlockPos pos,net.minecraft.core.HolderLookup.Provider registries){
        if(data.state()==null||!data.error().isEmpty())return unavailable(data.state(),data.error());
        try {
            var state=data.state();var tag=data.tag();var lines=summary(tag);
            var slots=items(state,tag,pos,registries);
            if(tag!=null&&tag.contains("LootTable"))return new Side(state,"战利品未生成",lines,List.of(),9);
            if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
                var neighborPos=pos.relative(ChestBlock.getConnectedDirection(state));var other=reader.apply(neighborPos);
                if(other.state()==null||!other.error().isEmpty())return new Side(state,"另一半箱子数据不可用",lines,List.of(),9);
                if(connected(state,other.state())){
                    if(other.tag()!=null&&other.tag().contains("LootTable"))return new Side(state,"另一半箱子战利品未生成",lines,List.of(),9);
                    var otherItems=items(other.state(),other.tag(),neighborPos,registries);
                    var merged=new ArrayList<ItemStack>(54);
                    // Vanilla DoubleBlockProperties marks RIGHT as FIRST.
                    if(state.getValue(ChestBlock.TYPE)==ChestType.RIGHT){merged.addAll(slots);merged.addAll(otherItems);}
                    else {merged.addAll(otherItems);merged.addAll(slots);}
                    slots=List.copyOf(merged);
                    var withOther=new ArrayList<String>(lines);withOther.add("另一半箱子");withOther.addAll(summary(other.tag()));
                    if(withOther.size()>128){withOther.subList(127,withOther.size()).clear();withOther.add("…");}lines=List.copyOf(withOther);
                }
            }
            return new Side(state,"",lines,slots,columns(state,slots.size()));
        }catch(RuntimeException e){return new Side(data.state(),"容器数据超过显示预算或读取失败",summary(data.tag()),List.of(),9);}
    }
    static boolean connected(BlockState one,BlockState other){
        return one.getBlock() instanceof ChestBlock&&one.getBlock()==other.getBlock()
            &&one.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
            &&other.getValue(ChestBlock.TYPE)==one.getValue(ChestBlock.TYPE).getOpposite()
            &&one.getValue(ChestBlock.FACING)==other.getValue(ChestBlock.FACING);
    }
    static List<ItemStack> items(BlockState state,CompoundTag tag,BlockPos pos){
        return items(state,tag,pos,ItemDataBridge.registries());
    }
    private static List<ItemStack> items(BlockState state,CompoundTag tag,BlockPos pos,net.minecraft.core.HolderLookup.Provider registries){
        int size=slotCount(state,pos);
        if(size==0)return List.of();
        if(size>MAX_SLOTS)throw new IllegalArgumentException("slots");
        var result=new ArrayList<ItemStack>(Collections.nCopies(size,ItemStack.EMPTY));
        if(tag!=null){
            check(tag,new Limit(),0);
            if(NbtAccess.contains(tag,"Items",Tag.TAG_LIST)){
                var items=NbtAccess.list(tag,"Items",Tag.TAG_COMPOUND);
                if(items.size()>MAX_SLOTS)throw new IllegalArgumentException("entries");
                for(var element:items){var item=(CompoundTag)element;int slot=item.getByteOr("Slot",(byte)0)&255;if(slot<size)result.set(slot,readItem(item,registries));}
            }
            if(state.getBlock() instanceof JukeboxBlock&&NbtAccess.contains(tag,"RecordItem",Tag.TAG_COMPOUND))result.set(0,readItem(tag.getCompoundOrEmpty("RecordItem"),registries));
            if(state.getBlock() instanceof LecternBlock&&NbtAccess.contains(tag,"Book",Tag.TAG_COMPOUND))result.set(0,readItem(tag.getCompoundOrEmpty("Book"),registries));
        }
        return List.copyOf(result);
    }
    private static ItemStack readItem(CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries){
        var id=net.minecraft.resources.Identifier.tryParse(tag.getStringOr("id",""));
        if(id==null||!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("Unknown item");
        return ItemDataBridge.read(tag.copy(),registries);
    }
    private static int slotCount(BlockState state,BlockPos pos){
        if(state.getBlock() instanceof JukeboxBlock||state.getBlock() instanceof LecternBlock)return 1;
        if(state.getBlock() instanceof CampfireBlock)return 4;
        if(state.getBlock() instanceof EntityBlock provider){var entity=provider.newBlockEntity(pos,state);if(entity instanceof Container inventory)return inventory.getContainerSize();}
        return 0;
    }
    private static int columns(BlockState state,int size){return size==5?5:size==3?3:size==6?3:size==4?4:size<9?Math.max(1,size):9;}
    private static Side unavailable(BlockState state,String text){return new Side(state,text,List.of(),List.of(),9);}
    private static Vec3i vector(BlockPos pos){return new Vec3i(pos.getX(),pos.getY(),pos.getZ());}
    static List<String> summary(CompoundTag tag){
        if(tag==null||tag.isEmpty())return List.of();var lines=new ArrayList<String>();
        summarize(tag,"",0,lines,new int[]{512});return List.copyOf(lines);
    }
    private static void summarize(Tag value,String path,int depth,List<String> lines,int[] budget){
        if(lines.size()>=128||budget[0]--<=0)return;
        if(value instanceof CompoundTag compound&&depth<6){
            if(compound.keySet().size()>MAX_NODES){lines.add(shorten(path,160)+" = …");return;}
            var keys=new ArrayList<String>(compound.keySet());Collections.sort(keys);
            for(String key:keys){if(lines.size()>=127||budget[0]<=0){lines.add("…");break;}
                if(key.equals("Items")){var list=NbtAccess.list(compound,key,Tag.TAG_COMPOUND);lines.add((path.isEmpty()?key:path+"."+key)+" = "+list.size()+" 项");}
                else summarize(compound.get(key),path.isEmpty()?key:path+"."+key,depth+1,lines,budget);
            }
        }else if(value instanceof ListTag list&&depth<6){
            if(list.isEmpty())lines.add(path+" = []");
            for(int i=0;i<list.size();i++){if(lines.size()>=127||budget[0]<=0){lines.add("…");break;}summarize(list.get(i),path+"["+i+"]",depth+1,lines,budget);}
        }else {
            String text;
            if(value instanceof ByteArrayTag bytes)text="byte["+bytes.getAsByteArray().length+"]";
            else if(value instanceof IntArrayTag ints)text="int["+ints.getAsIntArray().length+"]";
            else if(value instanceof LongArrayTag longs)text="long["+longs.getAsLongArray().length+"]";
            else if(value instanceof CompoundTag||value instanceof ListTag)text="…";
            else text=value==null?"":value.toString();
            lines.add(shorten(path,160)+" = "+shorten(text,240));
        }
    }
    private static String shorten(String value,int max){return value.length()>max?value.substring(0,max)+"…":value;}
    private static final class Limit {int nodes,bytes;void take(int amount,int depth){if(depth>16||++nodes>MAX_NODES||amount<0||(bytes+=amount)>MAX_BYTES)throw new IllegalArgumentException("NBT display budget");}}
    private static void check(Object value,Limit limit,int depth){
        limit.take(32,depth);
        if(value instanceof Map<?,?> map){if(map.size()>MAX_NODES)throw new IllegalArgumentException("keys");for(var entry:map.entrySet()){limit.take(entry.getKey().toString().length()*2,depth);check(entry.getValue(),limit,depth+1);}}
        else if(value instanceof List<?> list){if(list.size()>MAX_NODES)throw new IllegalArgumentException("list");for(var item:list)check(item,limit,depth+1);}
        else if(value instanceof CompoundTag compound){for(String key:compound.keySet()){limit.take(key.length()*2,depth);check(compound.get(key),limit,depth+1);}}
        else if(value instanceof ListTag list){if(list.size()>MAX_NODES)throw new IllegalArgumentException("list");for(var item:list)check(item,limit,depth+1);}
        else if(value instanceof String text)limit.take(text.length()*2,depth);
        else if(value instanceof StringTag text)limit.take(text.value().length()*2,depth);
        else if(value instanceof byte[] bytes)limit.take(bytes.length,depth);
        else if(value instanceof int[] ints)limit.take(Math.multiplyExact(ints.length,4),depth);
        else if(value instanceof long[] longs)limit.take(Math.multiplyExact(longs.length,8),depth);
        else if(value instanceof ByteArrayTag bytes)limit.take(bytes.getAsByteArray().length,depth);
        else if(value instanceof IntArrayTag ints)limit.take(Math.multiplyExact(ints.getAsIntArray().length,4),depth);
        else if(value instanceof LongArrayTag longs)limit.take(Math.multiplyExact(longs.getAsLongArray().length,8),depth);
    }
}
