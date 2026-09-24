package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Complete-file draft/restore/export check. Outputs go to a new validation directory. */
public final class LargeDraftCheck {
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),directory=Path.of(args[1]);Files.createDirectories(directory);
        String hash=SchematicImporter.sha256(source,Cancellation.NEVER);long started=System.nanoTime();
        var imported=SchematicImporter.importFile(source,directory.resolve("cache"),Cancellation.NEVER,p->{});
        long[] expected;BlueprintMetadata metadata;SectionKey changed=null;int cell=-1;SchematicEdits edits;
        try(var cache=BlueprintCache.open(imported.path())){
            metadata=cache.metadata();edits=new SchematicEdits(metadata,cache.copyBlockStateCounts());
            outer:for(var key:cache.index().keySet()){var section=cache.read(key);for(int i=0;i<4096;i++){int id=section.globalId(i);if(!metadata.palette().get(id).isAir()){changed=key;cell=i;edits.apply(List.of(new SchematicEdits.Request(key,i,id,BlockStateSpec.AIR,false,false,false)));break outer;}}}
            if(changed==null)throw new AssertionError("Fixture has no blocks");
            Path checkpoint=directory.resolve("large.draft");var placement=new Placement(UUID.randomUUID(),"validation",source.getFileName().toString(),new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
            DraftStore.write(checkpoint,new DraftStore.Draft("validation",placement,edits.checkpoint()));var restored=DraftStore.read(checkpoint);
            edits=SchematicEdits.restore(restored.checkpoint(),metadata,cache.copyBlockStateCounts());expected=edits.counts();
        }
        Path output=directory.resolve("edited.litematic");SchematicPatchIO.write(source,output,edits.snapshot(),Cancellation.NEVER);
        var next=SchematicImporter.importFile(output,directory.resolve("cache"),Cancellation.NEVER,p->{});
        try(var cache=BlueprintCache.open(next.path())){
            var counts=cache.copyBlockStateCounts();var actual=new HashMap<BlockStateSpec,Long>();for(int i=0;i<counts.length;i++)actual.merge(cache.metadata().palette().get(i),counts[i],Long::sum);
            for(int i=0;i<expected.length;i++)if(actual.getOrDefault(metadata.palette().get(i),0L)!=expected[i])throw new AssertionError("Complete histogram changed outside the edited cell: "+metadata.palette().get(i));
            var section=cache.index().containsKey(changed)?cache.read(changed):null;if(section!=null&&!cache.metadata().palette().get(section.globalId(cell)).isAir())throw new AssertionError("Edited cell not saved");
        }
        if(!hash.equals(SchematicImporter.sha256(source,Cancellation.NEVER)))throw new AssertionError("Original source changed");
        System.out.printf(Locale.ROOT,"LARGE DRAFT PASS cells=%d changed=1 seconds=%.3f maxHeapMiB=%d outputBytes=%d%n",Arrays.stream(expected).sum(),(System.nanoTime()-started)/1e9,Runtime.getRuntime().maxMemory()/(1<<20),Files.size(output));
        System.out.println("fullHistogram=PASS checkpointUndoRedo=retained sourceSha256="+hash);
    }
}
