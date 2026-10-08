package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import java.util.*;

/** Complete source histogram, independent of resident/rendered sections and the world. */
final class FileMaterials {
    enum Sort {MISSING,TOTAL,AVAILABLE,NAME;String label(){return switch(this){case MISSING->"缺少";case TOTAL->"总量";case AVAILABLE->"拥有";case NAME->"名称";};}}
    private final long[] counts;
    private final StateResolver1201 resolver;
    private final Map<Item,Long> totals=new HashMap<>();
    private int cursor;private long unsupported;
    FileMaterials(BlueprintMetadata metadata,long[] counts,PlacementTransform transform){this.counts=counts;resolver=new StateResolver1201(metadata.palette(),transform);}
    void tick(){
        long end=System.nanoTime()+2_000_000L;int budget=256;
        while(cursor<counts.length&&budget-->0&&System.nanoTime()<end){
            int id=cursor++;if(counts[id]==0)continue;var state=resolver.resolve(id);
            if(resolver.unresolved(id)){unsupported+=counts[id];continue;}
            for(var need:BuildMaterials.forState(state))totals.merge(need.item(),Math.multiplyExact(counts[id],need.count()),Math::addExact);
        }
    }
    long total(Item item){return totals.getOrDefault(item,0L);}
    long unsupported(){return unsupported;}
    boolean finished(){return cursor==counts.length;}
    String status(){return (finished()?"": "统计中 · "+cursor+"/"+counts.length)+(unsupported==0?"":(finished()?"":" · ")+"未识别方块 "+unsupported);}
    List<PlacementAnalysis.Material> materials(Minecraft client,int multiplier,boolean missingOnly,String filter){
        return materials(client,multiplier,missingOnly,filter,Sort.MISSING,true);
    }
    List<PlacementAnalysis.Material> materials(Minecraft client,int multiplier,boolean missingOnly,String filter,Sort sort,boolean descending){
        Map<Item,Long> inventory=new HashMap<>();
        if(client.player!=null)for(int i=0;i<client.player.getInventory().getContainerSize();i++){var stack=client.player.getInventory().getItem(i);if(!stack.isEmpty())inventory.merge(stack.getItem(),(long)stack.getCount(),Long::sum);}
        String query=filter.toLowerCase(Locale.ROOT);var result=new ArrayList<PlacementAnalysis.Material>();
        totals.forEach((item,count)->{long total=Math.multiplyExact(count,multiplier),available=inventory.getOrDefault(item,0L);
            if((!missingOnly||total>available)&&(item.getName(new net.minecraft.world.item.ItemStack(item)).getString().toLowerCase(Locale.ROOT).contains(query)||BuiltInRegistries.ITEM.getKey(item).toString().contains(query)))result.add(new PlacementAnalysis.Material(item,total,total,0,available));
        });
        result.sort(comparator(sort,descending));return result;
    }
    static Comparator<PlacementAnalysis.Material> comparator(Sort sort,boolean descending){Comparator<PlacementAnalysis.Material> order=switch(sort){case MISSING->Comparator.comparingLong(m->Math.max(0,m.total()-m.available()));case TOTAL->Comparator.comparingLong(PlacementAnalysis.Material::total);case AVAILABLE->Comparator.comparingLong(PlacementAnalysis.Material::available);case NAME->Comparator.comparing(m->m.item().getName(new net.minecraft.world.item.ItemStack(m.item())).getString(),java.text.Collator.getInstance(Locale.SIMPLIFIED_CHINESE));};return (descending?order.reversed():order).thenComparing(m->BuiltInRegistries.ITEM.getKey(m.item()).toString());}
}
