package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Read-only original-file import validation. Derived cache is the only output. */
public final class FileImportCheck {
    private static String hash(Path p)throws Exception{var md=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))>=0)md.update(bytes,0,n);}return HexFormat.of().formatHex(md.digest());}
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),directory=Path.of(args[1]);String before=hash(source);long start=System.nanoTime();
        var last=new long[]{0};
        var result=SchematicImporter.importFile(source,directory,Cancellation.NEVER,p->{long now=System.nanoTime();if(now-last[0]>5_000_000_000L){System.out.println(p.phase()+" "+p.completed()+"/"+p.total());last[0]=now;}});
        try(var cache=BlueprintCache.open(result.path())){
            long total=0,nonAir=0;var counts=cache.copyBlockStateCounts();for(int i=0;i<counts.length;i++){total+=counts[i];if(!cache.metadata().palette().get(i).isAir())nonAir+=counts[i];}
            long[] decodedCounts=new long[counts.length];
            for(var key:cache.index().keySet()){var section=cache.read(key);for(int i=0;i<PackedSection.VOLUME;i++){int id=section.globalId(i);if(!cache.metadata().palette().get(id).isAir())decodedCounts[id]++;}}
            for(int i=0;i<counts.length;i++)if(!cache.metadata().palette().get(i).isAir()&&decodedCounts[i]!=counts[i])throw new AssertionError("Decoded count mismatch: "+i);
            System.out.println("allSectionChecksumsAndNonAirCounts=PASS");
            var index=new SpatialIndex(cache,Cancellation.NEVER);int decoded=0;for(var key:index.nearest(cache.metadata().regions().get(0).min(),192,512)){cache.read(key);decoded++;}
            System.out.println("cache="+result.path()+" hit="+result.cacheHit());System.out.println("cells="+total+" nonAir="+nonAir+" sections="+cache.index().size()+" palette="+counts.length+" nearbyDecoded="+decoded);
        }
        if(!before.equals(hash(source)))throw new AssertionError("Source changed");
        System.out.println("sourceSha256="+before);System.out.printf(Locale.ROOT,"elapsedSeconds=%.3f maxHeapMiB=%d%n",(System.nanoTime()-start)/1e9,Runtime.getRuntime().maxMemory()/(1<<20));
    }
}
