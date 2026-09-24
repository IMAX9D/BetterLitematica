package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone checkpoint/lifecycle checks; invoke run(tempDirectory) from another test runner. */
public final class DraftRecoveryChecks {
    private DraftRecoveryChecks(){}
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Class<? extends Throwable> type,Checked task)throws Exception{try{task.run();}catch(Exception e){if(type.isInstance(e))return;throw e;}throw new AssertionError("Expected "+type.getName());}
    public static int run(Path directory)throws Exception{
        Files.createDirectories(directory);int checks=0;var stone=BlockStateSpec.parse("minecraft:stone");var glass=BlockStateSpec.parse("minecraft:glass");
        var metadata=new BlueprintMetadata("draft",3465,"a".repeat(64),List.of(BlockStateSpec.AIR,stone),List.of(new Region("negative",new Vec3i(-5,2,-4),new Vec3i(16,1,1),new Vec3i(10,2,-4))),List.of());long[] counts={14,2};var key=new SectionKey(0,0,0,0);
        var edits=new SchematicEdits(metadata,counts);edits.apply(List.of(new SchematicEdits.Request(key,0,1,glass,false,false,false)));edits.apply(List.of(new SchematicEdits.Request(key,1,1,BlockStateSpec.AIR,false,false,false)));edits.undo();
        var placement=new Placement(UUID.randomUUID(),"negative", "source.litematic",new PlacementTransform(new Vec3i(31,70,-22),3,true,false),true,true,0.72f);
        var draft=new DraftStore.Draft("world-a",placement,edits.checkpoint());Path path=directory.resolve("roundtrip.draft");DraftStore.write(path,draft);var read=DraftStore.read(path);
        check(edits.checkpoint()==draft.checkpoint()&&edits.fork().checkpoint()==draft.checkpoint(),"Stable checkpoints and forks share immutable recovery image");check(edits.checkpoint().estimatedBytes()==edits.checkpoint().estimatedBytes(),"Byte estimate is stable");checks+=2;
        check(read!=null&&read.placement().equals(placement)&&read.worldKey().equals("world-a"),"Full placement and world survive");checks++;
        var restored=SchematicEdits.restore(read.checkpoint(),metadata,counts);check(restored.snapshot().sections().equals(edits.snapshot().sections())&&Arrays.equals(restored.counts(),edits.counts()),"Final cells, attachment flags, counts survive");checks++;
        check(restored.canUndo()&&restored.canRedo(),"Both history directions survive");restored.redo();check(restored.sample(key,1,1)==0,"Redo deleted cell");restored.undo();restored.undo();check(!restored.dirty()&&restored.counts()[0]==14&&restored.counts()[1]==2&&restored.counts()[2]==0,"Undo restores base and attachments");checks+=3;
        var candidate=edits.fork();candidate.redo();check(edits.sample(key,1,1)==1&&candidate.sample(key,1,1)==0,"Candidate admission cannot modify live draft");checks++;
        byte[] encoded=DraftStore.encode(draft);encoded[encoded.length/2]^=1;rejects(IOException.class,()->DraftStore.decode(encoded));checks++;
        var wrongSource=new BlueprintMetadata(metadata.name(),metadata.dataVersion(),"b".repeat(64),metadata.palette(),metadata.regions(),List.of());rejects(IllegalArgumentException.class,()->SchematicEdits.restore(draft.checkpoint(),wrongSource,counts));checks++;
        var badAction=new SchematicEdits.Action(List.of(new SchematicEdits.Delta(new SchematicEdits.Address(key,0),null,new SchematicEdits.Patch(1,0,false,false,false))));
        var bad=new SchematicEdits.Checkpoint(draft.checkpoint().metadata(),2,draft.checkpoint().sections(),10,List.of(badAction),List.of());rejects(IllegalArgumentException.class,()->SchematicEdits.validateCheckpoint(bad));checks++;
        var badAddress=new SchematicEdits.Checkpoint(metadata,2,Map.of(key,Map.of(20,new SchematicEdits.Patch(1,0,false,false,false))),1,List.of(),List.of());rejects(IllegalArgumentException.class,()->SchematicEdits.validateCheckpoint(badAddress));checks++;
        var impossible=new SchematicEdits.Checkpoint(metadata,2,Map.of(),1,List.of(),List.of(new SchematicEdits.Action(List.of(new SchematicEdits.Delta(new SchematicEdits.Address(key,0),null,new SchematicEdits.Patch(1,0,false,false,false))))));rejects(IllegalArgumentException.class,()->SchematicEdits.restore(impossible,metadata,new long[]{16,0}));checks++;
        Path other=directory.resolve("other.draft");DraftStore.write(other,draft);check(DraftStore.read(other)!=null,"Independent destination");DraftStore.clear(other);check(DraftStore.read(other)==null&&DraftStore.read(path)!=null,"Clear keeps other worlds");checks+=2;
        try(var writer=new DraftWriter()){
            check(writer.reserve(path)&&writer.canWrite(path,draft),"Admission available before publishing candidate");writer.write(path,draft);var saved=writer.read(path).get(5,TimeUnit.SECONDS);check(saved!=null&&saved.placement().equals(placement),"Read waits for accepted write");
            Path archived=writer.archive(path).get(5,TimeUnit.SECONDS);check(archived!=null&&!Files.exists(path)&&DraftStore.read(archived)!=null,"Archive preserves recovery material");checks+=3;
        }
        try(var writer=new DraftWriter(2,new MemoryBackend())){check(writer.reserve(path)&&writer.reserve(other)&&!writer.reserve(directory.resolve("third")),"Path capacity rejects before an edit");writer.release(other);check(writer.reserve(directory.resolve("third")),"Unused reservation can be released");checks+=2;}
        // Attach callbacks while the prerequisite write is blocked. CompletableFuture executes
        // them on the worker at completion, before pump's finally block; no timing race is needed.
        for(String operation:List.of("flush","read","archive")){
            var gated=new MemoryBackend();var started=new CountDownLatch(1);gated.started=started;gated.release=new CountDownLatch(1);
            try(var writer=new DraftWriter(1,gated)){
                writer.write(path,draft);check(started.await(3,TimeUnit.SECONDS),"Gated "+operation+" prerequisite starts");
                CompletableFuture<?> barrier=switch(operation){case "flush"->writer.flush(path);case "read"->writer.read(path);case "archive"->writer.archive(path);default->throw new AssertionError(operation);};
                var observed=barrier.thenRun(()->{
                    check(!writer.status(path).pending(),operation+" callback observes committed revision");
                    check(writer.retainedBytes()==0,operation+" callback observes released image");
                    check(writer.reserve(other),operation+" callback can reuse the completed path slot");writer.release(other);
                });
                gated.release.countDown();observed.get(5,TimeUnit.SECONDS);checks+=3;
            }finally{gated.release.countDown();}
        }
        var storage=new MemoryBackend();storage.fail.set(true);
        try(var writer=new DraftWriter(2,storage)){
            writer.write(path,draft);rejects(ExecutionException.class,()->writer.flush(path).get(5,TimeUnit.SECONDS));var status=writer.status(path);check(status.pending()&&!status.error().isEmpty()&&writer.retainedBytes()>0,"Failed latest image remains owned");
            rejects(ExecutionException.class,()->writer.read(path).get(5,TimeUnit.SECONDS));check(storage.reads.get()==0,"Failed write cannot expose stale disk data");
            storage.fail.set(false);writer.retry(path);writer.flush(path).get(5,TimeUnit.SECONDS);check(!writer.status(path).pending()&&writer.retainedBytes()==0,"Retry commits and releases image");checks+=5;
            storage.fail.set(true);rejects(ExecutionException.class,()->writer.archive(path).get(5,TimeUnit.SECONDS));check(storage.files.containsKey(path),"Failed archive preserves the original recovery file");storage.fail.set(false);check(writer.archive(path).get(5,TimeUnit.SECONDS)!=null,"Failed archive can be retried explicitly");checks+=3;
        }
        var ordered=new MemoryBackend();var firstStarted=new CountDownLatch(1);ordered.started=firstStarted;ordered.release=new CountDownLatch(1);var later=new DraftStore.Draft("world-b",placement,candidate.checkpoint());
        try(var writer=new DraftWriter(2,ordered)){
            writer.write(path,draft);check(firstStarted.await(3,TimeUnit.SECONDS),"First write starts");writer.write(path,later);var archive=writer.archive(path);writer.clear(path);var after=writer.read(path);ordered.release.countDown();
            Path backup=archive.get(5,TimeUnit.SECONDS);check(ordered.files.get(backup).worldKey().equals("world-b"),"Later clear cannot coalesce away archive's required image");check(after.get(5,TimeUnit.SECONDS)==null,"Clear is ordered after archive");checks+=3;
        }finally{ordered.release.countDown();}
        return checks;
    }
    private static final class MemoryBackend implements DraftWriter.Backend {
        final Map<Path,DraftStore.Draft> files=new ConcurrentHashMap<>();final AtomicBoolean fail=new AtomicBoolean();final AtomicInteger reads=new AtomicInteger();volatile CountDownLatch started,release;
        public void write(Path path,DraftStore.Draft value)throws IOException{var latch=started;if(latch!=null){started=null;latch.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new IOException("Test gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}if(fail.get())throw new IOException("Injected storage failure");files.put(path,value);}
        public void clear(Path path)throws IOException{if(fail.get())throw new IOException("Injected clear failure");files.remove(path);}
        public DraftStore.Draft read(Path path){reads.incrementAndGet();return files.get(path);}
        public Path archive(Path path)throws IOException{if(fail.get())throw new IOException("Injected archive failure");var value=files.get(path);if(value==null)return null;Path backup=path.resolveSibling(path.getFileName()+".recovery-test");files.put(backup,value);files.remove(path);return backup;}
    }
    public static void main(String[] args)throws Exception{Path temp=Files.createTempDirectory("betterlitematica-draft-check-");try{System.out.println("PASS DraftRecoveryChecks: "+run(temp)+" checks");}finally{try(var files=Files.walk(temp)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}}
}
