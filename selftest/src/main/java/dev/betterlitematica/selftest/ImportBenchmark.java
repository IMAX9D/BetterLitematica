package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;

/** Synthetic IO benchmark only. No Minecraft, no render frames, no GPU, no FPS assertion. */
public final class ImportBenchmark {
    public static void main(String[] args)throws Exception{
        int sx=256,sy=128,sz=256;Path directory=Files.createTempDirectory("betterlitematica-benchmark-");
        try{
            Path source=directory.resolve("synthetic.litematic");
            Fixtures.write(source,Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,sx,sy,sz,i->{int x=i%sx,z=(i/sx)%sz,y=i/(sx*sz);return y%16==0||x%32==0||z%32==0?((x+y+z)%7==0?2:1):0;})),"8 Mi-cell synthetic benchmark"));
            System.gc();for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
            long first=System.nanoTime();var cold=SchematicImporter.importFile(source,directory.resolve("cache"),Cancellation.NEVER,p->{});long coldNanos=System.nanoTime()-first;
            long start=System.nanoTime();var warm=SchematicImporter.importFile(source,directory.resolve("cache"),Cancellation.NEVER,p->{});long warmNanos=System.nanoTime()-start;
            int sections,decoded;long decodeNanos,cpuBytes=0,spatialNanos,nonAir=0;
            try(var cache=BlueprintCache.open(cold.path())){
                sections=cache.index().size();var counts=cache.copyBlockStateCounts();for(int i=0;i<counts.length;i++)if(!cache.metadata().palette().get(i).isAir())nonAir+=counts[i];
                start=System.nanoTime();SpatialIndex index=new SpatialIndex(cache,Cancellation.NEVER);spatialNanos=System.nanoTime()-start;
                List<SectionKey> selection=index.nearest(new Vec3i(128,64,128),96,96);decoded=selection.size();start=System.nanoTime();
                for(SectionKey key:selection)cpuBytes+=cache.read(key).estimatedBytes();decodeNanos=System.nanoTime()-start;
            }
            long peakHeapPools=0;for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peakHeapPools+=pool.getPeakUsage().getUsed();
            System.out.println("SYNTHETIC IMPORT BENCHMARK - NOT A GAME FPS BENCHMARK");
            System.out.println("java="+System.getProperty("java.runtime.version")+", os="+System.getProperty("os.name")+", processors="+Runtime.getRuntime().availableProcessors());
            System.out.printf(Locale.ROOT,"cells=%d, non_air=%d, source_bytes=%d, cache_bytes=%d, stored_sections=%d%n",(long)sx*sy*sz,nonAir,Files.size(source),Files.size(cold.path()),sections);
            System.out.printf(Locale.ROOT,"cold_import_ms=%.3f, warm_hash_and_cache_validation_ms=%.3f, warm_cache_hit=%s%n",coldNanos/1e6,warmNanos/1e6,warm.cacheHit());
            System.out.printf(Locale.ROOT,"spatial_index_ms=%.3f, decoded_sections=%d, decode_ms=%.3f, decoded_logical_bytes=%d%n",spatialNanos/1e6,decoded,decodeNanos/1e6,cpuBytes);
            System.out.printf(Locale.ROOT,"sum_of_heap_pool_peaks_mib=%.3f (not simultaneous live heap / not process RSS)%n",peakHeapPools/1048576.0);
        }finally{try(var walk=Files.walk(directory)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
