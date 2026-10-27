package dev.betterlitematica.fabric;

import java.util.*;
import java.util.function.Consumer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

/** Coalesce owned terrain commands after the game established their order. The native encoder
 * still binds the pipeline, attributes, samplers and uniforms; only its final draw becomes multi-draw. */
public final class TerrainMultiDraw {
    private TerrainMultiDraw(){}
    private record Key(Object vertices,Object indices,Object uniform){}
    private static final class Commands {
        IntBuffer counts;PointerBuffer offsets;int size;Object first;
        void clear(){size=0;first=null;}
        void add(Object draw,TerrainRegions.Draw geometry){
            if(counts==null||size==counts.capacity()){
                int capacity=counts==null?16:counts.capacity()*2;var next=MemoryUtil.memAllocInt(capacity);var pointers=MemoryUtil.memAllocPointer(capacity);
                if(counts!=null)for(int i=0;i<size;i++){next.put(i,counts.get(i));pointers.put(i,offsets.get(i));}
                MemoryUtil.memFree(counts);MemoryUtil.memFree(offsets);counts=next;offsets=pointers;
            }
            if(first==null)first=draw;counts.put(size,geometry.count());offsets.put(size,(long)geometry.firstIndex()*4);size++;
        }
        void close(){MemoryUtil.memFree(counts);MemoryUtil.memFree(offsets);counts=null;offsets=null;clear();}
    }
    private static final ArrayList<Commands> POOL=new ArrayList<>();
    private static final LinkedHashMap<Key,Commands> GROUPS=new LinkedHashMap<>();
    private static final ArrayList<Commands> ORDER=new ArrayList<>();
    private static final ThreadLocal<Commands> ACTIVE=new ThreadLocal<>();
    public static long drawCalls,sections;
    private static Commands next(){int i=ORDER.size();if(i==POOL.size())POOL.add(new Commands());var result=POOL.get(i);result.clear();ORDER.add(result);return result;}
    public static <T> boolean draw(Collection<T> objects,Consumer<Collection<T>> original){
        if(objects.isEmpty()||ACTIVE.get()!=null)return false;
        boolean ordered=false;
        for(var object:objects){var geometry=TerrainRegions.INSTANCE.drawInfo(object);if(geometry==null)return false;ordered|=geometry.ordered();}
        GROUPS.clear();ORDER.clear();Key previous=null;Commands run=null;
        for(var object:objects){var geometry=TerrainRegions.INSTANCE.drawInfo(object);var key=new Key(geometry.vertices(),geometry.indices(),geometry.uniformKey());
            Commands commands;
            if(ordered){if(!key.equals(previous)){run=next();previous=key;}commands=run;}
            else{commands=GROUPS.get(key);if(commands==null){commands=next();GROUPS.put(key,commands);}}
            commands.add(object,geometry);
        }
        try{for(var commands:ORDER){ACTIVE.set(commands);original.accept(Collections.singletonList((T)commands.first));}}
        finally{ACTIVE.remove();for(var commands:ORDER)commands.clear();GROUPS.clear();ORDER.clear();}
        return true;
    }
    public static boolean elements(int mode,int type){
        var commands=ACTIVE.get();if(commands==null)return false;
        commands.counts.position(0).limit(commands.size);commands.offsets.position(0).limit(commands.size);
        try{GL14.glMultiDrawElements(mode,commands.counts,type,commands.offsets);drawCalls++;sections+=commands.size;}
        finally{commands.counts.limit(commands.counts.capacity());commands.offsets.limit(commands.offsets.capacity());}
        return true;
    }
    public static void clear(){ACTIVE.remove();POOL.forEach(Commands::close);POOL.clear();GROUPS.clear();ORDER.clear();}
}
