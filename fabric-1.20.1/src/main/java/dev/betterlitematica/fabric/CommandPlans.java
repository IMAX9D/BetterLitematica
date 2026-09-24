package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.SchematicDocument;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import java.io.IOException;
import java.util.*;

/** Detached NBT and frozen block registries only. Never receives an active world or client. */
final class CommandPlans {
    record EntityPlan(String line,List<Vec3i> required){EntityPlan{required=List.copyOf(required);}}
    record Prepared(Map<Long,List<String>> blocks,List<List<EntityPlan>> entities,Set<String> commands){}
    private long bytes;private final Set<String> commands=new HashSet<>();
    static long key(int part,int cell){return ((long)part<<32)|(cell&0xffffffffL);}
    static String storage(String session,int part,int cell){return session+"_"+Long.toUnsignedString(key(part,cell),36);}
    static Prepared prepare(SchematicDocument document,PlacementLayout layout,PlacementLayout.Source cells,LayerRange layer,ReplaceRule rule,boolean nbt,boolean entities,List<List<String>> states,int limit,String storage)throws IOException{
        var builder=new CommandPlans();var blocks=new HashMap<Long,List<String>>();var plans=new ArrayList<List<EntityPlan>>();
        for(int part=0;part<document.parts().size();part++){
            Cancellation.THREAD.check();var p=document.parts().get(part);var transform=layout.part(part).transform();
            if(nbt&&layout.enabled(part))for(var entry:p.blockEntities().entrySet()){
                Cancellation.THREAD.check();int index=entry.getKey();var size=p.region().size();var at=transform.apply(p.region().min().add(new Vec3i(index%size.x(),index/(size.x()*size.z()),index/size.x()%size.z())));if(!layer.contains(at))continue;var owner=layout.sample(at,cells);if(owner==null||owner.part().index()!=part)continue;
                var tag=(NbtCompound)NbtBridge.game(entry.getValue());BlockEntityNbtTransform.placed(tag,transform);tag.remove("x");tag.remove("y");tag.remove("z");Cancellation.THREAD.check();var lines=CommandNbt.block(at,states.get(part).get(p.blocks().get(index)),tag,rule,limit,storage(storage,part,index));for(String line:lines)builder.admit(line);blocks.put(key(part,index),lines);
            }
            var entityPlans=new ArrayList<EntityPlan>();if(entities&&layout.enabled(part))for(var data:p.entities()){
                Cancellation.THREAD.check();var tag=(NbtCompound)NbtBridge.game(data);EntityNbtTransform.placed(tag,p.region().min(),transform);var required=new ArrayList<Vec3i>();locations(tag,required,0);if(required.isEmpty())throw new IllegalArgumentException("实体缺少坐标");if(!layer.contains(required.get(0))){entityPlans.add(null);continue;}
                var position=tag.getList("Pos",NbtElement.DOUBLE_TYPE);String id=tag.getString("id");var type=net.minecraft.util.Identifier.tryParse(id);if(type==null||!Registries.ENTITY_TYPE.containsId(type))throw new IllegalArgumentException("投影包含无法识别的实体");tag.remove("id");tag.remove("Pos");String line="summon "+id+" "+position.getDouble(0)+" "+position.getDouble(1)+" "+position.getDouble(2)+" "+tag;if(line.length()>limit)throw new IllegalArgumentException("实体数据超过命令长度，请导出 mcfunction");builder.admit(line);builder.bytes+=required.size()*32L;entityPlans.add(new EntityPlan(line,required));
            }
            plans.add(Collections.unmodifiableList(entityPlans));
        }
        Cancellation.THREAD.check();return new Prepared(Map.copyOf(blocks),List.copyOf(plans),Set.copyOf(builder.commands));
    }
    private void admit(String line){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();bytes+=64L+line.length()*2L;if(bytes>64L*1024*1024)throw new IllegalArgumentException("命令数据超过 64 MiB 准备上限");String command=line.substring(0,line.indexOf(' '));commands.add(command);if(command.equals("execute"))commands.add("data");}
    private static void locations(NbtCompound tag,List<Vec3i> positions,int depth){if(depth>32||positions.size()>128)throw new IllegalArgumentException("实体结构超过命令预算");var p=tag.getList("Pos",NbtElement.DOUBLE_TYPE);if(p.size()!=3||!Double.isFinite(p.getDouble(0))||!Double.isFinite(p.getDouble(1))||!Double.isFinite(p.getDouble(2)))throw new IllegalArgumentException("实体坐标无效");positions.add(new Vec3i((int)Math.floor(p.getDouble(0)),(int)Math.floor(p.getDouble(1)),(int)Math.floor(p.getDouble(2))));if(tag.contains("TileX"))positions.add(new Vec3i(tag.getInt("TileX"),tag.getInt("TileY"),tag.getInt("TileZ")));for(var child:tag.getList("Passengers",NbtElement.COMPOUND_TYPE))locations((NbtCompound)child,positions,depth+1);}
}
