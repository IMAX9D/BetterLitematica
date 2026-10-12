package dev.betterlitematica.fabric;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.render.chunk.ChunkBuilder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Track category changes, not every empty-section upload. Actual meshes and entity contents remain live. */
public final class TerrainDataUpdates {
    private static final AtomicLong REVISION=new AtomicLong();
    private TerrainDataUpdates(){}
    public static long revision(){return REVISION.get();}
    private static int category(Object value){
        if(!(value instanceof ChunkBuilder.ChunkData data))return -1;
        return (data.isEmpty()?0:1)|(data.getBlockEntities().isEmpty()?0:2);
    }
    public static void publish(AtomicReference<?> reference,Object next,Operation<Void> original){
        int before=category(reference.get());
        try{original.call(reference,next);}finally{if(before!=category(reference.get()))REVISION.incrementAndGet();}
    }
}
