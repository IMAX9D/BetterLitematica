package dev.betterlitematica.runtime;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Disk decode only. Bounded task count, bounded completed queue, bounded CPU cache. Never accesses Minecraft. */
public final class SectionStreamer implements AutoCloseable {
    private record Request(long epoch,long serial){}
    private record Ready(SectionKey key,Request request,PackedSection section,String error){}
    private final BlueprintCache source;private final boolean ownsSource;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"betterlitematica-section");t.setDaemon(true);return t;});
    private final ConcurrentHashMap<SectionKey,Request> inFlight=new ConcurrentHashMap<>();
    // Each section is <= ~23 KiB in memory by format construction. 16 ready slots plus
    // at most two worker-local sections; producers wait rather than discard decoded data.
    private final ArrayBlockingQueue<Ready> ready=new ArrayBlockingQueue<>(16);
    private final WeightedLru<SectionKey,PackedSection> cache;
    private final AtomicLong sequence=new AtomicLong();private final AtomicLong epoch=new AtomicLong();private volatile boolean closed;private String error="";
    private long revision;
    public SectionStreamer(BlueprintCache source,long maxCpuBytes){this(source,maxCpuBytes,true);}
    public SectionStreamer(BlueprintCache source,long maxCpuBytes,boolean ownsSource){this.source=source;this.ownsSource=ownsSource;cache=new WeightedLru<>(maxCpuBytes,PackedSection::estimatedBytes,s->{});}
    public void drain(){
        Ready r;while((r=ready.poll())!=null){inFlight.remove(r.key,r.request);if(r.request.epoch!=epoch.get()||closed)continue;
            if(r.section!=null&&cache.put(r.key,r.section))revision++;if(!r.error.isEmpty())error=r.error;
        }
    }
    public PackedSection get(SectionKey key){return cache.get(key);}
    public boolean request(SectionKey key){
        if(closed||cache.contains(key)||!source.index().containsKey(key))return false;
        Request request=new Request(epoch.get(),sequence.incrementAndGet());
        if(inFlight.putIfAbsent(key,request)!=null)return false;
        try{workers.execute(()->{
            boolean published=false;
            try{
                if(closed||request.epoch!=epoch.get())return;
                PackedSection section=source.read(key);
                published=publish(new Ready(key,request,section,""));
            }catch(InterruptedException e){Thread.currentThread().interrupt();
            }catch(IOException|RuntimeException e){
                if(!closed&&request.epoch==epoch.get())published=ready.offer(new Ready(key,request,null,e.toString()));
            }finally{if(!published)inFlight.remove(key,request);}
        });return true;}catch(RejectedExecutionException e){inFlight.remove(key,request);return false;}
    }

    private boolean publish(Ready result)throws InterruptedException{
        while(!closed&&result.request.epoch==epoch.get()){
            if(ready.offer(result,10,TimeUnit.MILLISECONDS))return true;
        }
        return false;
    }

    public void cancelPending(){epoch.incrementAndGet();workers.getQueue().clear();inFlight.clear();ready.clear();}
    public long cachedBytes(){return cache.usedBytes();}public int queuedJobs(){return inFlight.size();}public String error(){return error;}
    /** Owner-thread wakeup token: advances only when a current decode is admitted by drain(). */
    public long revision(){return revision;}
    public BlueprintCache source(){return source;}
    @Override public void close(){
        if(closed)return;closed=true;cancelPending();workers.shutdownNow();cache.close();
        // One bounded section read may finish before close acquires the file lock; no awaitTermination on the render thread.
        try{if(ownsSource)source.close();}catch(IOException e){error=e.toString();}
    }
}
