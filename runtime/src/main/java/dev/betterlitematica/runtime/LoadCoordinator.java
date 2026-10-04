package dev.betterlitematica.runtime;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
/** Single bounded import worker; an old request can never publish into a newer session. */
public final class LoadCoordinator implements AutoCloseable {
    /** Each lease is independently closeable; immutable source data and decoded sections are shared. */
    public static final class Loaded implements AutoCloseable {
        private static final class Shared {final BlueprintCache cache;final SpatialIndex index;final AuxiliaryData details;final String error;final boolean hit;final long elapsed;final SectionStreamer stream;final long estimatedBytes;int references=1;
            Shared(BlueprintCache cache,SpatialIndex index,AuxiliaryData details,String error,boolean hit,long elapsed){this.cache=cache;this.index=index;this.details=details;this.error=error;this.hit=hit;this.elapsed=elapsed;long capacity=Math.min(32L<<20,Math.max(32L<<10,(long)cache.index().size()*(32L<<10)));estimatedBytes=estimate(cache,details,capacity);stream=new SectionStreamer(cache,capacity,false);}}
        private final Shared shared;private boolean closed;
        public Loaded(BlueprintCache cache,SpatialIndex index,AuxiliaryData details,String error,boolean hit,long elapsed){shared=new Shared(cache,index,details,error,hit,elapsed);}
        private Loaded(Shared shared){this.shared=shared;}
        public Loaded retain(){synchronized(shared){if(closed)throw new IllegalStateException("Source lease closed");shared.references++;return new Loaded(shared);}}
        public BlueprintCache cache(){return shared.cache;}public SpatialIndex index(){return shared.index;}public AuxiliaryData details(){return shared.details;}public String detailsError(){return shared.error;}public boolean cacheHit(){return shared.hit;}public long elapsedNanos(){return shared.elapsed;}public SectionStreamer stream(){return shared.stream;}
        public long estimatedBytes(){return shared.estimatedBytes;}
        /** Computed once on the import worker; getters and retain never traverse source metadata. */
        private static long estimate(BlueprintCache cache,AuxiliaryData details,long decodedCapacity){
            var meter=new MemoryMeter();meter.add((4L<<20)+decodedCapacity+(long)cache.index().size()*512);
            var metadata=cache.metadata();meter.value(metadata.name(),0);
            for(var state:metadata.palette()){meter.add(96);meter.value(state.name(),0);meter.value(state.properties(),0);}
            for(var region:metadata.regions()){meter.add(160);meter.value(region.name(),0);}
            for(var histogram:cache.regionCounts())meter.add(64L+12L*histogram.size());
            meter.value(metadata.warnings(),0);
            if(details!=null)for(var part:details.parts()){meter.add(64);meter.value(part.blocks(),0);meter.value(part.entities(),0);}
            return meter.bytes;
        }
        private static final class MemoryMeter {
            private static final long LIMIT=1L<<30;
            long bytes;int visited;
            void add(long value){bytes=Math.min(LIMIT+1,bytes+value);}
            void value(Object value,int depth){
                if(bytes>LIMIT||value==null)return;
                if((++visited&255)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Source memory accounting cancelled");
                if(depth>64){bytes=LIMIT+1;return;}
                if(value instanceof String text)add(48L+2L*text.length());
                else if(value instanceof byte[] a)add(24L+a.length);
                else if(value instanceof int[] a)add(24L+4L*a.length);
                else if(value instanceof long[] a)add(24L+8L*a.length);
                else if(value instanceof java.util.Map<?,?> map){add(80L+64L*map.size());for(var e:map.entrySet()){if(bytes>LIMIT)break;value(e.getKey(),depth+1);value(e.getValue(),depth+1);}}
                else if(value instanceof java.util.List<?> list){add(32L+8L*list.size());for(var e:list){if(bytes>LIMIT)break;value(e,depth+1);}}
                else add(32);
            }
        }
        @Override public void close()throws IOException{synchronized(shared){if(closed)return;closed=true;if(--shared.references==0){shared.stream.close();shared.cache.close();}}}
    }
    public record Status(String phase,long completed,long total,String error){}
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"betterlitematica-import");t.setDaemon(true);return t;});
    private volatile Status status=new Status("idle",0,0,"");
    private long generation;private Future<?> job;private Loaded completed;private boolean closed;
    public synchronized void request(Path source,Path cacheDirectory){
        request(source,cacheDirectory,null);
    }
    public synchronized void request(Path source,Path cacheDirectory,Path allowedRoot){
        if(closed)throw new IllegalStateException("Loader closed");cancelInternal();long epoch=generation;status=new Status("queued",0,1,"");
        job=executor.submit(()->run(source,cacheDirectory,allowedRoot,epoch));
    }
    private void run(Path source,Path directory,Path allowedRoot,long epoch){
        BlueprintCache cache=null;
        try{
            if(allowedRoot!=null&&!source.toRealPath().startsWith(allowedRoot.toRealPath()))throw new IOException("Blueprint must stay inside the schematics directory");
            Cancellation cancel=()->Thread.currentThread().isInterrupted()||outdated(epoch);
            var result=SchematicImporter.importFile(source,directory,cancel,p->publishStatus(epoch,new Status(p.phase(),p.completed(),p.total(),"")));
            cancel.check();cache=BlueprintCache.open(result.path());SpatialIndex index=new SpatialIndex(cache,cancel);cancel.check();AuxiliaryData details=null;String detailsError="";
            try{details=AuxiliaryData.read(source,cache.metadata(),cancel);}catch(java.io.InterruptedIOException e){throw e;}catch(IOException|RuntimeException e){detailsError=e.toString();}cancel.check();
            Loaded prepared=new Loaded(cache,index,details,detailsError,result.cacheHit(),result.elapsedNanos());cache=null;
            synchronized(this){if(!closed&&epoch==generation){completed=prepared;status=new Status("ready",1,1,"");}else closeQuietly(prepared);}
        }catch(IOException|RuntimeException e){publishStatus(epoch,new Status(e instanceof InterruptedIOException?"cancelled":"failed",0,0,e.toString()));}
        finally{closeQuietly(cache);}
    }
    private synchronized boolean outdated(long epoch){return closed||epoch!=generation;}
    private synchronized void publishStatus(long epoch,Status value){if(!closed&&epoch==generation)status=value;}
    public Status status(){return status;}
    /** Transfers ownership of the opened cache to the caller; must be called from the game owner thread. */
    public synchronized Loaded poll(){Loaded value=completed;completed=null;return value;}
    public synchronized void cancel(){cancelInternal();status=new Status("cancelled",0,0,"");}
    private void cancelInternal(){generation++;if(job!=null){job.cancel(true);job=null;}executor.getQueue().clear();if(completed!=null){closeQuietly(completed);completed=null;}}
    @Override public synchronized void close(){if(!closed){cancelInternal();closed=true;executor.shutdownNow();}}
    private static void closeQuietly(AutoCloseable c){if(c!=null)try{c.close();}catch(Exception e){System.err.println("BetterLitematica resource close: "+e);}}
}
