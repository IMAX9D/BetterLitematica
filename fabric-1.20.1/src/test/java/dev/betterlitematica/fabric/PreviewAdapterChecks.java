package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.TemporarySources;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class PreviewAdapterChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static int run()throws Exception{
        checks=0;Path root=Files.createTempDirectory("bl-preview-adapter-");
        try(var previews=new FilePreviews(root.resolve("cache"))){
            Path source=root.resolve("source.litematic");var document=document();NbtWriter.writeNew(source,document,Cancellation.NEVER);
            String original=SchematicImporter.sha256(source,Cancellation.NEVER);
            var ref=new TemporarySources.Reference(source,root,false);
            var cold=finish(previews,previews.request(ref));
            check(cold.side()==256&&cold.views().size()==4,"File preview produces four fixed-size views");
            for(var view:cold.views())check(Arrays.stream(view).anyMatch(c->(c>>>24)!=0),"Each direction sees source geometry");
            check(previews.stats().generated()==1,"Cold file generated once");
            var warm=finish(previews,previews.request(ref));
            check(previews.stats().generated()==1&&previews.stats().cacheHits()==1,"Repeat selection reuses disk preview without geometry generation");
            for(int i=0;i<4;i++)check(Arrays.equals(cold.views().get(i),warm.views().get(i)),"Cached direction matches original image");
            check(original.equals(SchematicImporter.sha256(source,Cancellation.NEVER)),"Preview never alters original source");

            var exported=document();finish(previews,previews.attach(exported,Cancellation.NEVER));
            Path portable=root.resolve("portable.litematic");NbtWriter.writeNew(portable,exported,Cancellation.NEVER);long generated=previews.stats().generated();
            var attached=finish(previews,previews.request(new TemporarySources.Reference(portable,root,false)));
            check(attached.views().size()==4&&previews.stats().generated()==generated&&previews.stats().embeddedHits()==1,"Portable attached views reused on a new path");
            check(SchematicPreview.embedded(NbtReader.read(portable,SchematicImporter.SOURCE_LIMITS,Cancellation.NEVER),Cancellation.NEVER)!=null,"Saved NBT retains all embedded views");

            CompletableFuture<?>[] pending=new CompletableFuture<?>[32];
            for(int i=0;i<pending.length;i++){pending[i]=previews.request(ref);pending[i].cancel(true);}
            finish(previews,previews.request(ref));check(Arrays.stream(pending).allMatch(CompletableFuture::isDone),"Rapidly abandoned selections leave no waiting futures");
            check(previews.stats().tasks()==0,"Completed and cancelled preview work releases ownership");
            var settings=new InteractionOptions();check(settings.capturePreviews,"New capture includes preview by default");settings.capturePreviews=false;
            Path options=root.resolve("options.json");InteractionOptions.write(options,settings.snapshot());check(!InteractionOptions.read(options).capturePreviews,"Include-preview preference persists independently");

            var interactive=finish(previews,previews.interactive(ref));long readCount=previews.stats().sourceReads(),buildCount=previews.stats().modelBuilds();
            check(interactive.model()!=null&&buildCount==1,"Interactive preview prepares a bounded reusable model");
            finish(previews,previews.interactive(ref));
            check(previews.stats().modelHits()==1&&previews.stats().sourceReads()==readCount&&previews.stats().modelBuilds()==buildCount,"Warm interactive selection reads its model without reparsing the source");
            try(var restarted=new FilePreviews(root.resolve("cache"))){
                var loaded=finish(restarted,restarted.interactive(ref));
                check(restarted.stats().generated()==0&&restarted.stats().sourceReads()==0&&restarted.stats().modelHits()==1,"A new service reuses persisted images and geometry");
                try(var orbit=restarted.orbit(loaded.model())){
                    long finalSequence=0;for(int i=0;i<80;i++)finalSequence=orbit.request(i*.03,.27,true);
                    finalSequence=orbit.request(-.9,-.35,false);
                    var frame=frame(orbit,finalSequence);
                    check(frame.sequence()==finalSequence&&!frame.dragging(),"Rapid dragging settles at the last released angle");
                    check(Arrays.equals(frame.pixels(),SchematicPreview.renderOrbit(loaded.model(),-.9,-.35,false,Cancellation.NEVER)),"Published frame matches the final camera, not a queued stale angle");
                    check(restarted.stats().sourceReads()==0,"Rotation never reopens source geometry");
                }
                check(restarted.stats().orbits()==0,"Closed preview releases its orbit ownership");
                var abandoned=restarted.orbit(loaded.model());abandoned.request(1,.4,true);abandoned.close();
                check(abandoned.poll()==null&&restarted.stats().orbits()==0,"Closed sessions cannot publish a late frame");
            }
            Path modelFile;try(var files=Files.list(root.resolve("cache/preview-models"))){modelFile=files.filter(p->p.toString().endsWith(".blpo")).findFirst().orElseThrow();}
            byte[] packed=Files.readAllBytes(modelFile);packed[packed.length-8]^=1;Files.write(modelFile,packed);
            finish(previews,previews.interactive(ref));check(previews.stats().modelBuilds()==buildCount+1,"Corrupt compressed model is rebuilt from source");
            check(original.equals(SchematicImporter.sha256(source,Cancellation.NEVER)),"Interactive rotation and repair preserve source bytes");
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        return checks;
    }
    private static Map<String,Object> document(){
        var region=new Region("test",new Vec3i(-3,2,-4),new Vec3i(3,3,2));int[] blocks=new int[18];blocks[0]=1;blocks[2]=2;blocks[17]=3;
        var palette=List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:red_concrete"),BlockStateSpec.parse("minecraft:lime_concrete"),BlockStateSpec.parse("minecraft:blue_concrete"));
        return LitematicExport.create("preview","test",3465,Vec3i.ZERO,List.of(new LitematicExport.Capture(region,palette,blocks,List.of(),List.of(),List.of(),List.of())));
    }
    private static <T>T finish(FilePreviews previews,CompletableFuture<T> result)throws Exception{
        long deadline=System.nanoTime()+15_000_000_000L;
        while(!result.isDone()&&System.nanoTime()<deadline){previews.tick();Thread.sleep(2);}
        check(result.isDone(),"Background preview terminates while the main thread only pumps bounded color slices");return result.get(1,TimeUnit.SECONDS);
    }
    private static FilePreviews.Orbit.Frame frame(FilePreviews.Orbit orbit,long sequence)throws Exception{
        long deadline=System.nanoTime()+5_000_000_000L;FilePreviews.Orbit.Frame latest=null;
        while(System.nanoTime()<deadline){var next=orbit.poll();if(next!=null){latest=next;if(next.sequence()==sequence)return next;}Thread.sleep(2);}
        throw new AssertionError("Orbit never produced final sequence "+sequence+", latest="+(latest==null?0:latest.sequence()));
    }
}
