package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferBuilder;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

/**
 * Worker-to-render handoff. A job retains its builder until the upload future completes, so the
 * game's existing fixed builder pool also bounds this queue. Never wait on the GPU from a worker.
 */
public final class TerrainUploadQueue {
    private static final class Pending {
        final TerrainBuffer target;final BufferBuilder.BuiltBuffer data;final TerrainBuildStamp stamp;
        final CompletableFuture<Void> completion;final long bytes;TerrainRegions.Transfer transfer;boolean begun;
        Pending(TerrainBuffer target,BufferBuilder.BuiltBuffer data,TerrainBuildStamp stamp,CompletableFuture<Void> completion,long bytes){
            this.target=target;this.data=data;this.stamp=stamp;this.completion=completion;this.bytes=bytes;
        }
        void discard(){if(transfer!=null)transfer.abort();data.release();completion.complete(null);}
    }
    private final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private boolean stopped;
    private long queuedBytes;
    private volatile Pending active;
    public static final LongAdder preparedBytes=new LongAdder(),prepareNanos=new LongAdder(),discarded=new LongAdder();
    public static long uploadedBytes,uploadNanos;
    public static volatile boolean visibleHoles;

    public CompletableFuture<Void> offer(TerrainBuffer target,BufferBuilder.BuiltBuffer data,TerrainBuildStamp stamp){
        var completion=new CompletableFuture<Void>();
        long started=System.nanoTime();
        try{
            if(!stamp.current()||target.isClosed()){
                data.release();discarded.increment();completion.complete(null);return completion;
            }
            // The builder is still exclusively owned by this worker. Change positions in-place,
            // avoiding a second full native mesh allocation. Sorting centres stay section-local.
            target.prepare(data,stamp);
            long bytes=(long)data.getVertexBuffer().remaining()+data.getIndexBuffer().remaining();
            synchronized(this){
                if(!stopped){pending.addLast(new Pending(target,data,stamp,completion,bytes));queuedBytes+=bytes;return completion;}
            }
            data.release();completion.complete(null);
        }catch(Throwable failure){
            data.release();completion.completeExceptionally(failure);
        }finally{prepareNanos.add(System.nanoTime()-started);}
        return completion;
    }

    public void drain(){
        RenderSystem.assertOnRenderThread();
        long start=System.nanoTime(),budget=visibleHoles?6_000_000L:2_000_000L;
        long bytes=0,limit=visibleHoles?8L<<20:4L<<20;
        do{
            Pending next=active;
            if(next==null)synchronized(this){active=next=pending.pollFirst();}
            if(next==null)break;
            boolean finished=false;Throwable failure=null;
            try{
                if(!next.stamp.current()||next.target.isClosed()){
                    if(next.transfer!=null)next.transfer.abort();discarded.increment();finished=true;
                }else{
                    if(!next.begun){next.transfer=next.target.beginUpload(next.data,next.stamp);next.begun=true;}
                    if(next.transfer==null)finished=true;
                    else{
                        bytes+=next.transfer.step();
                        if(next.transfer.done()){next.target.finishUpload(next.transfer,next.stamp);finished=true;}
                    }
                }
            }catch(Throwable problem){
                failure=problem;finished=true;if(next.transfer!=null)next.transfer.abort();
            }
            if(finished){
                active=null;synchronized(this){queuedBytes-=next.bytes;}
                next.data.release();
                // Publish ChunkData only after every layer is resident, using vanilla's future chain.
                if(failure==null)next.completion.complete(null);else next.completion.completeExceptionally(failure);
            }
        }while(bytes<limit&&System.nanoTime()-start<budget);
        uploadedBytes+=bytes;uploadNanos+=System.nanoTime()-start;
    }

    public synchronized int size(){return pending.size()+(active==null?0:1);}
    public synchronized long bytes(){return queuedBytes;}

    public void stop(){
        RenderSystem.assertOnRenderThread();
        // Complete outside the lock: completion handlers may return buffers or enqueue more work.
        ArrayDeque<Pending> cancelled;
        synchronized(this){stopped=true;cancelled=new ArrayDeque<>(pending);pending.clear();if(active!=null){cancelled.addFirst(active);active=null;}queuedBytes=0;}
        for(var next:cancelled)next.discard();
    }
}
