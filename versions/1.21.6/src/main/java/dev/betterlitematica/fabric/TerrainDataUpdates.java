package dev.betterlitematica.fabric;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.render.chunk.ChunkBuilder;

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
    public static Object exchange(AtomicReference<?> reference,Object next,Operation<Object> original){
        return exchange(null,reference,next,original);
    }
    public static Object exchange(ChunkBuilder.BuiltChunk section,AtomicReference<?> reference,Object next,Operation<Object> original){
        Object previous=reference.get();
        boolean membership=kind(previous)!=kind(next);
        boolean topology=false;
        if(previous instanceof net.minecraft.client.render.chunk.AbstractChunkRenderData a&&next instanceof net.minecraft.client.render.chunk.AbstractChunkRenderData b&&a!=b){
            long connections=TerrainTraversal.encode(b);topology=TerrainTraversal.encode(a)!=connections;
            next=b;
        }
        if(topology){CHANGING.incrementAndGet();TOPOLOGY.incrementAndGet();}
        try{return original.call(reference,next);}finally{
            if(previous!=next){
                if(section instanceof TerrainSectionChanges.Owner owner)owner.betterlitematica$sectionChanged();
                else TerrainSectionChanges.unlocatedChange();
            }
            if(membership)REVISION.incrementAndGet();
            if(topology){TOPOLOGY.incrementAndGet();CHANGING.decrementAndGet();}
        }
    }
    private static int kind(Object value){
        if(!(value instanceof net.minecraft.client.render.chunk.AbstractChunkRenderData data))return -1;
        return (!data.hasData()?0:1)|(data.getBlockEntities().isEmpty()?0:2);
    }
}
