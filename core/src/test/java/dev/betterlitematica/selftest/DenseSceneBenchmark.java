package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Ten million non-air cells, real import/decode and the renderer's pinned-neighbor addressing. No GPU/FPS claim. */
public final class DenseSceneBenchmark {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("bl-dense-10m-");int sx=250,sy=160,sz=250;
        try{
            Path source=root.resolve("dense.litematic");
            Fixtures.write(source,Fixtures.litematic(Map.of("solid",Fixtures.region(0,0,0,sx,sy,sz,i->1)),"10 million non-air blocks"));
            long start=System.nanoTime();var imported=SchematicImporter.importFile(source,root.resolve("cache"),Cancellation.NEVER,p->{});long importNanos=System.nanoTime()-start;
            try(var cache=BlueprintCache.open(imported.path())){
                var data=new HashMap<SectionKey,PackedSection>();for(var key:cache.index().keySet())data.put(key,cache.read(key));
                long cells=0,faces=0,oldFaces=0;start=System.nanoTime();
                for(var entry:data.entrySet()){
                    var k=entry.getKey();var halo=new SectionNeighborhood(entry.getValue(),get(data,k,-1,0,0),get(data,k,1,0,0),get(data,k,0,-1,0),get(data,k,0,1,0),get(data,k,0,0,-1),get(data,k,0,0,1));
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                        if(entry.getValue().globalId(x,y,z)==0)continue;cells++;
                        if(halo.globalId(x-1,y,z)==0)faces++;if(halo.globalId(x+1,y,z)==0)faces++;
                        if(halo.globalId(x,y-1,z)==0)faces++;if(halo.globalId(x,y+1,z)==0)faces++;
                        if(halo.globalId(x,y,z-1)==0)faces++;if(halo.globalId(x,y,z+1)==0)faces++;
                        if(x==0||entry.getValue().globalId(x-1,y,z)==0)oldFaces++;if(x==15||entry.getValue().globalId(x+1,y,z)==0)oldFaces++;
                        if(y==0||entry.getValue().globalId(x,y-1,z)==0)oldFaces++;if(y==15||entry.getValue().globalId(x,y+1,z)==0)oldFaces++;
                        if(z==0||entry.getValue().globalId(x,y,z-1)==0)oldFaces++;if(z==15||entry.getValue().globalId(x,y,z+1)==0)oldFaces++;
                    }
                }
                long nanos=System.nanoTime()-start,expected=2L*(sx*sy+sx*sz+sy*sz);
                if(cells!=10_000_000||faces!=expected)throw new AssertionError("Dense surface mismatch: "+cells+" / "+faces);
                start=System.nanoTime();var index=new SpatialIndex(cache,Cancellation.NEVER);for(int i=0;i<100;i++)if(index.nearest(new Vec3i(i+75,80,125),192,512).size()>512)throw new AssertionError("Candidate bound");
                System.out.printf(Locale.ROOT,"DENSE RESULT cells=%d sections=%d old_faces=%d new_faces=%d reduction=%.2f%%%n",cells,data.size(),oldFaces,faces,100.0*(oldFaces-faces)/oldFaces);
                System.out.printf(Locale.ROOT,"import_ms=%.2f neighbor_scan_ms=%.2f index_plus_100_queries_ms=%.2f%n",importNanos/1e6,nanos/1e6,(System.nanoTime()-start)/1e6);
                System.out.println("Synthetic non-air solid; not the user's scene, not a renderer/GPU/FPS benchmark.");
            }
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static PackedSection get(Map<SectionKey,PackedSection> map,SectionKey key,int x,int y,int z){int nx=key.x()+x,ny=key.y()+y,nz=key.z()+z;return nx<0||ny<0||nz<0?null:map.get(new SectionKey(key.region(),nx,ny,nz));}
}
