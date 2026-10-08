package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.BlueprintCache;
import dev.betterlitematica.runtime.SpatialIndex;
import java.nio.file.*;
import java.util.*;

/** Read-only, matched CPU traversal comparison. Excludes model baking, GL, frame timing and disk IO. */
public final class RenderPreparationBenchmark {
    private record Section(Vec3i base,PackedSection data){}
    private record Result(long count,long checksum){}
    private static volatile long sink;
    private static Result scan(List<Section> sections,List<BlockStateSpec> palette,PlacementTransform transform,LayerRange layer,boolean sparse){
        long count=0,hash=0;
        for(var section:sections){
            PackedSection data=section.data();
            for(int i=sparse?data.nextNonZero(0):0;i<PackedSection.VOLUME;i=sparse?data.nextNonZero(i+1):i+1){
                var spec=palette.get(data.globalId(i));
                if(sparse&&spec.isAir())continue;
                var local=section.base().add(new Vec3i(i&15,i>>>8,(i>>>4)&15));var world=transform.apply(local);
                if(spec.isAir()||!layer.contains(world))continue;
                count++;hash=hash*31+world.x();hash=hash*31+world.y();hash=hash*31+world.z();hash=hash*31+data.globalId(i);
            }
        }
        sink=hash;return new Result(count,hash);
    }
    private static double median(long[] values){var sorted=values.clone();Arrays.sort(sorted);return sorted[sorted.length/2]/1e6;}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected a read-only .bpc cache path");
        Path path=Path.of(args[0]);
        try(var cache=BlueprintCache.open(path)){
            var region=cache.metadata().regions().get(0);
            var camera=region.min().add(new Vec3i(region.size().x()/2,region.size().y()/2,region.size().z()/2));
            var keys=new SpatialIndex(cache,Cancellation.NEVER).nearest(camera,22*16,32768);
            var sections=new ArrayList<Section>();long logicalBytes=0;
            for(var key:keys){var data=cache.read(key);logicalBytes+=data.estimatedBytes();sections.add(new Section(cache.metadata().regions().get(key.region()).sectionOrigin(key),data));}
            var transform=new PlacementTransform(new Vec3i(52,55,193),1,true,false);var palette=cache.metadata().palette();
            for(int i=0;i<4;i++){scan(sections,palette,transform,LayerRange.ALL,false);scan(sections,palette,transform,LayerRange.ALL,true);}
            long[] baseline=new long[9],optimized=new long[9];Result reference=null;
            for(int round=0;round<9;round++){
                for(int step=0;step<2;step++){
                    boolean sparse=((round+step)&1)!=0;long start=System.nanoTime();var result=scan(sections,palette,transform,LayerRange.ALL,sparse);long elapsed=System.nanoTime()-start;
                    if(reference==null)reference=result;else if(!reference.equals(result))throw new AssertionError("Traversal parity failed");
                    (sparse?optimized:baseline)[round]=elapsed;
                }
            }
            double before=median(baseline),after=median(optimized);
            System.out.println("source_sha256="+cache.metadata().sourceSha256());
            System.out.printf(Locale.ROOT,"radius=352 camera=%s sections=%d decoded_logical_MiB=%.2f%n",camera,sections.size(),logicalBytes/1048576.0);
            System.out.printf(Locale.ROOT,"dense_cell_visits=%d non_air_blocks=%d checksum=%d parity=PASS%n",sections.size()*4096L,reference.count(),reference.checksum());
            System.out.printf(Locale.ROOT,"legacy_median_ms=%.3f sparse_median_ms=%.3f cpu_traversal_speedup=%.2fx%n",before,after,before/after);
            System.out.println("legacy_trials_ns="+Arrays.toString(baseline));System.out.println("sparse_trials_ns="+Arrays.toString(optimized));
            System.out.println("CPU traversal only; decoded data preloaded for both paths. Not a game/FPS/GPU/loading benchmark.");
        }
    }
}
