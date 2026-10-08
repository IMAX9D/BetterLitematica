package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.SchematicDocument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.util.*;

/** Detached NBT and frozen block registries only. Never receives an active world or client. */
final class CommandPlans {
    record EntityPlan(String line,List<Vec3i> required){EntityPlan{required=List.copyOf(required);}}
    record Prepared(Map<Long,List<String>> blocks,List<List<EntityPlan>> entities,Set<String> commands,Set<Long> skippedBlocks,long skippedEntities){}
    private long bytes;private final Set<String> commands=new HashSet<>();
    static long key(int part,int cell){return ((long)part<<32)|(cell&0xffffffffL);}
    static String storage(String session,int part,int cell){return session+"_"+Long.toUnsignedString(key(part,cell),36);}
    static Prepared prepare(SchematicDocument document,PlacementLayout layout,PlacementLayout.Source cells,LayerRange layer,ReplaceRule rule,boolean nbt,boolean entities,List<List<String>> states,int limit,String storage)throws IOException{
        var builder=new CommandPlans();var blocks=new HashMap<Long,List<String>>();var plans=new ArrayList<List<EntityPlan>>();var skippedBlocks=new HashSet<Long>();long skippedEntities=0;
        for(int part=0;part<document.parts().size();part++){
            Cancellation.THREAD.check();var p=document.parts().get(part);var transform=layout.part(part).transform();
            if(nbt&&layout.enabled(part))for(var entry:p.blockEntities().entrySet()){
                Cancellation.THREAD.check();int index=entry.getKey();var size=p.region().size();var at=transform.apply(p.region().min().add(new Vec3i(index%size.x(),index/(size.x()*size.z()),index/size.x()%size.z())));if(!layer.contains(at))continue;var owner=layout.sample(at,cells);if(owner==null||owner.part().index()!=part)continue;
                String state=states.get(part).get(p.blocks().get(index));if(state==null)continue;
                var tag=(CompoundTag)NbtBridge.game(entry.getValue());
                try{BlockEntityNbtTransform.placed(tag,transform);}
                catch(BlockEntityNbtTransform.UnknownSourceState unknown){builder.reserve(64);skippedBlocks.add(key(part,index));continue;}
                tag.remove("x");tag.remove("y");tag.remove("z");Cancellation.THREAD.check();var lines=CommandNbt.block(at,state,tag,rule,limit,storage(storage,part,index));for(String line:lines)builder.admit(line);blocks.put(key(part,index),lines);
            }
            var entityPlans=new ArrayList<EntityPlan>();if(entities&&layout.enabled(part))for(var data:p.entities()){
                Cancellation.THREAD.check();var tag=(CompoundTag)NbtBridge.game(data);EntityNbtTransform.placed(tag,p.region().min(),transform);var required=new ArrayList<Vec3i>();locations(tag,required,0);if(required.isEmpty())throw new IllegalArgumentException("实体缺少坐标");if(!layer.contains(required.get(0))){entityPlans.add(null);continue;}
                if(!knownEntities(tag,0)){builder.reserve(8);entityPlans.add(null);skippedEntities++;continue;}
                var position=NbtAccess.list(tag,"Pos",Tag.TAG_DOUBLE);String id=tag.getStringOr("id","");tag.remove("id");tag.remove("Pos");String line="summon "+id+" "+position.getDoubleOr(0,0d)+" "+position.getDoubleOr(1,0d)+" "+position.getDoubleOr(2,0d)+" "+tag;if(line.length()>limit)throw new IllegalArgumentException("实体数据超过命令长度，请导出 mcfunction");builder.admit(line);builder.reserve(required.size()*32L);entityPlans.add(new EntityPlan(line,required));
            }
            plans.add(Collections.unmodifiableList(entityPlans));
        }
        Cancellation.THREAD.check();return new Prepared(Map.copyOf(blocks),List.copyOf(plans),Set.copyOf(builder.commands),Set.copyOf(skippedBlocks),skippedEntities);
    }
    private void reserve(long count){bytes+=count;if(bytes>64L*1024*1024)throw new IllegalArgumentException("命令数据超过 64 MiB 准备上限");}
    private void admit(String line){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();reserve(64L+line.length()*2L);String command=line.substring(0,line.indexOf(' '));commands.add(command);if(command.equals("execute"))commands.add("data");}
    private static boolean knownEntities(CompoundTag tag,int depth){
        if(depth>32)throw new IllegalArgumentException("实体结构超过命令预算");
        var id=net.minecraft.resources.Identifier.tryParse(tag.getStringOr("id",""));if(id==null)throw new IllegalArgumentException("实体 ID 无效");
        boolean known=BuiltInRegistries.ENTITY_TYPE.containsKey(id);
        for(var child:NbtAccess.list(tag,"Passengers",Tag.TAG_COMPOUND))known=knownEntities((CompoundTag)child,depth+1)&&known;
        return known;
    }
    private static void locations(CompoundTag tag,List<Vec3i> positions,int depth){if(depth>32||positions.size()>128)throw new IllegalArgumentException("实体结构超过命令预算");var p=NbtAccess.list(tag,"Pos",Tag.TAG_DOUBLE);if(p.size()!=3||!Double.isFinite(p.getDoubleOr(0,0d))||!Double.isFinite(p.getDoubleOr(1,0d))||!Double.isFinite(p.getDoubleOr(2,0d)))throw new IllegalArgumentException("实体坐标无效");positions.add(new Vec3i((int)Math.floor(p.getDoubleOr(0,0d)),(int)Math.floor(p.getDoubleOr(1,0d)),(int)Math.floor(p.getDoubleOr(2,0d))));if(tag.contains("TileX"))positions.add(new Vec3i(tag.getIntOr("TileX",0),tag.getIntOr("TileY",0),tag.getIntOr("TileZ",0)));for(var child:NbtAccess.list(tag,"Passengers",Tag.TAG_COMPOUND))locations((CompoundTag)child,positions,depth+1);}
}
