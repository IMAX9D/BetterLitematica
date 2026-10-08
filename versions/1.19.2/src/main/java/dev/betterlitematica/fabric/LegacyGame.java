package dev.betterlitematica.fabric;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import java.util.List;
import java.util.function.Predicate;

/** Older game capabilities, retaining bounded query results and item data. */
final class LegacyGame {
    static ItemStack light(ItemStack stack,int level){stack.getOrCreateSubNbt("BlockStateTag").putString("level",Integer.toString(level));return stack;}
    static <T extends Entity> void collect(World world,TypeFilter<Entity,T> type,Box box,Predicate<T> filter,List<T> result,int limit){
        int remaining=Math.max(0,limit-result.size());if(remaining==0)return;int[] accepted={0};
        result.addAll(world.getEntitiesByType(type,box,value->accepted[0]<remaining&&filter.test(value)&&++accepted[0]<=remaining));
    }
    static int nbtBytes(NbtElement value){return (int)Math.min(Integer.MAX_VALUE,size(value,0));}
    private static long size(NbtElement value,int depth){
        if(depth>64)return Integer.MAX_VALUE;
        long bytes=48;
        if(value instanceof NbtCompound map){for(String key:map.getKeys()){bytes+=48L+key.length()*2L+size(map.get(key),depth+1);if(bytes>=Integer.MAX_VALUE)return Integer.MAX_VALUE;}}
        else if(value instanceof NbtList list){for(var child:list){bytes+=size(child,depth+1);if(bytes>=Integer.MAX_VALUE)return Integer.MAX_VALUE;}}
        else if(value instanceof NbtString text)bytes+=text.asString().length()*2L;
        else if(value instanceof NbtByteArray array)bytes+=array.size();
        else if(value instanceof NbtIntArray array)bytes+=array.size()*4L;
        else if(value instanceof NbtLongArray array)bytes+=array.size()*8L;
        return bytes;
    }
}
