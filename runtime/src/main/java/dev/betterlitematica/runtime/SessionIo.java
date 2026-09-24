package dev.betterlitematica.runtime;

import dev.betterlitematica.core.PlacementSession;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Serial disk operations preserve save-before-restore ordering across world changes. */
public final class SessionIo implements AutoCloseable {
    public record FileEntry(String path, String name, boolean directory) {}
    public record Listing(List<FileEntry> entries, boolean truncated) {
        public Listing { entries = List.copyOf(entries); }
    }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "betterlitematica-settings"); t.setDaemon(true); return t; });
    private final AtomicReference<String> error = new AtomicReference<>();
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
        return submit(() -> {
            Path canonicalRoot = root.toRealPath(); Path directory = root.resolve(relative).normalize().toRealPath();
            if (!directory.startsWith(canonicalRoot)) throw new IOException("Directory must stay inside schematics");
            List<FileEntry> entries = new ArrayList<>(); boolean truncated = false; int visited = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (++visited > 10000 || entries.size() >= 1000 || System.nanoTime() > deadline) { truncated = true; break; }
                    if (Files.isSymbolicLink(file)) continue;
                    Path real = file.toRealPath(); if (!real.startsWith(canonicalRoot)) continue;
                    boolean folder = Files.isDirectory(real); String name = file.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (folder || lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic") || lower.endsWith(".nbt"))
                        entries.add(new FileEntry(canonicalRoot.relativize(real).toString().replace('\\', '/'), name, folder));
                }
            }
            entries.sort(Comparator.comparing(FileEntry::directory).reversed().thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER));
            return new Listing(entries, truncated);
        });
    }
    @Override public void close() {
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) error.set("Settings save is still pending at shutdown"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error.set("Settings shutdown interrupted"); }
    }
}
