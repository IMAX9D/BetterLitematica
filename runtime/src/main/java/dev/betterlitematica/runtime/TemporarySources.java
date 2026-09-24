package dev.betterlitematica.runtime;

import dev.betterlitematica.core.Cancellation;
import dev.betterlitematica.io.NbtWriter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Complete temporary sources, distinct from lossy/resident render caches and persistent user files. */
public final class TemporarySources implements AutoCloseable {
    private static final String PREFIX="@temporary/";
    public record Reference(Path path,Path root,boolean temporary){
        public Path read()throws IOException{Path base=root.toRealPath(),source=path.toRealPath();if(!source.startsWith(base)||!Files.isRegularFile(source))throw new IOException("投影源文件越界或不存在");return source;}
    }
    private static final class Entry {final Path path;long bytes;int readers;boolean ready;Entry(Path path){this.path=path;}}
    private final Path directory;
    private final Map<String,Entry> entries=new LinkedHashMap<>();private final Map<Path,Entry> retired=new LinkedHashMap<>();private long generation,bytes;private boolean closed;
    private final java.util.concurrent.ExecutorService cleaner=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),r->{var thread=new Thread(r,"betterlitematica-source-cleanup");thread.setDaemon(true);return thread;});
    private java.util.concurrent.CompletableFuture<Void> cleaning;
    public TemporarySources(Path cache){directory=cache.resolve("temporary-sources").resolve(UUID.randomUUID().toString()).toAbsolutePath().normalize();}
    public static boolean temporary(String source){return source.startsWith(PREFIX);}
    public synchronized Reference reference(Path schematics,String source){
        if(!temporary(source))return new Reference(schematics.resolve(source),schematics,false);
        var entry=entries.get(source);if(entry==null||!entry.ready)throw new IllegalArgumentException("临时投影已失效");return new Reference(entry.path,directory,true);
    }
    public final class Lease implements AutoCloseable {
        private final Reference reference;private Entry entry;
        private Lease(Reference reference,Entry entry){this.reference=reference;this.entry=entry;}
        public Reference reference(){return reference;}
        @Override public void close(){synchronized(TemporarySources.this){if(entry==null)return;entry.readers--;entry=null;}if(cleanupPending()){boolean ended;synchronized(TemporarySources.this){ended=closed;}if(ended){try{delete(clearRetired());}catch(IOException failure){System.getLogger("betterlitematica").log(System.Logger.Level.ERROR,"Temporary source cleanup failed",failure);}}else cleanupAsync();}}
    }
    public synchronized Lease lease(Path schematics,String source){var reference=reference(schematics,source);var entry=entries.get(source);if(entry!=null)entry.readers++;return new Lease(reference,entry);}
    public String create(Map<String,Object> root,Cancellation cancellation)throws IOException{
        String name=UUID.randomUUID()+".litematic",key=PREFIX+name;Entry entry=new Entry(directory.resolve(name));long epoch;
        synchronized(this){if(closed)throw new IOException("临时资源已关闭");if(entries.size()+retired.size()>=16)throw new IOException("临时投影已达上限");epoch=generation;entries.put(key,entry);}
        boolean committed=false;
        try{cancellation.check();Files.createDirectories(directory);NbtWriter.writeNew(entry.path,root,()->cancellation.cancelled()||stale(epoch));long size=Files.size(entry.path);
            synchronized(this){cancellation.check();if(epoch!=generation)throw new InterruptedIOException("临时投影已取消");if(size>64L*1024*1024||bytes+size>256L*1024*1024)throw new IOException("临时投影超过缓存预算");entry.bytes=size;entry.ready=true;bytes+=size;committed=true;return key;}
        }finally{if(!committed){synchronized(this){if(entries.remove(key,entry))bytes-=entry.bytes;}Files.deleteIfExists(entry.path);}}
    }
    private synchronized boolean stale(long epoch){return epoch!=generation;}
    /** Retired files retain their budget and identity until deletion succeeds. */
    public synchronized List<Path> clear(){generation++;for(var entry:entries.values())retired.put(entry.path,entry);entries.clear();return List.copyOf(retired.keySet());}
    public synchronized List<Path> remove(String source){var entry=entries.remove(source);if(entry==null)return List.of();retired.put(entry.path,entry);return List.of(entry.path);}
    private synchronized List<Path> clearRetired(){return retired.entrySet().stream().filter(e->e.getValue().readers==0).map(Map.Entry::getKey).toList();}
    public synchronized boolean cleanupPending(){return retired.values().stream().anyMatch(e->e.readers==0);}
    public void delete(List<Path> files)throws IOException{for(Path path:files){Entry entry;synchronized(this){entry=retired.get(path);}if(entry==null)continue;synchronized(this){if(entry.readers!=0)continue;Files.deleteIfExists(path);if(retired.remove(path,entry))bytes-=entry.bytes;}}}
    public synchronized java.util.concurrent.CompletableFuture<Void> cleanupAsync(){
        if(cleaning!=null&&!cleaning.isDone())return cleaning;if(!cleanupPending())return java.util.concurrent.CompletableFuture.completedFuture(null);
        var future=new java.util.concurrent.CompletableFuture<Void>();cleaning=future;cleaner.execute(()->{
            try{while(true){List<Path> paths;synchronized(this){if(!cleanupPending()){if(cleaning==future)cleaning=null;future.complete(null);return;}paths=retired.entrySet().stream().filter(e->e.getValue().readers==0).map(Map.Entry::getKey).toList();}delete(paths);}}
            catch(IOException e){synchronized(this){if(cleaning==future)cleaning=null;}future.completeExceptionally(e);}
        });return future;
    }
    @Override public void close(){synchronized(this){if(closed)return;closed=true;}clear();cleanupAsync();cleaner.shutdown();try{cleaner.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
