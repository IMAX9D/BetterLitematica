package dev.betterlitematica.runtime;

import dev.betterlitematica.core.PlacementSession;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Serial disk operations preserve save-before-restore ordering across world changes. */
public final class SessionIo implements AutoCloseable {
    public static final int MAX_LISTING_RESULTS=1000;
    public record FileEntry(String path, String name, boolean directory) {}
    public record Listing(List<FileEntry> entries, boolean truncated) {
        public Listing { entries = List.copyOf(entries); }
    }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "betterlitematica-settings"); t.setDaemon(true); return t; });
    private final AtomicReference<String> error = new AtomicReference<>();
    private final ThreadPoolExecutor browser = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"betterlitematica-browser");t.setDaemon(true);return t;});
    private final Set<DirectoryQuery> queries=new HashSet<>();
    private boolean browserClosed;
    public <T> CompletableFuture<T> submit(Callable<T> action) {
        var future = new CompletableFuture<T>();
        try { Future<?> job=worker.submit(() -> { try { future.complete(action.call()); } catch (Exception e) { future.completeExceptionally(e); } });
            future.whenComplete((value,failure)->{if(future.isCancelled())job.cancel(true);}); }
        catch (RejectedExecutionException e) { future.completeExceptionally(new IOException("Settings IO queue is full or closed", e)); }
        return future;
    }
    public CompletableFuture<PlacementSession> read(Path file) { return submit(() -> PlacementStore.read(file)); }
    public void save(Path file, PlacementSession session) {
        submit(() -> { PlacementStore.write(file, session); return null; })
            .whenComplete((ignored, failure) -> { if (failure != null) error.set("Placement save failed: " + failure.getMessage()); });
    }
    public String takeError() { return error.getAndSet(null); }
    public CompletableFuture<Listing> list(Path root, String relative) {
        return list(root,relative,"");
    }
    /** Match before collecting: unrelated entries never consume the result limit or search domain.
     * Each query retains at most one directory cursor and 1000 results. Disk traversal is sliced,
     * independent of ordered session writes, and continues until EOF or a proven extra match. */
    public CompletableFuture<Listing> list(Path root,String relative,String query){
        if(query==null||query.length()>120)return CompletableFuture.failedFuture(new IOException("Search text is too long"));
        var task=new DirectoryQuery(root,relative,query.toLowerCase(Locale.ROOT));
        synchronized(queries){
            if(browserClosed||queries.size()>=8)return CompletableFuture.failedFuture(new IOException("Directory search queue is full or closed"));
            queries.add(task);
            try{browser.execute(task);}catch(RejectedExecutionException e){queries.remove(task);task.result.completeExceptionally(new IOException("Directory search queue is full or closed",e));}
        }
        return task.result;
    }
    private final class DirectoryQuery implements Runnable {
        private final Path root;private final String relative,query;
        private final CompletableFuture<Listing> result=new CompletableFuture<>();
        private final List<FileEntry> entries=new ArrayList<>();
        private Path canonicalRoot;private DirectoryStream<Path> stream;private Iterator<Path> cursor;
        DirectoryQuery(Path root,String relative,String query){this.root=root;this.relative=relative;this.query=query;}
        @Override public void run(){
            try{
                if(result.isCancelled()){finish(null,null);return;}
                if(cursor==null){
                    canonicalRoot=root.toRealPath();Path directory=root.resolve(relative).normalize().toRealPath();
                    if(!directory.startsWith(canonicalRoot))throw new IOException("Directory must stay inside schematics");
                    stream=Files.newDirectoryStream(directory);cursor=stream.iterator();
                }
                long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(4);
                for(int visited=0;visited<512&&System.nanoTime()<deadline;visited++){
                    if(result.isCancelled()){finish(null,null);return;}
                    if(!cursor.hasNext()){complete(false);return;}
                    Path file=cursor.next();String name=file.getFileName().toString(),lower=name.toLowerCase(Locale.ROOT);
                    if(!lower.contains(query)||Files.isSymbolicLink(file))continue;
                    try{
                        Path real=file.toRealPath();if(!real.startsWith(canonicalRoot))continue;
                        boolean folder=Files.isDirectory(real);
                        if(!folder&&!lower.endsWith(".litematic")&&!lower.endsWith(".schem")&&!lower.endsWith(".schematic")&&!lower.endsWith(".nbt"))continue;
                        if(entries.size()==MAX_LISTING_RESULTS){complete(true);return;}
                        entries.add(new FileEntry(canonicalRoot.relativize(real).toString().replace('\\','/'),name,folder));
                    }catch(NoSuchFileException ignored){/* Directory changed while its cursor was open. */}
                }
                browser.execute(this);
            }catch(Exception failure){finish(null,failure);}
        }
        private void complete(boolean truncated){
            entries.sort(Comparator.comparing(FileEntry::directory).reversed().thenComparing(FileEntry::name,String.CASE_INSENSITIVE_ORDER));
            finish(new Listing(entries,truncated),null);
        }
        private void finish(Listing listing,Exception failure){
            if(stream!=null)try{stream.close();}catch(IOException close){if(failure==null)failure=close;}finally{stream=null;}
            synchronized(queries){queries.remove(this);}
            entries.clear();
            if(failure!=null)result.completeExceptionally(failure);else if(listing!=null)result.complete(listing);
        }
    }
    @Override public void close() {
        synchronized(queries){browserClosed=true;for(var query:queries)query.result.cancel(false);}
        browser.shutdown();
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) error.set("Settings save is still pending at shutdown");if(!browser.awaitTermination(1,TimeUnit.SECONDS))error.compareAndSet(null,"Directory search is still stopping"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error.set("Settings shutdown interrupted"); }
    }
}
