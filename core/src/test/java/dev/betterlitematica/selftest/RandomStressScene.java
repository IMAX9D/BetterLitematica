package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Reproducible adversarial scene: exactly ten million randomly positioned blocks at 20% occupancy. */
public final class RandomStressScene {
    private static int hash(int n){n^=0x41973ab1;n^=n>>>16;n*=0x7feb352d;n^=n>>>15;n*=0x846ca68b;n^=n>>>16;return n;}
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]).toAbsolutePath(),cacheDir=Path.of(args[1]).toAbsolutePath();
        List<BlockStateSpec> states=new ArrayList<>();states.add(BlockStateSpec.AIR);
        for(String s:List.of("stone","cobblestone","deepslate","glass","white_stained_glass","red_stained_glass","ice","oak_leaves[persistent=true]","birch_leaves[persistent=true]","oak_log[axis=x]","oak_log[axis=y]","oak_log[axis=z]","white_concrete","black_concrete","gold_block","iron_block","oak_planks","bricks","sandstone","sea_lantern","oak_slab[type=top]","oak_slab[type=bottom]","oak_slab[type=double]"))states.add(BlockStateSpec.parse(s));
        for(String facing:List.of("north","south","east","west"))for(String half:List.of("top","bottom"))states.add(BlockStateSpec.parse("oak_stairs[facing="+facing+",half="+half+"]"));
        int cells=50_000_000,sx=500,sy=200,sz=500,bits=32-Integer.numberOfLeadingZeros(states.size()-1);
        if(!Files.exists(output)){
            List<Map<String,Object>> palette=new ArrayList<>();for(var state:states){Map<String,Object> p=new LinkedHashMap<>();p.put("Name",state.name());if(!state.properties().isEmpty())p.put("Properties",state.properties());palette.add(p);}
            // One randomly selected slot in each five-cell group; exactly 20%, with independently hashed material.
            var packed=PackedBits.generate(bits,cells,i->i%5==Integer.remainderUnsigned(hash(i/5),5)?1+Integer.remainderUnsigned(hash(i^0x175ac93),states.size()-1):0);
            Map<String,Object> region=new LinkedHashMap<>();region.put("Position",Fixtures.xyz(0,0,0));region.put("Size",Fixtures.xyz(sx,sy,sz));region.put("BlockStatePalette",palette);region.put("BlockStates",packed.copyWords());
            Map<String,Object> root=Fixtures.litematic(Map.of("random20",region),"random 10M blocks 20 percent seed 41973ab1");
            root.put("Metadata",Map.of("Name","random-10m-density20","Author","BetterLitematica independent stress generator","TotalBlocks",10_000_000,"TotalVolume",cells,"EnclosingSize",Fixtures.xyz(sx,sy,sz)));
            NbtWriter.writeNew(output,root,Cancellation.NEVER);
        }
        long start=System.nanoTime();var imported=SchematicImporter.importFile(output,cacheDir,Cancellation.NEVER,p->{});long importNanos=System.nanoTime()-start;
        try(var cache=BlueprintCache.open(imported.path())){
            long air=0,nonAir=0;long[] counts=cache.copyBlockStateCounts();for(int i=0;i<counts.length;i++){if(cache.metadata().palette().get(i).isAir())air+=counts[i];else nonAir+=counts[i];}
            if(nonAir!=10_000_000||air!=40_000_000)throw new AssertionError("Stress density mismatch");
            var index=new SpatialIndex(cache,Cancellation.NEVER);var selected=index.nearest(new Vec3i(250,100,250),192,512);
            long selectedCells=0,faceCandidates=0,bytes=0;start=System.nanoTime();
            for(var key:selected){var section=cache.read(key);bytes+=section.estimatedBytes();for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(section.globalId(x,y,z)!=0){selectedCells++;if(x==0||section.globalId(x-1,y,z)==0)faceCandidates++;if(x==15||section.globalId(x+1,y,z)==0)faceCandidates++;if(y==0||section.globalId(x,y-1,z)==0)faceCandidates++;if(y==15||section.globalId(x,y+1,z)==0)faceCandidates++;if(z==0||section.globalId(x,y,z-1)==0)faceCandidates++;if(z==15||section.globalId(x,y,z+1)==0)faceCandidates++;}}
            System.out.printf(Locale.ROOT,"RANDOM STRESS cells=%d non_air=%d density=20%% dimensions=500x200x500 palette=%d sections=%d source_bytes=%d cache_bytes=%d%n",air+nonAir,nonAir,counts.length,cache.index().size(),Files.size(output),Files.size(imported.path()));
            System.out.printf(Locale.ROOT,"import_ms=%.2f cache_hit=%s sample_sections=%d sample_non_air=%d sample_face_candidates=%d sample_decode_bytes=%d sample_decode_scan_ms=%.2f%n",importNanos/1e6,imported.cacheHit(),selected.size(),selectedCells,faceCandidates,bytes,(System.nanoTime()-start)/1e6);
            System.out.println("No GPU or game FPS measurement. Face candidates are a conservative section-local occupancy count, not actual baked quads.");
            System.out.println("fixture="+output);System.out.println("sha256="+SchematicImporter.sha256(output,Cancellation.NEVER));
        }
    }
}
