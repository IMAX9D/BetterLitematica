package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.BlueprintCache;
import dev.betterlitematica.runtime.SectionStreamer;
import dev.betterlitematica.runtime.WeightedLru;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Blocks;

/** Real source caches and production scene composition, without GPU/world construction. */
public final class PrinterCompositionChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Object allocate(Class<?> type)throws Exception{var c=Class.forName("sun.misc.Unsafe");var f=c.getDeclaredField("theUnsafe");f.setAccessible(true);return c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
    private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static final class Source implements AutoCloseable {
        final Path file;final SectionStreamer stream;final ProjectionRenderer1201 renderer;final BlueprintMetadata metadata;final Placement placement;final int state;
        Source(Path directory,int state,Placement placement,int regions,boolean cached)throws Exception{
            this.placement=placement;this.state=state;file=directory.resolve(UUID.randomUUID()+".bpc");var list=new ArrayList<Region>();for(int i=0;i<regions;i++)list.add(new Region("r"+i,Vec3i.ZERO,new Vec3i(1,1,1)));
            metadata=new BlueprintMetadata("test",3465,"0".repeat(64),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:stone"),BlockStateSpec.parse("minecraft:glass"),BlockStateSpec.parse("minecraft:structure_void"),BlockStateSpec.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")),list,List.of());
            try(var writer=new BlueprintCache.Writer(file,metadata)){for(int r=0;r<regions;r++){writer.count(r,state);int[] cells=new int[4096];cells[0]=state;writer.add(new SectionKey(r,0,0,0),PackedSection.fromGlobalIds(cells));}writer.commit(Cancellation.THREAD);}
            stream=new SectionStreamer(BlueprintCache.open(file),1<<20);renderer=(ProjectionRenderer1201)allocate(ProjectionRenderer1201.class);
            set(renderer,"stream",stream);set(renderer,"layout",new PlacementLayout(placement,metadata.regions()));set(renderer,"resolvers",new HashMap<>());
            if(cached)cache();
        }
        void cache()throws Exception{var f=SectionStreamer.class.getDeclaredField("cache");f.setAccessible(true);@SuppressWarnings("unchecked") var cache=(WeightedLru<SectionKey,PackedSection>)f.get(stream);for(var key:stream.source().index().keySet())cache.put(key,stream.source().read(key));}
        ProjectionController.PrinterSource descriptor(){return new ProjectionController.PrinterSource(placement,renderer,metadata);}
        @Override public void close()throws Exception{stream.close();Files.deleteIfExists(file);}
    }
    private static Placement placement(Vec3i at,ReplaceRule rule){return new Placement(UUID.randomUUID(),"test","test.litematic",new PlacementTransform(at,0,false,false),true,false).overlap(rule);}
    private static PrinterProjectionView view(Source... sources){return new PrinterProjectionView(Arrays.stream(sources).map(Source::descriptor).toList());}
    public static int run()throws Exception{
        checks=0;Path directory=Files.createTempDirectory("printer-composition-");
        try(var stone=new Source(directory,1,placement(Vec3i.ZERO,ReplaceRule.ALL),1,true);
            var glass=new Source(directory,2,placement(Vec3i.ZERO,ReplaceRule.ALL),1,true);
            var air=new Source(directory,0,placement(Vec3i.ZERO,ReplaceRule.ALL),1,true);
            var nonAir=new Source(directory,0,placement(Vec3i.ZERO,ReplaceRule.NON_AIR),1,true);
            var keep=new Source(directory,2,placement(Vec3i.ZERO,ReplaceRule.NONE),1,true);
            var ignored=new Source(directory,3,placement(Vec3i.ZERO,ReplaceRule.ALL),1,true);
            var unknown=new Source(directory,2,placement(Vec3i.ZERO,ReplaceRule.ALL),1,false);
            var far=new Source(directory,2,placement(new Vec3i(32,0,0),ReplaceRule.ALL),1,true);
            var hidden=new Source(directory,2,placement(new Vec3i(-32,0,0),ReplaceRule.ALL).renderBlocks(false),1,true);
            var disabled=new Source(directory,2,placement(Vec3i.ZERO,ReplaceRule.ALL).enabled(false),1,true);
            var overload=new Source(directory,1,placement(Vec3i.ZERO,ReplaceRule.ALL),257,true)){
            check(view(stone,glass).apply(Vec3i.ZERO).state()==Blocks.GLASS.getDefaultState(),"Later placement ALL wins");
            check(view(glass,stone).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"Insertion order is deterministic");
            check(view(stone,air).apply(Vec3i.ZERO).state().isAir(),"ALL air erases lower source state");
            check(view(stone,nonAir).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"NON_AIR skips higher air");
            check(view(stone,keep).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"NONE preserves occupied lower state");
            check(view(air,keep).apply(Vec3i.ZERO).state()==Blocks.GLASS.getDefaultState(),"NONE fills lower air");
            check(view(stone,ignored).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"Structure void never replaces");
            check(view(stone,disabled).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"Disabled placement excluded");
            check(view(hidden).apply(new Vec3i(-32,0,0)).state()==Blocks.GLASS.getDefaultState(),"Display-hidden enabled source still prints");
            var combined=view(stone,far);check(combined.apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState()&&combined.apply(new Vec3i(32,0,0)).state()==Blocks.GLASS.getDefaultState(),"One sampler spans two separate placements");
            check(!combined.apply(new Vec3i(16,0,0)).inside(),"Gap outside both sources remains outside");
            var streaming=view(stone,unknown);check(streaming.apply(Vec3i.ZERO).inside()&&streaming.apply(Vec3i.ZERO).state()==null,"Unloaded higher section is UNKNOWN, not lower stone");
            check(view(unknown,keep).apply(Vec3i.ZERO).state()==null,"NONE cannot overwrite unresolved lower section");
            check(view(unknown,stone).apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"Known higher ALL can replace lower UNKNOWN");
            unknown.cache();check(streaming.apply(Vec3i.ZERO).state()==Blocks.GLASS.getDefaultState(),"Cached geometry still reads newly decoded source cells");
            check(view(overload).apply(Vec3i.ZERO).state()==null,"Excessive overlap returns UNKNOWN within part budget");
            var pending=new ProjectionController.PrinterSource(far.placement,null,far.metadata);var bounded=new PrinterProjectionView(List.of(stone.descriptor(),pending));
            check(bounded.apply(Vec3i.ZERO).state()==Blocks.STONE.getDefaultState(),"Known pending bounds do not block distant ready sources");
            check(bounded.apply(new Vec3i(32,0,0)).state()==null&&bounded.apply(new Vec3i(32,0,0)).inside(),"Pending known region is UNKNOWN");
            var unmapped=new PrinterProjectionView(List.of(stone.descriptor(),new ProjectionController.PrinterSource(far.placement,null,null)));
            check(unmapped.apply(Vec3i.ZERO).state()==null,"Unknown source extent cannot expose potentially overridden lower state");
            var limited=view(stone);for(int i=0;i<64;i++)limited.apply(new Vec3i(i*16,16,16));check(limited.apply(Vec3i.ZERO).inside()&&limited.apply(Vec3i.ZERO).state()==null,"Section cache exhaustion is bounded UNKNOWN, not false air");
            check(!stone.descriptor().equals(new ProjectionController.PrinterSource(stone.placement,glass.renderer,stone.metadata)),"Renderer replacement changes source context identity");
        }finally{try{Files.deleteIfExists(directory);}catch(DirectoryNotEmptyException incomplete){directory.toFile().deleteOnExit();}}
        return checks;
    }
    public static void main(String[] args)throws Exception{net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();System.out.println("PrinterCompositionChecks: "+run()+" checks");}
}
