package dev.betterlitematica.fabric;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.render.chunk.ChunkBuilder;

/** Geometry/entity membership and connectivity have separate revisions; draws still read current data. */
public final class TerrainDataUpdates {
    private static final AtomicLong REVISION=new AtomicLong();
    private static final AtomicLong TOPOLOGY=new AtomicLong();
    private static final AtomicInteger CHANGING=new AtomicInteger();
    private TerrainDataUpdates(){}
    public static long revision(){return REVISION.get();}
    public static long topologyStamp(){
        if(CHANGING.get()!=0)return Long.MIN_VALUE;
        long stamp=TOPOLOGY.get();return CHANGING.get()==0?stamp:Long.MIN_VALUE;
    }
    public static void publish(AtomicReference<?> reference,Object next,Operation<Void> original){
        Object previous=reference.get();
        boolean membership=kind(previous)!=kind(next);
        boolean topology=false;
        if(previous instanceof ChunkBuilder.ChunkData a&&next instanceof ChunkBuilder.ChunkData b&&a!=b){
            long connections=TerrainTraversal.encode(b);topology=TerrainTraversal.encode(a)!=connections;
            next=TerrainEmptyData.canonical(b,connections);
        }
        if(topology){CHANGING.incrementAndGet();TOPOLOGY.incrementAndGet();}
        try{original.call(reference,next);}finally{
            if(membership)REVISION.incrementAndGet();
            if(topology){TOPOLOGY.incrementAndGet();CHANGING.decrementAndGet();}
        }
    }
    private static int kind(Object value){
        if(!(value instanceof ChunkBuilder.ChunkData data))return -1;
        return (!data.hasNonEmptyLayers()?0:1)|(data.getBlockEntities().isEmpty()?0:2);
    }
}
