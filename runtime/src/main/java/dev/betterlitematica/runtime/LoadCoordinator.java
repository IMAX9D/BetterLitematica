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
        private static final class Shared {final BlueprintCache cache;final SpatialIndex index;final AuxiliaryData details;final String error;final boolean hit;final long elapsed;final SectionStreamer stream;int references=1;
            Shared(BlueprintCache cache,SpatialIndex index,AuxiliaryData details,String error,boolean hit,long elapsed){this.cache=cache;this.index=index;this.details=details;this.error=error;this.hit=hit;this.elapsed=elapsed;stream=new SectionStreamer(cache,32L<<20,false);}}
        private final Shared shared;private boolean closed;
        public Loaded(BlueprintCache cache,SpatialIndex index,AuxiliaryData details,String error,boolean hit,long elapsed){shared=new Shared(cache,index,details,error,hit,elapsed);}
        private Loaded(Shared shared){this.shared=shared;}
        public Loaded retain(){synchronized(shared){if(closed)throw new IllegalStateException("Source lease closed");shared.references++;return new Loaded(shared);}}
        public BlueprintCache cache(){return shared.cache;}public SpatialIndex index(){return shared.index;}public AuxiliaryData details(){return shared.details;}public String detailsError(){return shared.error;}public boolean cacheHit(){return shared.hit;}public long elapsedNanos(){return shared.elapsed;}public SectionStreamer stream(){return shared.stream;}
        public long estimatedBytes(){return (long)cache().index().size()*160+32L*1024*1024+64L*1024*1024;}
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
            synchronized(this){if(!closed&&epoch==generation){completed=new Loaded(cache,index,details,detailsError,result.cacheHit(),result.elapsedNanos());cache=null;status=new Status("ready",1,1,"");}}
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
