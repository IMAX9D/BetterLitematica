package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.chunk.ChunkBuilder.Buffers;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Bounded by the game's builder pool. Workers retain their mesh until every upload completes. */
public final class TerrainUploadQueue {
    private static final class Pending {
        final TerrainBuiltBuffer mesh;final TerrainBuildStamp stamp;final Supplier<Buffers> current;final Consumer<Buffers> publish;
        final CompletableFuture<Void> completion=new CompletableFuture<>();TerrainRegions.Transfer transfer;
        Pending(TerrainBuiltBuffer mesh,TerrainBuildStamp stamp,Supplier<Buffers> current,Consumer<Buffers> publish){this.mesh=mesh;this.stamp=stamp;this.current=current;this.publish=publish;}
        void finish(Throwable failure){try{mesh.release();}finally{if(failure==null)completion.complete(null);else completion.completeExceptionally(failure);}}
    }
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();private volatile Pending active;private boolean closed;
    public static volatile boolean visibleHoles;public static long uploadedBytes,uploadNanos,discarded;
    public CompletableFuture<Void> offer(TerrainBuiltBuffer mesh,TerrainBuildStamp stamp,Supplier<Buffers> current,Consumer<Buffers> publish){
        var next=new Pending(mesh,stamp,current,publish);
        synchronized(this){if(!closed&&stamp.current()){queue.addLast(next);return next.completion;}}
        next.finish(null);return next.completion;
    }
    public synchronized int size(){return queue.size()+(active==null?0:1);}
    public void drain(){
        RenderSystem.assertOnRenderThread();long started=System.nanoTime(),budget=visibleHoles?6_000_000:2_000_000;int bytes=0,limit=visibleHoles?8<<20:4<<20;
        do{
            Pending job=active;if(job==null)synchronized(this){active=job=queue.pollFirst();}if(job==null)break;
            boolean done=false;Throwable failure=null;
            try{
                if(!job.stamp.current()){if(job.transfer!=null)job.transfer.abort();discarded++;done=true;}
                else{
                    if(job.transfer==null){
                        var old=job.current.get();
                        if(job.mesh.indexOnly&&(old==null||!TerrainRegions.INSTANCE.owns(old))){done=true;discarded++;}
                        else job.transfer=TerrainRegions.INSTANCE.begin(job.mesh,old);
                    }
                    if(!done){bytes+=job.transfer.step();if(job.transfer.done()){
                        if(job.stamp.current())job.publish.accept(job.transfer.commit());else job.transfer.abort();done=true;
                    }}
                }
            }catch(Throwable problem){failure=problem;done=true;if(job.transfer!=null)job.transfer.abort();}
            if(done){active=null;job.finish(failure);}
        }while(bytes<limit&&System.nanoTime()-started<budget);
        uploadedBytes+=bytes;uploadNanos+=System.nanoTime()-started;
    }
    public void stop(){
        RenderSystem.assertOnRenderThread();ArrayDeque<Pending> cancelled;
        synchronized(this){closed=true;cancelled=new ArrayDeque<>(queue);queue.clear();if(active!=null){cancelled.addFirst(active);active=null;}}
        for(var job:cancelled){if(job.transfer!=null)job.transfer.abort();job.finish(null);}
    }
}
