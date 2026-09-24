package dev.betterlitematica.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated serial checkpoints. Failed writes retain their latest image; reads never pass them. */
public final class DraftWriter implements AutoCloseable {
    public interface Backend {
        void write(Path path,DraftStore.Draft value)throws IOException;
        void clear(Path path)throws IOException;
        DraftStore.Draft read(Path path)throws IOException;
        default Path archive(Path path)throws IOException{if(!Files.exists(path))return null;Path target=path.resolveSibling(path.getFileName()+".recovery-"+UUID.randomUUID());return Files.move(path,target,StandardCopyOption.ATOMIC_MOVE);}
    }
    private static final Backend FILES=new Backend(){public void write(Path p,DraftStore.Draft d)throws IOException{DraftStore.write(p,d);}public void clear(Path p)throws IOException{DraftStore.clear(p);}public DraftStore.Draft read(Path p)throws IOException{return DraftStore.read(p);}};
    public record Status(long requested,long persisted,boolean pending,long attempts,String error){}
    private static final long MAX_RETAINED_BYTES=128L<<20;
    private static final class Slot {
        final Path path;long requested,persisted,attempts,retryAt,failedRevision;boolean reserved;DraftStore.Draft value;String error="";
        Slot(Path path){this.path=path;}
    }
    private enum Kind {FLUSH,READ,ARCHIVE}
    private static final class Barrier {
        final Slot slot;final long target,attempt;final Kind kind;final DraftStore.Draft image;final CompletableFuture<Object> result=new CompletableFuture<>();
        Barrier(Slot slot,Kind kind){this.slot=slot;this.target=slot.requested;this.attempt=slot.attempts;this.kind=kind;this.image=slot.value;}
    }
    private final int maxPaths;private final Backend backend;
    private final LinkedHashMap<Path,Slot> slots=new LinkedHashMap<>();private final ArrayDeque<Barrier> barriers=new ArrayDeque<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"betterlitematica-drafts");t.setDaemon(true);return t;});
    private boolean scheduled,closing,closed;private long sequence;private Slot inFlight;private DraftStore.Draft inFlightValue;
    public DraftWriter(){this(16,FILES);}
    /** Injectable storage permits deterministic failure/order tests without a game. */
    public DraftWriter(int maxPaths,Backend backend){if(maxPaths<1||maxPaths>16)throw new IllegalArgumentException("Draft path budget");this.maxPaths=maxPaths;this.backend=Objects.requireNonNull(backend);}
    private static Path path(Path path){return path.toAbsolutePath().normalize();}
    private Slot slot(Path path){
        var key=path(path);var found=slots.get(key);if(found!=null)return found;
        if(slots.size()>=maxPaths){var it=slots.values().iterator();while(it.hasNext()){var candidate=it.next();if(!candidate.reserved&&candidate!=inFlight&&candidate.requested==candidate.persisted&&barriers.stream().noneMatch(b->b.slot==candidate)){it.remove();break;}}}
        if(slots.size()>=maxPaths)throw new IllegalStateException("草稿保存队列已满，当前草稿仍需保留");var next=new Slot(key);slots.put(key,next);return next;
    }
    public synchronized long write(Path path,DraftStore.Draft draft){Objects.requireNonNull(draft);return request(path,draft);}
    public synchronized long clear(Path path){return request(path,null);}
    /** Protect a path slot until its next accepted write/clear, or explicit release. */
    public synchronized boolean reserve(Path path){try{ensureOpen();slot(path).reserved=true;return true;}catch(IllegalStateException e){return false;}}
    public synchronized void release(Path path){var slot=slots.get(path(path));if(slot!=null)slot.reserved=false;}
    public synchronized boolean canWrite(Path path,DraftStore.Draft draft){
        if(closing||closed||draft==null)return false;Slot slot;try{slot=slot(path);}catch(IllegalStateException e){return false;}
        var previous=slot.value;slot.value=draft;try{return retainedBytes()<=MAX_RETAINED_BYTES;}finally{slot.value=previous;}
    }
    private long request(Path path,DraftStore.Draft draft){
        ensureOpen();var slot=slot(path);var previous=slot.value;slot.value=draft;
        if(retainedBytes()>MAX_RETAINED_BYTES){slot.value=previous;throw new IllegalStateException("草稿保存内存预算已满，当前草稿仍需保留");}
        slot.reserved=false;slot.requested=++sequence;slot.retryAt=0;slot.error="";kick(0);return sequence;
    }
    public CompletableFuture<Void> flush(Path path){return barrier(path,Kind.FLUSH).thenApply(ignored->null);}
    public CompletableFuture<DraftStore.Draft> read(Path path){return barrier(path,Kind.READ).thenApply(value->(DraftStore.Draft)value);}
    public CompletableFuture<Path> archive(Path path){return barrier(path,Kind.ARCHIVE).thenApply(value->(Path)value);}
    private synchronized CompletableFuture<Object> barrier(Path path,Kind kind){
        try{ensureOpen();if(barriers.size()>=64)throw new IllegalStateException("Draft barrier queue is full");var slot=slot(path);slot.retryAt=0;var b=new Barrier(slot,kind);barriers.add(b);kick(0);return b.result;}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
    }
    public synchronized CompletableFuture<Void> flush(){
        try{ensureOpen();if(barriers.size()+slots.size()>80)throw new IllegalStateException("Draft barrier queue is full");return flushAll();}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
    }
    private CompletableFuture<Void> flushAll(){var results=new ArrayList<CompletableFuture<?>>();for(var slot:slots.values()){slot.retryAt=0;var b=new Barrier(slot,Kind.FLUSH);barriers.add(b);results.add(b.result);}kick(0);return CompletableFuture.allOf(results.toArray(CompletableFuture<?>[]::new));}
    public synchronized void retry(Path path){ensureOpen();var slot=slots.get(path(path));if(slot!=null){slot.retryAt=0;kick(0);}}
    public synchronized Status status(Path path){var slot=slots.get(path(path));return slot==null?new Status(0,0,false,0,""):new Status(slot.requested,slot.persisted,slot.requested!=slot.persisted,slot.attempts,slot.error);}
    public synchronized long retainedBytes(){
        var images=Collections.newSetFromMap(new IdentityHashMap<dev.betterlitematica.core.SchematicEdits.Checkpoint,Boolean>());long bytes=0;
        for(var slot:slots.values())if(slot.value!=null&&images.add(slot.value.checkpoint()))bytes+=slot.value.checkpoint().estimatedBytes()+4096;
        for(var barrier:barriers)if(barrier.image!=null&&images.add(barrier.image.checkpoint()))bytes+=barrier.image.checkpoint().estimatedBytes()+4096;
        if(inFlightValue!=null&&images.add(inFlightValue.checkpoint()))bytes+=inFlightValue.checkpoint().estimatedBytes()+4096;return bytes;
    }
    private void ensureOpen(){if(closing||closed)throw new IllegalStateException("Draft writer is closed");}
    private void kick(long delay){if(!closed&&!scheduled){scheduled=true;worker.schedule(this::pump,Math.max(0,delay),TimeUnit.MILLISECONDS);}}
    private void pump(){
        Barrier barrier=null;Slot writing=null;DraftStore.Draft value=null;long revision=0;String blocked=null;
        synchronized(this){
            if(closed){scheduled=false;return;}
            for(var it=barriers.iterator();it.hasNext();){var b=it.next();if(b.slot.persisted>=b.target||!b.slot.error.isEmpty()&&b.slot.failedRevision>=b.target&&b.slot.attempts>b.attempt){barrier=b;it.remove();if(b.slot.persisted<b.target)blocked=b.slot.error;break;}}
            if(barrier!=null){inFlight=barrier.slot;inFlightValue=barrier.image;}
            if(barrier==null){long now=System.currentTimeMillis();for(var slot:slots.values())if(slot.requested>slot.persisted&&slot.retryAt<=now){writing=slot;break;}
                if(writing!=null){value=writing.value;revision=writing.requested;
                    for(var b:barriers)if(b.slot==writing&&b.target>writing.persisted){value=b.image;revision=b.target;break;}
                    inFlight=writing;inFlightValue=value;}}
        }
        try{
            if(barrier!=null){
                Object result=null;Exception failure=blocked==null?null:new IOException(blocked);
                if(failure==null)try{result=switch(barrier.kind){case FLUSH->null;case READ->backend.read(barrier.slot.path);case ARCHIVE->backend.archive(barrier.slot.path);};}catch(Exception e){failure=e;}
                // Completion can wake another thread or run its callbacks inline. Publish it only
                // after releasing this barrier's lease, so admission and accounting agree with it.
                synchronized(this){inFlight=null;inFlightValue=null;}
                if(failure==null)barrier.result.complete(result);else barrier.result.completeExceptionally(failure);
            }
            else if(writing!=null){String failure="";try{if(value==null)backend.clear(writing.path);else backend.write(writing.path,value);}catch(Exception e){failure=e.toString();}
                synchronized(this){writing.attempts++;if(failure.isEmpty()){writing.persisted=revision;writing.error="";writing.retryAt=0;if(writing.requested==revision)writing.value=null;}
                    else {writing.error=failure;writing.failedRevision=revision;writing.retryAt=System.currentTimeMillis()+1000;}inFlight=null;inFlightValue=null;}}
        }finally{
            synchronized(this){if(barrier!=null){inFlight=null;inFlightValue=null;}scheduled=false;if(!closed){long wait=Long.MAX_VALUE,now=System.currentTimeMillis();for(var b:barriers)if(b.slot.persisted>=b.target||!b.slot.error.isEmpty()&&b.slot.failedRevision>=b.target&&b.slot.attempts>b.attempt){wait=0;break;}
                for(var slot:slots.values())if(slot.requested>slot.persisted)wait=Math.min(wait,Math.max(0,slot.retryAt-now));if(wait!=Long.MAX_VALUE)kick(wait);}}
        }
    }
    /** A failed shutdown is reported; pending images remain inspectable through status(). */
    @Override public void close()throws IOException{
        CompletableFuture<Void> flush;
        synchronized(this){if(closed)return;if(closing)throw new IOException("Draft shutdown already running");closing=true;flush=flushAll();}
        IOException failure=null;try{flush.get(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();failure=new IOException("Draft shutdown interrupted",e);}catch(ExecutionException|TimeoutException e){failure=new IOException("Drafts remain unsaved at shutdown",e);}
        finally{synchronized(this){closed=true;for(var b:barriers)b.result.completeExceptionally(new IOException("Draft writer closed"));barriers.clear();}worker.shutdownNow();}
        if(failure!=null)throw failure;
    }
}
