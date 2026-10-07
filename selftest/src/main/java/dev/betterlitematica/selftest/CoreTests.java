package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Dependency-free regression runner; each failed check exits non-zero. */
public final class CoreTests {
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static int tests,checks;private static Path temp,source,cachePath;private static String expectedHash;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void expect(Class<? extends Throwable> type,Checked call)throws Exception{
        try{call.run();}catch(Throwable e){check(type.isInstance(e),"Expected "+type+" got "+e);return;}throw new AssertionError("Expected "+type);
    }
    private static void test(String name,Checked body)throws Exception{long t=System.nanoTime();body.run();tests++;System.out.printf(Locale.ROOT,"PASS %-49s %8.2f ms%n",name,(System.nanoTime()-t)/1e6);}
    private static Map<String,Object> compound(Object v)throws IOException{return NbtReader.compound(v,"test");}
    private static Path write(String name,Map<String,Object> root)throws IOException{Path p=temp.resolve(name);Fixtures.write(p,root);return p;}
    private static Path imported(Path p)throws IOException{return SchematicImporter.importFile(p,temp.resolve("cache"),Cancellation.NEVER,x->{}).path();}
    public static void main(String[] args)throws Exception{
        long started=System.nanoTime();temp=Files.createTempDirectory("betterlitematica-test-");
        try{
            test("antialiased status badges and bounded physical rasters",()->{checks+=StatusBadgeChecks.run();});
            test("tintable vector glyphs at exact physical sizes",()->{checks+=UiGlyphChecks.run();});
            test("complete source previews, embedded digests and bounded cache",()->{checks+=SchematicPreviewChecks.run(temp.resolve("previews"));});
            test("axis handle geometry and stable signed grid dragging",()->{checks+=AxisGizmoChecks.run();});
            test("placement quantity uses byte budgets not fixed counts",()->{checks+=UnlimitedPlacementChecks.run();});
            test("per-placement display filters and legacy settings migration",()->{checks+=BlockDisplayFilterChecks.run();});
            test("complete bounded directory search and cancellation",()->{checks+=DirectorySearchChecks.run(temp);});
            test("draft persistence ordering and failure recovery",()->{checks+=DraftRecoveryChecks.run(temp.resolve("recovery"));});
            test("finite material batches avoid needless switches without starvation",()->{checks+=PrinterBatchChecks.run();});
            test("movement retains printer work and scans the advancing frontier",()->{checks+=PrinterMovementChecks.run();});
            test("immediate source-cell visibility and bounded quad ranges",()->{checks+=ImmediateVisibilityChecks.run();});
            test("completed mesh refresh retains changes during construction",()->{checks+=MeshRefreshChecks.run();});
            test("replacement meshes retain reserved room within the global cap",()->{checks+=RenderRefreshBudgetChecks.run();});
            test("editing input release after menus and failures",()->{
                var guard=new InputReleaseGuard();check(!guard.owns(),"Idle input belongs to game");guard.arm();check(guard.owns()&&!guard.ready(),"Entering waits for physical release");guard.poll(true,false,true);check(!guard.ready(),"Held click never edits on entry");guard.poll(true,false,false);check(guard.ready(),"Released click arms next edge");guard.pause();guard.poll(true,true,false);check(guard.owns()&&!guard.ready(),"Menu clearing vanilla state does not release ownership");guard.poll(false,false,false);check(guard.owns(),"Focus loss cannot unlock mining");guard.poll(true,false,true);check(guard.owns(),"Held key after returning remains consumed");guard.poll(true,false,false);check(!guard.owns(),"Only physical release returns input to game");
            });
            test("global render budget reservations and retirement",()->{
                var budget=new RenderResources(100,10,8);var released=new AtomicInteger();var detached=new AtomicInteger();
                var front=budget.begin(RenderResources.VISIBLE);front.reserve(new int[]{30},RenderResources.VISIBLE).get(0).attach(released::incrementAndGet);front.finish(RenderResources.VISIBLE,detached::incrementAndGet);
                var back=budget.begin(RenderResources.NEARBY);for(var lease:back.reserve(new int[]{20,20},RenderResources.NEARBY))lease.attach(released::incrementAndGet);back.finish(RenderResources.NEARBY,detached::incrementAndGet);
                var building=budget.begin(RenderResources.VISIBLE);var extra=building.reserve(new int[]{20},RenderResources.VISIBLE);extra.get(0).attach(released::incrementAndGet);
                check(budget.usedBytes()==90&&budget.objects()==4,"In-flight buffers count before mesh admission");
                check(building.reserve(new int[]{20},RenderResources.VISIBLE)==null,"Budget rejects upload before cold buffers are actually released");
                check(!front.closed()&&back.closed()&&detached.get()==1&&released.get()==0&&budget.pendingBytes()==40,"Current views protected, eviction detaches but does not erase charge");
                budget.drain(1,Long.MAX_VALUE);check(budget.usedBytes()==70&&budget.objects()==3&&released.get()==1,"Release work limited to one actual buffer");
                var second=building.reserve(new int[]{20},RenderResources.VISIBLE);check(second!=null&&budget.usedBytes()==90,"Space reused only after release");second.get(0).attach(released::incrementAndGet);
                building.close();building.close();front.close();budget.drain(100,Long.MAX_VALUE);check(budget.usedBytes()==0&&budget.objects()==0&&budget.groups()==0&&released.get()==5,"Cancel and duplicate close release each upload exactly once");
            });
            test("render pressure protects warm views and stops churn",()->{
                var budget=new RenderResources(60,3,8);var removed=new AtomicInteger();var a=budget.begin(RenderResources.VISIBLE);a.reserve(new int[]{20},RenderResources.VISIBLE);a.finish(RenderResources.VISIBLE,removed::incrementAndGet);var b=budget.begin(RenderResources.VISIBLE);b.reserve(new int[]{20},RenderResources.VISIBLE);b.finish(RenderResources.VISIBLE,removed::incrementAndGet);var next=budget.begin(RenderResources.VISIBLE);next.reserve(new int[]{20},RenderResources.VISIBLE);
                for(int i=0;i<20;i++)check(next.reserve(new int[]{1},RenderResources.VISIBLE)==null,"Full visible cache never uploads then evicts");check(removed.get()==0,"Both placements publish protection before allocation");
                a.priority(RenderResources.NEARBY);check(next.reserve(new int[]{1},RenderResources.VISIBLE)==null&&!a.closed(),"Recently visible surfaces survive a quick camera turn");long waiting=budget.revision();for(int i=0;i<10;i++)budget.nextFrame();check(waiting==budget.revision(),"No per-frame pressure retry during camera grace");budget.nextFrame();check(waiting!=budget.revision(),"Grace expiry wakes a renderer parked on the revision without another camera movement");
                check(next.reserve(new int[]{1},RenderResources.NEARBY)==null&&!a.closed(),"Warm prefetch cannot evict another nearby surface");check(next.reserve(new int[]{1},RenderResources.VISIBLE)==null&&a.closed()&&!b.closed(),"Visible work can reclaim old offscreen surface after grace");budget.drain(10,Long.MAX_VALUE);check(next.reserve(new int[]{1},RenderResources.VISIBLE)!=null,"Actual free space wakes allocation");next.close();b.close();budget.drain(100,Long.MAX_VALUE);check(budget.groups()==0,"All placeholder and active allocations retire");
                var countBudget=new RenderResources(1000,2,2);var huge=countBudget.begin(RenderResources.VISIBLE);huge.reserve(new int[]{1,1},RenderResources.VISIBLE);check(huge.reserve(new int[]{1},RenderResources.VISIBLE)==null,"Small buffers obey object count, not only bytes");expect(IllegalArgumentException.class,()->huge.reserve(new int[]{1001},RenderResources.VISIBLE));huge.finish(RenderResources.VISIBLE,()->{});var empty=countBudget.begin(RenderResources.NEARBY);check(countBudget.begin(RenderResources.VISIBLE)==null,"Empty mesh records have a group count budget");empty.close();long stable=countBudget.revision();check(countBudget.drain(10,Long.MAX_VALUE)==0&&countBudget.revision()==stable,"Rejected empty build does not perpetually wake itself next frame");huge.close();countBudget.drain(10,Long.MAX_VALUE);check(countBudget.groups()==0,"Resource-less reservations also return to zero");
            });
            test("global mesh permit prevents in-flight deadlock",()->{
                var budget=new RenderResources(300,3,8);var first=budget.begin(RenderResources.VISIBLE);first.reserve(new int[]{40,40},RenderResources.VISIBLE);check(budget.begin(RenderResources.VISIBLE)==null,"Second placement cannot pin incomplete buffers beside first");first.reserve(new int[]{40},RenderResources.VISIBLE);long waiting=budget.revision();first.finish(RenderResources.COLD,()->{});check(waiting!=budget.revision(),"Mesh completion wakes another placement");var second=budget.begin(RenderResources.VISIBLE);check(second!=null,"Next placement receives released build permit");check(second.reserve(new int[]{40},RenderResources.VISIBLE)==null&&first.closed(),"Completed old mesh can now be reclaimed, avoiding mutual in-flight deadlock");budget.drain(10,Long.MAX_VALUE);check(second.reserve(new int[]{40},RenderResources.VISIBLE)!=null,"Next placement advances after incremental reclaim");second.close();budget.drain(10,Long.MAX_VALUE);check(budget.groups()==0,"Cancelled build also releases global permit and accounting");
            });
            test("render scheduler hands off between unfinished large placements",()->{
                class Worker implements RenderScheduler.Worker{int steps,completed,calls;boolean stalled;public boolean building(){return steps!=0;}public RenderScheduler.Work buildFrame(long deadline,int allowance){calls++;if(stalled)return RenderScheduler.Work.NONE;steps++;if(steps==2){steps=0;completed++;}return new RenderScheduler.Work(1,true);}}
                var a=new Worker();var b=new Worker();var workers=List.of(a,b);int next=RenderScheduler.run(workers,0,8,Long.MAX_VALUE,20);check(a.completed==2&&b.completed==2&&a.calls==4&&b.calls==4,"Large sources both complete sections before either source finishes");
                RenderScheduler.run(workers,next,32,Long.MAX_VALUE,3);check(a.calls+b.calls==11&&a.completed==3&&b.building(),"All placements share a single upload allowance");RenderScheduler.run(workers,0,1,Long.MAX_VALUE,20);check(b.completed==3&&!b.building(),"An unfinished job retains its one completion opportunity across frames");
                int before=a.calls+b.calls;RenderScheduler.run(workers,0,32,0,20);check(a.calls+b.calls==before,"Expired global deadline does not renew for another placement");a.steps=1;a.stalled=true;RenderScheduler.run(workers,0,32,Long.MAX_VALUE,20);check(a.calls+b.calls==before+1,"Unknown data or memory wait does not busy loop within the frame");a.steps=0;a.stalled=false;
                var only=new Worker();RenderScheduler.run(List.of(only),0,32,Long.MAX_VALUE,100);check(only.completed==16,"A single projection keeps multi-section-per-frame throughput");
            });
            test("dense special meshes use incremental resource release",()->{
                var budget=new RenderResources(8L<<20,65536,16);var dense=budget.begin(RenderResources.VISIBLE);var released=new AtomicInteger();for(int i=0;i<4096;i++)dense.reserve(new int[]{24*24},RenderResources.VISIBLE).get(0).attach(released::incrementAndGet);dense.finish(RenderResources.VISIBLE,()->{});check(budget.objects()==4096&&budget.usedBytes()==4096L*24*24,"4096 small special models fit without a new per-section object cutoff");dense.close();check(released.get()==0,"Dense section retirement performs no synchronous native deletions");budget.drain(64,Long.MAX_VALUE);check(released.get()==64&&budget.usedBytes()==4032L*24*24,"Only 64 parts release, all remaining retired parts still charged");budget.drain(5000,Long.MAX_VALUE);check(budget.usedBytes()==0&&budget.groups()==0,"Dense section eventually fully releases");
            });
            test("render resource release faults retain charge",()->{
                var budget=new RenderResources(64,4,4);var group=budget.begin(RenderResources.VISIBLE);var attempts=new AtomicInteger();group.reserve(new int[]{48},RenderResources.VISIBLE).get(0).attach(()->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("injected release failure");});group.close();expect(IllegalStateException.class,()->budget.drain(1,Long.MAX_VALUE));check(budget.usedBytes()==48&&budget.pendingBytes()==48,"Failed native release still consumes budget");budget.drain(10,Long.MAX_VALUE);check(budget.usedBytes()==0&&budget.groups()==0&&attempts.get()==2,"Failed release remains retryable without double accounting");
            });
            test("same-type export retains block and fluid data",()->{
                var part=Fixtures.region(0,0,0,2,1,1,i->1);part.put("BlockStatePalette",List.of(Map.of("Name","minecraft:air"),Map.of("Name","minecraft:chest","Properties",Map.of("facing","north","waterlogged","true","type","single"))));
                var chest=Map.of("x",0,"y",0,"z",0,"id","minecraft:chest","Items",List.of(Map.of("id","minecraft:diamond","Count",(byte)9,"Slot",(byte)0)));var tick=Map.of("x",0,"y",0,"z",0,"Block","minecraft:chest","Time",1);part.put("TileEntities",List.of(chest));part.put("PendingBlockTicks",List.of(tick));part.put("PendingFluidTicks",List.of(tick));var file=write("retain-chest.litematic",Fixtures.litematic(Map.of("a",part),"retention"));
                Path turned=temp.resolve("retain-turned.litematic");var result=LitematicEdit.replace(file,turned,BlockStateSpec.parse("chest"),BlockStateSpec.parse("chest[facing=east,waterlogged=true,type=single]"),true,null,Cancellation.NEVER);check(result.changed()==2&&result.removedBlockEntities()==0&&result.removedTicks()==0,"Facing change keeps inventory and both tick kinds");var restored=SchematicDocument.read(turned,Cancellation.NEVER).parts().get(0);check(restored.blockEntities().get(0).get("Items").equals(chest.get("Items")),"Exact inventory survives export");
                Path dry=temp.resolve("retain-dry.litematic");result=LitematicEdit.replace(file,dry,BlockStateSpec.parse("chest"),BlockStateSpec.parse("chest[facing=east,waterlogged=false,type=single]"),true,null,Cancellation.NEVER);check(result.removedBlockEntities()==0&&result.removedTicks()==1,"Removing water preserves chest but drops fluid tick");var dryPart=SchematicDocument.read(dry,Cancellation.NEVER).parts().get(0);check(dryPart.blockTicks().size()==1&&dryPart.fluidTicks().isEmpty(),"Only fluid schedule removed");
            });
            test("temporary source lease survives disconnect cleanup",()->{
                try(var sources=new TemporarySources(temp.resolve("leased-source"))){String id=sources.create(Fixtures.litematic(Map.of("a",Fixtures.region(0,0,0,1,1,1,i->1)),"lease"),Cancellation.NEVER);var lease=sources.lease(temp,id);var file=lease.reference().read();sources.clear();sources.cleanupAsync().get(3,java.util.concurrent.TimeUnit.SECONDS);check(Files.exists(file)&&lease.reference().read().equals(file),"Disconnect cannot delete a baseline being copied");lease.close();sources.cleanupAsync().get(3,java.util.concurrent.TimeUnit.SECONDS);check(!Files.exists(file),"Release allows owned retired source deletion");lease.close();}
            });
            test("sparse editing history and immutable snapshots",()->{
                var metadata=new BlueprintMetadata("edit",3465,"0".repeat(64),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("stone")),List.of(new Region("one",Vec3i.ZERO,new Vec3i(32,1,1)),new Region("two",Vec3i.ZERO,new Vec3i(32,1,1))),List.of());var edits=new SchematicEdits(metadata,new long[]{64,0});var key=new SectionKey(0,0,0,0);var original=edits.snapshot();
                edits.apply(List.of(new SchematicEdits.Request(key,1,0,BlockStateSpec.parse("stone"),false,false,false)));var frozen=edits.snapshot();check(original.sections().isEmpty()&&edits.counts()[0]==63&&edits.counts()[1]==1,"Snapshot and full-source histogram separate before/after");
                edits.apply(List.of(new SchematicEdits.Request(key,1,0,BlockStateSpec.parse("glass"),false,false,false)));check(frozen.metadata().palette().size()==2&&frozen.patch(key,1).state()==1,"A save snapshot cannot change under a later edit");edits.undo();check(edits.snapshot().patch(key,1).state()==1&&edits.canRedo(),"Undo restores one whole action");long revision=edits.snapshot().revision();
                expect(IllegalArgumentException.class,()->edits.apply(List.of(new SchematicEdits.Request(key,256,0,BlockStateSpec.parse("glass"),false,false,false))));check(edits.canRedo()&&edits.snapshot().revision()==revision,"Rejected edit does not clear redo or mutate the draft");
                edits.redo();check(edits.snapshot().metadata().palette().get(edits.snapshot().patch(key,1).state()).name().equals("minecraft:glass"),"Redo restores final state");edits.undo();edits.undo();check(!edits.dirty()&&edits.counts()[0]==64&&edits.canRedo(),"Undo to source removes both state and auxiliary-data deltas");
                edits.apply(List.of(new SchematicEdits.Request(key,2,0,BlockStateSpec.parse("glass"),false,false,false)));check(!edits.canRedo()&&edits.snapshot().sections().keySet().stream().noneMatch(k->k.region()==1),"New edit branches history and does not edit overlapping sibling region");
                int[] offsets={0,1,15};for(int i:offsets)check(edits.sample(key,i,0)==0,"Untouched cells use immutable base");
            });
            test("draft merge preserves complete source and repacks palette",()->{
                var first=Fixtures.region(31,0,0,-32,1,1,i->i%4);var palette=new ArrayList<>(Fixtures.palette());palette.set(1,new LinkedHashMap<>(Map.of("Name","minecraft:chest","Properties",Map.of("facing","north","type","single","waterlogged","true"))));first.put("BlockStatePalette",palette);var chest=new LinkedHashMap<String,Object>(Fixtures.xyz(1,0,0));chest.put("id","minecraft:chest");chest.put("Items",List.of(Map.of("Slot",(byte)4,"id","minecraft:diamond","Count",(byte)12)));first.put("TileEntities",List.of(chest));var tick=new LinkedHashMap<String,Object>(Fixtures.xyz(1,0,0));tick.put("Block","minecraft:chest");tick.put("Time",7);first.put("PendingBlockTicks",List.of(tick));first.put("PendingFluidTicks",List.of(tick));first.put("UnknownRegion",new int[]{4,5,6});
                var second=Fixtures.region(31,0,0,-32,1,1,i->i%4);var root=Fixtures.litematic(Map.of("a",first,"b",second),"draft source");root.put("UnknownRoot",new byte[]{1,2,3});root.put("Metadata",new LinkedHashMap<>(Map.of("Name","draft source","TotalBlocks",48,"TotalVolume",64,"Custom","kept")));Path file=write("draft-source.litematic",root);String sha=SchematicImporter.sha256(file,Cancellation.NEVER);
                try(var cache=BlueprintCache.open(imported(file))){var edits=new SchematicEdits(cache.metadata(),cache.copyBlockStateCounts());var key=new SectionKey(0,0,0,0);int chestId=cache.read(key).globalId(1);var east=BlockStateSpec.parse("minecraft:chest[facing=east,type=single,waterlogged=true]");edits.apply(List.of(new SchematicEdits.Request(key,1,chestId,east,true,true,true)));var directionSnapshot=edits.snapshot();int tail=cache.read(new SectionKey(0,1,0,0)).globalId(15);edits.apply(List.of(new SchematicEdits.Request(new SectionKey(0,1,0,0),15,tail,BlockStateSpec.parse("minecraft:light[level=12,waterlogged=false]"),false,false,false)));
                    Path output=temp.resolve("draft-merged.litematic");SchematicPatchIO.write(file,output,edits.snapshot(),Cancellation.NEVER);var parsed=NbtReader.read(output,SchematicImporter.SOURCE_LIMITS,Cancellation.NEVER);var regions=compound(parsed.get("Regions"));var a=compound(regions.get("a"));check(Arrays.equals((byte[])parsed.get("UnknownRoot"),new byte[]{1,2,3})&&Arrays.equals((int[])a.get("UnknownRegion"),new int[]{4,5,6}),"Unknown root and region arrays survive merge");check(compound(parsed.get("Metadata")).get("Custom").equals("kept")&&a.get("TileEntities").equals(List.of(chest)),"Metadata and chest inventory survive a same-type direction edit");
                    var doc=SchematicDocument.read(output,Cancellation.NEVER);check(doc.parts().get(0).region().anchor().equals(new Vec3i(31,0,0))&&doc.parts().get(0).region().min().equals(Vec3i.ZERO),"Negative source anchor remains unchanged");check(doc.parts().get(0).palette().get(doc.parts().get(0).blocks().get(1)).equals(east),"Source-local state, without double placement transform");check(doc.parts().get(0).palette().get(doc.parts().get(0).blocks().get(31)).name().equals("minecraft:light"),"Sparse edit across section and bit-word boundary");check(doc.parts().get(1).palette().get(doc.parts().get(1).blocks().get(31)).name().equals("minecraft:oak_stairs"),"Overlapping sibling data untouched");for(int i=0;i<32;i++)if(i!=1&&i!=31)check(doc.parts().get(0).blocks().get(i)==i%4,"Expanded packed palette preserves every unedited cell");
                    edits.apply(List.of(new SchematicEdits.Request(key,1,chestId,BlockStateSpec.AIR,false,false,false)));Path removed=temp.resolve("draft-delete.litematic");SchematicPatchIO.write(file,removed,edits.snapshot(),Cancellation.NEVER);var removedPart=SchematicDocument.read(removed,Cancellation.NEVER).parts().get(0);check(removedPart.blockEntities().isEmpty()&&removedPart.blockTicks().isEmpty()&&removedPart.fluidTicks().isEmpty(),"Deletion removes incompatible auxiliary data only at edited cell");edits.undo();Path restored=temp.resolve("draft-undo.litematic");SchematicPatchIO.write(file,restored,edits.snapshot(),Cancellation.NEVER);var restoredPart=SchematicDocument.read(restored,Cancellation.NEVER).parts().get(0);check(restoredPart.blockEntities().get(1).get("Items").equals(chest.get("Items"))&&restoredPart.blockTicks().size()==1&&restoredPart.fluidTicks().size()==1,"Undo restores original auxiliary data after delete");
                    Path frozen=temp.resolve("draft-frozen.litematic");SchematicPatchIO.write(file,frozen,directionSnapshot,Cancellation.NEVER);check(SchematicDocument.read(frozen,Cancellation.NEVER).parts().get(0).palette().get(SchematicDocument.read(frozen,Cancellation.NEVER).parts().get(0).blocks().get(31)).name().equals("minecraft:oak_stairs"),"Older immutable save includes only edits at its own revision");
                    expect(FileAlreadyExistsException.class,()->SchematicPatchIO.write(file,output,edits.snapshot(),Cancellation.NEVER));Path cancelled=temp.resolve("draft-cancelled.litematic");expect(InterruptedIOException.class,()->SchematicPatchIO.write(file,cancelled,edits.snapshot(),()->true));check(!Files.exists(cancelled),"Cancelled merge publishes no artifact");Path changed=write("draft-external-change.litematic",Fixtures.litematic(Map.of("a",first),"different source"));expect(IOException.class,()->SchematicPatchIO.write(changed,temp.resolve("draft-stale.litematic"),edits.snapshot(),Cancellation.NEVER));
                    try(var materialized=SchematicPatchIO.open(file,edits.snapshot(),temp.resolve("draft-working"),Cancellation.NEVER)){check(Files.isRegularFile(materialized.path())&&materialized.temporary(),"Complete edited source is available to paste and export consumers");}try(var files=Files.list(temp.resolve("draft-working"))){check(files.findAny().isEmpty(),"Operation snapshot file removed after consumption");}
                }check(SchematicImporter.sha256(file,Cancellation.NEVER).equals(sha),"Original projection remains byte-identical");
            });
            test("air section additions and face-direction editing",()->{
                Path file=write("draft-air.litematic",Fixtures.litematic(Map.of("empty",Fixtures.region(0,0,0,32,2,2,i->0)),"air"));try(var cache=BlueprintCache.open(imported(file))){var index=new SpatialIndex(cache,Cancellation.NEVER);check(index.nearest(Vec3i.ZERO,64,100).isEmpty(),"Air-only source has no BPC sections");var key=new SectionKey(0,1,0,0);check(index.nearest(Vec3i.ZERO,64,100,null,List.of(key)).equals(List.of(key)),"Newly edited air section enters render query");}
                var face=new Vec3i(0,0,1);check(EditDirection.choose(face,.5,.5,1,new Vec3i(0,0,-1),true).equals(face),"Face center places outward");check(EditDirection.choose(face,.25,.75,1,new Vec3i(0,0,-1),false).equals(new Vec3i(0,0,-1)),"Inclusive center boundary deletes inward");check(EditDirection.choose(face,1,1,1,new Vec3i(0,0,-1),false).equals(new Vec3i(0,1,0)),"Corner tie selects second face axis");check(EditDirection.choose(face,1,.6,1,new Vec3i(0,0,-1),false).equals(new Vec3i(1,0,0)),"Outer quarter follows in-plane edge");for(var heading:List.of(new Vec3i(1,0,0),new Vec3i(-1,0,0),new Vec3i(0,0,1),new Vec3i(0,0,-1))){var chosen=EditDirection.choose(new Vec3i(0,1,0),1,1,1,heading,true);check(chosen.y()==0&&chosen.x()+chosen.z()==1,"Top face corner stays in horizontal plane for each player heading");}
            });
            test("temporary source ownership save and cancellation",()->{
                var catalog=new TemporarySources(temp.resolve("temporary-cache"));var schematics=Files.createDirectories(temp.resolve("temporary-user-files"));var root=Fixtures.litematic(Map.of("main",Fixtures.region(-2,4,9,3,2,4,i->i%4)),"temporary fixture");
                String key=catalog.create(root,Cancellation.NEVER);var ref=catalog.reference(schematics,key);var document=SchematicDocument.read(ref.read(),Cancellation.NEVER);check(ref.temporary()&&document.parts().get(0).blocks().size()==24,"Temporary catalog retains the complete source document");
                try(var files=Files.list(schematics)){check(files.findAny().isEmpty(),"Creating a temporary resource does not create a user schematic");}expect(IllegalArgumentException.class,()->catalog.reference(schematics,"@temporary/missing.litematic"));expect(IOException.class,()->catalog.reference(schematics,"../outside.litematic").read());
                var placement=new Placement(UUID.randomUUID(),"temporary",key,new PlacementTransform(new Vec3i(4,5,6),1,true,false),true,false);String version=ProjectVersions.save(schematics,"snapshot",placement,LayerRange.ALL,.45f,true,ref.path(),ref.root());var restored=ProjectVersions.load(schematics,"snapshot",version);check(!TemporarySources.temporary(restored.placements().get(0).source()),"Project snapshot turns a temporary source into a persistent independent file");check(Arrays.equals(Files.readAllBytes(ref.read()),Files.readAllBytes(schematics.resolve(restored.placements().get(0).source()))),"Project snapshot preserves the full temporary source bytes");
                catalog.delete(catalog.clear());check(!Files.exists(ref.path())&&Files.exists(schematics.resolve(restored.placements().get(0).source())),"Clearing session resources deletes only owned temporary files");expect(IllegalArgumentException.class,()->catalog.reference(schematics,key));
                expect(InterruptedIOException.class,()->catalog.create(root,()->true));var began=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);var error=new AtomicReference<Throwable>();var first=new AtomicBoolean(true);
                Thread worker=new Thread(()->{try{catalog.create(root,()->{if(first.getAndSet(false)){began.countDown();try{resume.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}return false;});}catch(Throwable e){error.set(e);}});worker.start();check(began.await(5,java.util.concurrent.TimeUnit.SECONDS),"Temporary creation reaches cancellation boundary");var stale=catalog.clear();resume.countDown();worker.join(5000);check(!worker.isAlive()&&error.get() instanceof InterruptedIOException,"World switch prevents an in-flight temporary source from publishing");catalog.delete(stale);
                for(int i=0;i<32;i++){String extra=catalog.create(root,Cancellation.NEVER);check(Files.isRegularFile(catalog.reference(schematics,extra).read()),"Temporary sources beyond 16 retain full documents");}catalog.delete(catalog.clear());
                try(var busy=new SessionIo()){var release=new java.util.concurrent.CountDownLatch(1);var startedCleanup=new java.util.concurrent.CountDownLatch(1);busy.submit(()->{startedCleanup.countDown();release.await();return null;});check(startedCleanup.await(5,java.util.concurrent.TimeUnit.SECONDS),"Settings worker blocked for cleanup isolation check");for(int i=0;i<32;i++)busy.submit(()->null);String extra=catalog.create(root,Cancellation.NEVER);Path path=catalog.reference(schematics,extra).read();catalog.remove(extra);try{catalog.cleanupAsync().get(5,java.util.concurrent.TimeUnit.SECONDS);check(!Files.exists(path)&&!catalog.cleanupPending(),"Owned source cleanup succeeds even while settings IO is full");}finally{release.countDown();}}catalog.close();
            });
            test("command transactions park independently and resume",()->{
                var queue=new CommandDispatchQueue();var a=new Vec3i(0,0,0);var b=new Vec3i(32,0,0);var passenger=new Vec3i(64,0,0);var loaded=new HashSet<>(List.of(a,b,passenger));
                queue.add(List.of("a:prepare","a:set","a:merge"),List.of(a));check(queue.poll(loaded::contains).equals("a:prepare"),"First transaction begins");loaded.remove(a);
                queue.add(List.of("b:prepare","b:set","b:merge"),List.of(b));for(String expected:List.of("b:prepare","b:set","b:merge"))check(queue.poll(loaded::contains).equals(expected),"Loaded work advances while another NBT transaction is parked");
                check(queue.poll(loaded::contains)==null&&queue.size()==1,"Unavailable work remains pending");loaded.add(a);check(queue.poll(loaded::contains).equals("a:set")&&queue.poll(loaded::contains).equals("a:merge")&&queue.isEmpty(),"Parked transaction resumes in order without repeating commands");
                queue.add(List.of("entity"),List.of(b,passenger));loaded.remove(passenger);check(queue.poll(loaded::contains)==null,"All passenger and anchor chunks are rechecked at dispatch");loaded.add(passenger);check(queue.poll(loaded::contains).equals("entity"),"Entity dispatch resumes when every required chunk is available");
                for(int i=0;i<128;i++)queue.add(List.of("x"),List.of(a));check(!queue.hasRoom(),"Parked transaction count is bounded");expect(IllegalStateException.class,()->queue.add(List.of("overflow"),List.of(a)));for(int i=0;i<128;i++)queue.poll(loaded::contains);check(queue.isEmpty()&&queue.hasRoom(),"Dispatch releases its memory budget");
                var large=Collections.nCopies(4096,"x".repeat(255));for(int i=0;i<14;i++){if(!queue.hasRoom())break;queue.add(large,List.of(a));}check(!queue.hasRoom(),"Payload byte budget limits parked large NBT transactions");
            });
            test("artifact cancel and commit have a single winner",()->{
                for(int i=0;i<32;i++){var gate=new NewFileCommit();Path part=temp.resolve("race-"+i+".part"),target=temp.resolve("race-"+i+".out");Files.writeString(part,"complete");var start=new java.util.concurrent.CountDownLatch(1);var moved=new AtomicBoolean();var cancelled=new AtomicBoolean();var error=new AtomicReference<Throwable>();
                    Thread writer=new Thread(()->{try{start.await();moved.set(gate.commit(part,target));}catch(Throwable e){error.set(e);}});Thread cancel=new Thread(()->{try{start.await();cancelled.set(gate.cancel());}catch(Throwable e){error.set(e);}});writer.start();cancel.start();start.countDown();writer.join();cancel.join();check(error.get()==null,"Commit race has no worker failure");check(moved.get()!=cancelled.get(),"Exactly one operation wins");check(Files.exists(target)==moved.get()&&gate.committed()==moved.get(),"Published file agrees with the reported winner");check(!moved.get()||Files.readString(target).equals("complete"),"Committed content is complete");Files.deleteIfExists(part);
                }
            });
            test("ice water never breaks before server confirmation",()->{
                var plan=new IceWaterPlan(7,42,100);check(plan.owns(7,42)&&!plan.owns(8,42)&&!plan.owns(7,43),"Transaction binds generation and position");check(plan.next(101,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.WAIT,"Predicted ice never authorizes destruction");plan.confirm(IceWaterPlan.Observation.ICE);check(plan.next(102,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.BREAK,"Server-confirmed owned ice may be broken");plan.breakingSent();check(plan.next(103,IceWaterPlan.Observation.WATER,true)==IceWaterPlan.Step.WAIT,"Predicted water is not completion");plan.confirm(IceWaterPlan.Observation.WATER);check(plan.next(104,IceWaterPlan.Observation.WATER,true)==IceWaterPlan.Step.DONE,"Authoritative water completes transaction");plan.cancel();check(plan.next(105,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.ABORT&&!plan.owns(7,42),"Cancellation forbids further destruction");var timeout=new IceWaterPlan(8,42,100);check(timeout.next(300,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.ABORT,"Missing confirmation times out");var rejected=new IceWaterPlan(8,43,100);rejected.confirm(IceWaterPlan.Observation.OTHER);check(rejected.next(101,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.ABORT,"Server rejection cannot become a break");var replaced=new IceWaterPlan(1,1,0);replaced.confirm(IceWaterPlan.Observation.ICE);replaced.confirm(IceWaterPlan.Observation.OTHER);replaced.confirm(IceWaterPlan.Observation.ICE);check(replaced.next(1,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.ABORT,"Replacement ice never inherits ownership");var broken=new IceWaterPlan(2,2,0);broken.confirm(IceWaterPlan.Observation.ICE);broken.breakingSent();broken.confirm(IceWaterPlan.Observation.AIR);broken.confirm(IceWaterPlan.Observation.ICE);check(broken.next(2,IceWaterPlan.Observation.ICE,true)==IceWaterPlan.Step.ABORT,"New ice after break cannot authorize another destruction");
            });
            test("action highlights are bounded and expire independently",()->{
                var history=new ActionHighlights(3);for(int i=0;i<10;i++)history.add(i,ActionHighlights.Kind.PLACE,i,100);check(history.live(20).size()==3&&history.live(20).stream().allMatch(m->m.position()>=7),"Events evict oldest entries");history.add(9,ActionHighlights.Kind.FAILED,20,50);check(history.live(20).size()==3&&history.live(20).stream().filter(m->m.position()==9).findFirst().orElseThrow().kind()==ActionHighlights.Kind.FAILED,"Same location updates event, not duplicates");check(history.live(200).isEmpty(),"All elapsed feedback removed");
            });
            test("subregion signed anchors and all composed orientations",()->{
                var r=new Region("negative",new Vec3i(8,17,26),new Vec3i(3,4,5),new Vec3i(10,20,30));
                for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int m=0;m<4;m++)for(int n=0;n<4;n++){
                    var main=new PlacementTransform(new Vec3i(-72,50,91),a,(m&1)!=0,(m&2)!=0);var sub=new RegionPlacement(new Vec3i(7,-9,12),b,(n&1)!=0,(n&2)!=0,true,false);
                    var p=new Placement(UUID.randomUUID(),"p","p.litematic",main,true,false).region(r,sub);var combined=p.transformFor(r);var layout=new PlacementLayout(p,List.of(r));
                    for(int x=8;x<11;x++)for(int y=17;y<21;y++)for(int z=26;z<31;z++){var local=new Vec3i(x,y,z);var relative=local.subtract(r.anchor());var mainLinear=new PlacementTransform(Vec3i.ZERO,a,(m&1)!=0,(m&2)!=0);var subLinear=new PlacementTransform(Vec3i.ZERO,b,(n&1)!=0,(n&2)!=0);
                        var expected=subLinear.apply(mainLinear.apply(relative)).add(main.apply(sub.position()));check(combined.apply(local).equals(expected),"Main then region orientation, anchor translated once");check(combined.inverse(expected).equals(local),"Source inverse remains exact");check(layout.at(expected).size()==1,"World domain includes transformed cells");
                    }
                    check(p.transformFor(r).apply(r.anchor()).equals(main.apply(sub.position())),"Signed anchor remains pivot");
                }
                var p=new Placement(UUID.randomUUID(),"p","p.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false).axes(1);
                expect(IllegalStateException.class,()->p.placed(new PlacementTransform(new Vec3i(1,0,0),0,false,false)));check(p.placed(new PlacementTransform(new Vec3i(0,1,0),0,false,false)).transform().origin().y()==1,"Unlocked axes remain editable");
            });
            test("region compositor air void unknown and ownership",()->{
                var regions=List.of(new Region("a",Vec3i.ZERO,new Vec3i(2,2,2)),new Region("b",Vec3i.ZERO,new Vec3i(2,2,2)));var p=new Placement(UUID.randomUUID(),"p","p.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);var palette=List.of(BlockStateSpec.AIR,BlockStateSpec.parse("stone"),BlockStateSpec.parse("glass"),BlockStateSpec.parse("structure_void"));
                int[] ids={1,2};var source=new PlacementLayout.Source(){public int state(PlacementLayout.Part part,Vec3i local){return ids[part.index()];}public BlockStateSpec spec(int region,int id){return palette.get(id);}};
                for(var rule:ReplaceRule.values()){var layout=new PlacementLayout(p.overlap(rule),regions);ids[0]=1;ids[1]=2;check(layout.sample(Vec3i.ZERO,source).part().index()==(rule==ReplaceRule.NONE?0:1),"Solid ownership follows replace rule");ids[1]=0;check(layout.sample(Vec3i.ZERO,source).state()==(rule==ReplaceRule.ALL?0:1),"Air overwrites only ALL");ids[1]=3;check(layout.sample(Vec3i.ZERO,source).state()==1,"Structure void never overwrites");ids[1]=-1;check(layout.sample(Vec3i.ZERO,source).unknown()==(rule!=ReplaceRule.NONE),"Unavailable later data is not air");ids[0]=-1;ids[1]=2;check(layout.sample(Vec3i.ZERO,source).unknown()==(rule==ReplaceRule.NONE),"Later ALL or nonair resolves earlier unknown");}
                ids[0]=1;ids[1]=2;var disabled=new PlacementLayout(p.region(regions.get(1),RegionPlacement.original(regions.get(1)).enabled(false)),regions);check(disabled.sample(Vec3i.ZERO,source).state()==1,"Disabled region has no domain");check(disabled.sample(new Vec3i(2,0,0),source)==null,"Domain excludes outside");
                var file=temp.resolve("regions.blps");var value=p.region(regions.get(1),new RegionPlacement(new Vec3i(20,30,40),3,true,false,false,true)).axes(5).renderBlocks(false).overlap(ReplaceRule.NON_AIR);PlacementStore.write(file,new PlacementSession(List.of(value),value.id(),LayerRange.ALL,.5f,true));check(PlacementStore.read(file).placements().get(0).equals(value),"All region and placement properties survive restart");
            });
            test("live verifier ignored samples refill and unknown yields",()->{
                var report=new VerificationReport();var a=new VerificationSection.Builder();for(int i=0;i<80;i++)a.add(new Vec3i(i,0,0),Comparison.MISSING,i<64?"stone":"glass","air");var first=a.build();check(first.samples().stream().anyMatch(v->v.key().expected().equals("glass")),"Later error group gets a representative");report.replace(VerificationSection.EMPTY,first);var key=new VerificationReport.Key(Comparison.MISSING,"stone","air");report.ignore(key);var b=new VerificationSection.Builder(report::ignored);for(int i=0;i<80;i++)b.add(new Vec3i(i,0,0),Comparison.MISSING,i<64?"stone":"glass","air");report.replace(first,b.build());check(report.samples().stream().allMatch(v->v.key().expected().equals("glass")),"Ignored group cannot consume sample slots");
                var unknowns=new VerificationSection.Builder();for(int i=0;i<60;i++)unknowns.add(new Vec3i(i,1,0),Comparison.UNKNOWN,"stone","");unknowns.add(new Vec3i(61,1,0),Comparison.WRONG_BLOCK,"stone","dirt");check(unknowns.build().samples().size()==1&&unknowns.build().samples().get(0).key().type()==Comparison.WRONG_BLOCK,"Unknown fallback does not hide actual errors");
            });
            test("auxiliary reader streams past packed arrays and preserves data",()->{
                var region=Fixtures.region(0,0,0,64,64,64,i->1);region.put("TileEntities",List.of(Map.of("x",1,"y",2,"z",3,"id","minecraft:sign","front_text",Map.of("messages",List.of("one","two")))));region.put("Entities",List.of(Map.of("id","minecraft:armor_stand","Pos",List.of(2.5,3.0,4.5))));var root=Fixtures.litematic(Map.of("a",region),"aux");var file=write("aux.litematic",root);
                var partial=NbtReader.readPartial(file,new NbtReader.Limits(2L<<20,65536,64,1000),Cancellation.NEVER,p->p.size()==3&&p.get(2).equals("BlockStates"));check(!compound(compound(partial.get("Regions")).get("a")).containsKey("BlockStates"),"Omitted packed array does not consume allocation budget");
                try(var cache=BlueprintCache.open(imported(file))){var aux=AuxiliaryData.read(file,cache.metadata(),Cancellation.NEVER);check(aux.parts().get(0).blocks().get(new Vec3i(1,2,3)).containsKey("front_text"),"Block entity custom NBT retained");check(aux.parts().get(0).entities().get(0).get("id").equals("minecraft:armor_stand"),"Entities retained independently of voxel cache");}
                expect(IOException.class,()->NbtReader.readPartial(file,new NbtReader.Limits(256,65536,64,1000),Cancellation.NEVER,p->p.size()==3&&p.get(2).equals("BlockStates")));
            });
            test("source occupancy skips air across unaligned section rows",()->{
                var size=new Vec3i(33,35,19);int volume=size.x()*size.y()*size.z();var random=new Random(261010);int[] ids=new int[volume];for(int i=0;i<volume;i++)ids[i]=random.nextInt(100)<20?2:random.nextInt(2);var blocks=PackedBits.pack(2,ids);var index=SourceBlockIndex.build(blocks,size,new boolean[]{true,true,false},Cancellation.NEVER);long count=0;
                for(int y=0;y<(size.y()+15)/16;y++)for(int z=0;z<(size.z()+15)/16;z++)for(int x=0;x<(size.x()+15)/16;x++){
                    var key=new SectionKey(0,x,y,z);var expected=new ArrayList<Integer>();for(int cell=0;cell<4096;cell++){int sx=x*16+(cell&15),sy=y*16+(cell>>>8),sz=z*16+((cell>>>4)&15);if(sx<size.x()&&sy<size.y()&&sz<size.z()&&ids[sx+sz*size.x()+sy*size.x()*size.z()]==2)expected.add(cell);}
                    var actual=new ArrayList<Integer>();for(int cell=index.next(key,0);cell<4096;cell=index.next(key,cell+1))actual.add(cell);check(actual.equals(expected),"Sparse cursor retains every non-air source cell once");check(index.sectionEmpty(key)==expected.isEmpty(),"Section occupancy agrees with contents");count+=actual.size();
                }
                check(index.count()==count&&index.estimatedBytes()<volume/8+2048,"Index has one bit per real source cell, not per padded section");
                var q=new DeferredSections(List.of(new Region("air",Vec3i.ZERO,size)));q.discover(100,k->0,Long.MAX_VALUE);check(q.finished()&&q.pending()==0,"Omitted air sections finish without work objects");
            });
            test("live verification replaces old groups and highights",()->{
                var a=new VerificationSection.Builder();a.add(Vec3i.ZERO,Comparison.MISSING,"stone","air");a.add(new Vec3i(1,0,0),Comparison.MATCH,"","");var first=a.build();var report=new VerificationReport();report.replace(VerificationSection.EMPTY,first);check(report.scanned()==2&&report.remainingErrors()==1,"Initial contribution");var key=report.groups().keySet().iterator().next();report.ignore(key);check(report.remainingErrors()==0,"Ignore still applies to live contributions");
                var b=new VerificationSection.Builder();b.add(Vec3i.ZERO,Comparison.MATCH,"","");b.add(new Vec3i(1,0,0),Comparison.WRONG_BLOCK,"stone","dirt");var second=b.build();report.replace(first,second);check(report.scanned()==2&&report.remainingErrors()==1&&report.groups().size()==1,"Repair removes old group and mismatch adds new group without double-counting");check(report.samples().size()==1&&report.samples().get(0).position().x()==1,"Repaired position loses its highlight");
                var c=new VerificationSection.Builder();c.add(Vec3i.ZERO,Comparison.UNKNOWN,"stone","");c.add(new Vec3i(1,0,0),Comparison.UNKNOWN,"stone","");var third=c.build();report.replace(second,third);check(report.scanned()==2&&report.remainingErrors()==0&&report.count(Comparison.UNKNOWN)==2,"Unloaded world is unknown, not missing");report.replace(third,first);report.resetIgnored();check(report.remainingErrors()==1&&report.count(Comparison.UNKNOWN)==0,"Reload restores current errors and ignore reset");report.replace(first,first);check(report.scanned()==2&&report.samples().size()==1,"Identical refresh does not inflate totals or samples");
            });
            test("independent printer work switches",()->{checks+=PrinterModeChecks.run();});
            test("complete spatial highlights and progressive cache",()->{checks+=VerificationSpatialChecks.run();});
            test("verification disk ledger ordering and repeated cleanup",()->{
                for(int cycle=0;cycle<20;cycle++){
                    var ledger=new VerificationLedger();var key=new SectionKey(0,0,0,0);var b=new VerificationSection.Builder();b.add(Vec3i.ZERO,Comparison.MISSING,"stone","air");var missing=b.build();var one=ledger.replace(key,missing).get(5,java.util.concurrent.TimeUnit.SECONDS);check(one.before().equals(VerificationSection.EMPTY),"New section starts with no contribution");
                    var two=ledger.replace(key,new VerificationSection(Map.of(),1,List.of())).get(5,java.util.concurrent.TimeUnit.SECONDS);check(two.before().equals(missing)&&two.after().matched()==1,"Replace reads the latest committed contribution");check(ledger.knownSections().get(5,java.util.concurrent.TimeUnit.SECONDS).equals(List.of(key)),"Refresh enumeration has no duplicate sections");ledger.close();ledger.closedFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);check(ledger.replace(key,missing).isCompletedExceptionally(),"Closed ledger cannot accept stale work");
                }
            });
            test("mesh replacement reserves bytes without requiring another cache slot",()->{
                var released=new ArrayList<Integer>();var cache=new WeightedLru<String,Integer>(100,2,Integer::longValue,released::add);
                cache.put("visible",30);cache.put("other",50);check(cache.canReplaceProtecting("visible",50,Set.of("visible","other")),"Full protected entry capacity still permits a fitting replacement");
                check(cache.canReplaceWithoutEviction("visible",50)&&!cache.canReplaceWithoutEviction("visible",51),"Replacement considers the old mesh bytes without evicting any entry");
                check(!cache.canReplaceProtecting("visible",51,Set.of("visible","other"))&&cache.canReplaceProtecting("visible",80,Set.of("visible")),"Growing replacement can reclaim only unprotected neighbors");
                check(cache.size()==2&&released.isEmpty(),"Admission check never deletes the old mesh");cache.remove("visible");check(cache.tryPutProtecting("visible",50,Set.of("other"))&&cache.get("other")==50,"Admitted replacement publishes without evicting another visible mesh");cache.close();
            });
            test("world render changes coalesce and survive bounded overflow",()->{
                var changes=new WorldSectionChanges(4);changes.block(new Vec3i(-1,-64,15));changes.block(new Vec3i(-16,-63,0));check(changes.pending()==1,"Same negative world section is coalesced");
                var box=changes.drain(1).get(0);check(box.min().equals(new Vec3i(-16,-64,0))&&box.max().equals(new Vec3i(-1,-49,15)),"Dirty section keeps exact inclusive bounds");
                changes.chunk(3,-2,-64,0);check(changes.pending()==4&&!changes.takeAll(),"Chunk notification retains vertical sections without walking cells");check(changes.drain(2).size()==2&&changes.pending()==2,"Render drains bounded work and retains the remainder");
                changes.block(new Vec3i(160,0,0));changes.block(new Vec3i(176,0,0));changes.block(new Vec3i(192,0,0));check(changes.pending()==0&&changes.takeAll()&&!changes.takeAll(),"Overflow becomes one lazy full invalidation without losing updates");
                changes.chunk(0,0,-64,320);check(changes.pending()==0&&changes.takeAll(),"Tall chunk notification cannot exceed queue capacity");
                changes.block(Vec3i.ZERO);changes.clear();check(changes.pending()==0&&!changes.takeAll(),"Disconnect clears old-world changes");changes.chunk(0,0,10,10);check(changes.pending()==0,"Empty world height emits no changes");
            });
            test("printer shared tick deadline bounds refill and unlimited dispatch",()->{
                var budget=new PrinterWorkBudget(1_000_000L,8);check(budget.scan(1_000_000L),"First scan uses the shared tick budget");
                check(budget.attempt(2_500_000L),"Execution can use more than the former fixed 1ms slice");
                check(budget.scan(4_000_000L)&&budget.attempt(8_999_999L),"A drained queue can refill and execute within the same deadline");
                check(!budget.attempt(9_000_000L)&&!budget.scan(9_000_000L),"Refills and execution both stop at the same deadline");
                var bounded=new PrinterWorkBudget(0,16);for(int i=0;i<8;i++)check(bounded.scan(0),"A bounded refill page is admitted");check(!bounded.scan(0),"Zero action limit cannot scan unlimited pages");
                for(int i=0;i<4096;i++)check(bounded.attempt(0),"An attempt is charged against the shared limit");check(!bounded.attempt(0),"An instant clock cannot create an unbounded retry loop");
                var wrap=new PrinterWorkBudget(Long.MAX_VALUE-100,1);check(wrap.hasTime(Long.MAX_VALUE)&&!wrap.hasTime(wrap.deadline()),"Monotonic clock wrapping preserves the deadline");
                check(new PrinterWorkBudget(0,32).hasTime(31_999_999L),"One-time prepared start remains bounded below a server tick");
                expect(IllegalArgumentException.class,()->new PrinterWorkBudget(0,0));expect(IllegalArgumentException.class,()->new PrinterWorkBudget(0,33));
            });
            test("printer batches group actual materials without starvation",()->{
                var old=new PrinterQueue(128);var batched=new PrinterQueue(128);
                for(int i=0;i<128;i++){int item=i%2;var job=new PrinterQueue.Job(4,i,100+(i%8),0,PrinterQueue.Kind.PLACE);old.offer(job,item);batched.offer(job,item);}
                int oldHand=0,newHand=0,oldSwitches=0,newSwitches=0;var seen=new HashSet<Long>();
                while(old.size()>0){int next=(int)(old.poll().position()%2);if(next!=oldHand){oldSwitches++;oldHand=next;}}
                while(batched.size()>0){var job=batched.poll(newHand,64);int next=(int)(job.position()%2);if(next!=newHand){newSwitches++;newHand=next;}check(seen.add(job.position()),"Batched jobs execute once");}
                check(oldSwitches==127&&newSwitches==1&&seen.size()==128,"Alternating two materials change hands once instead of 127 times");
                var fair=new PrinterQueue(256);for(int i=0;i<128;i++)fair.offer(new PrinterQueue.Job(1,i,10+i%4,0,PrinterQueue.Kind.PLACE),10);fair.offer(new PrinterQueue.Job(1,999,99,0,PrinterQueue.Kind.ADJUST),20);
                for(int i=0;i<64;i++)check(fair.poll(10,64).position()==i,"Same-item states retain coordinate order within batch");check(fair.poll(10,64).position()==999,"Continuously replenished held material cannot starve another item");
                var missing=new PrinterQueue(3);missing.offer(new PrinterQueue.Job(1,1,1,0,PrinterQueue.Kind.PLACE),1);missing.offer(new PrinterQueue.Job(1,2,2,0,PrinterQueue.Kind.PLACE),2);check(missing.poll(2,64).position()==2,"Available held item runs before missing-material head");
            });
            test("printer configured batches and actual dispatch limits",()->{
                var pacing=new PrinterPacing();int total=0;
                for(int tick=0;tick<=8;tick++){pacing.begin(tick,4,8,3,2,2);int sent=0;while(pacing.canRun(false)){pacing.dispatched(tick*100+sent,false);sent++;}check(sent==(tick%4==0?8:0),"Interval 4 permits complete batches on ticks 0/4/8");total+=sent;pacing.begin(tick,4,8,3,2,2);check(!pacing.canRun(false),"Repeated consumption in same tick cannot reset quota");}check(total==24,"Exact configured batch total");
                pacing.reset();pacing.begin(20,0,128,0,0,256);for(int i=0;i<128;i++){check(pacing.canRun(false),"No hidden 32-attempt quantity ceiling");pacing.dispatched(i,false);}check(!pacing.canRun(false),"Exact 128 limit enforced");
                pacing.reset();pacing.begin(30,0,0,0,0,1);for(int i=0;i<512;i++)pacing.dispatched(i,false);check(pacing.canRun(false),"Zero quantity leaves work bounded by caller time/queue budget");
                pacing.reset();pacing.begin(40,0,2,3,5,1);check(pacing.canRun(true),"First break available");pacing.dispatched(7,true);check(!pacing.canRun(true)&&pacing.canRun(false),"Unfinished dispatched break uses both shared and break quotas");pacing.dispatched(8,false);check(!pacing.canRun(false),"An ice placement returning WAIT still spends its dispatch");pacing.begin(41,0,2,3,5,1);check(pacing.canRun(false)&&!pacing.canRun(true),"Break interval does not block unrelated placement");pacing.begin(45,0,2,3,5,1);check(pacing.canRun(true),"Break resumes exactly at configured deadline");
            });
            test("printer dispatch cooldown survives retries and movement",()->{
                var pacing=new PrinterPacing();pacing.begin(100,1,8,6,1,1);pacing.dispatched(55,false);check(pacing.cooling(55),"Queued duplicate is blocked immediately after dispatch");
                for(int tick=101;tick<106;tick++){pacing.begin(tick,1,8,6,1,1);check(pacing.cooling(55),"Search cancellation and movement do not clear cooldown");}pacing.begin(106,1,8,6,1,1);check(!pacing.cooling(55),"Position becomes eligible at exact cooldown deadline");
                pacing.reset();pacing.begin(0,0,10,0,0,1);pacing.dispatched(55,false);check(!pacing.cooling(55),"Zero closes same-position cooldown without coercion to one");
                pacing.reset();for(int t=0;t<32;t++){pacing.begin(t,0,256,64,0,256);for(int i=0;i<256;i++)pacing.dispatched(t*256+i,false);}pacing.begin(32,0,256,64,0,256);check(!pacing.canDispatch(999999,false)&&pacing.cooling(0),"Full cooldown budget waits instead of forgetting an unexpired position");pacing.begin(64,0,256,64,0,256);check(pacing.canDispatch(999999,false)&&!pacing.cooling(0),"Expiry frees bounded capacity");
                check(PrinterRange.contains(new Vec3i(3,1,0),new Vec3i(1,1,0),3,PrinterRange.Shape.SPHERE),"Owned action remains in range after nearby movement");check(!PrinterRange.contains(new Vec3i(3,1,0),new Vec3i(10,1,0),3,PrinterRange.Shape.SPHERE),"Out-of-range owned action is not retained");
            });
            test("printer bounded fair queues and immutable snapshots",()->{
                var q=new PrinterQueue(3);var a=new PrinterQueue.Job(7,1,10,0,PrinterQueue.Kind.PLACE);var b=new PrinterQueue.Job(7,2,10,0,PrinterQueue.Kind.PLACE);var c=new PrinterQueue.Job(7,3,20,0,PrinterQueue.Kind.PLACE);
                check(q.offer(a)&&q.offer(b)&&q.offer(c),"Queue accepts capacity");check(!q.offer(a)&&!q.offer(new PrinterQueue.Job(7,4,30,0,PrinterQueue.Kind.PLACE)),"Duplicate and overflow rejected");
                check(q.poll().equals(a)&&q.poll().equals(c)&&q.poll().equals(b),"Material buckets make fair progress");check(q.poll()==null,"Queue drains");q.offer(a);q.clear();check(q.size()==0&&q.poll()==null,"Generation reset removes all queues");
                long[] positions={11,12,13,14};int[] desired={2,2,2,0},actual={0,2,0,3},flags={PrinterDiscovery.KNOWN|PrinterDiscovery.REPLACEABLE|PrinterDiscovery.ACTUAL_AIR,PrinterDiscovery.KNOWN|PrinterDiscovery.SAME_BLOCK,0,PrinterDiscovery.KNOWN|PrinterDiscovery.WANTED_AIR},scope={1,1,1,1},fill={4,4,4,4};
                var page=new PrinterDiscovery.Page(77,positions,desired,actual,flags,scope,fill);positions[0]=999;desired[0]=999;flags[2]=PrinterDiscovery.KNOWN|PrinterDiscovery.REPLACEABLE;
                var result=PrinterDiscovery.search(page,new PrinterDiscovery.Policy(true,false,false,false,false,false,false,false));
                check(result.generation()==77&&result.unknown()==1&&result.compared()==3&&result.matched()==1,"Unknown stays pending, air is not silently filled");
                check(result.jobs().size()==1&&result.jobs().get(0).position()==11&&result.jobs().get(0).expected()==2,"Worker sees owned snapshot");
                var breaking=PrinterDiscovery.search(page,new PrinterDiscovery.Policy(true,false,false,false,false,true,false,false));check(breaking.jobs().size()==2&&breaking.jobs().get(1).kind()==PrinterQueue.Kind.BREAK,"Extra blocks require explicit destruction option");
            });
            test("printer range shapes and all ordered reversals",()->{
                for(var shape:PrinterRange.Shape.values())for(String order:List.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX"))for(int reverse=0;reverse<8;reverse++){
                    var origin=new Vec3i(-33,15,91);var range=new PrinterRange(origin,2,shape,order,(reverse&1)!=0,(reverse&2)!=0,(reverse&4)!=0);Set<Vec3i> points=new HashSet<>();
                    while(range.hasNext()){var point=range.next();if(point!=null)check(points.add(point),"No duplicate position");}
                    int expected=shape==PrinterRange.Shape.CUBE?125:shape==PrinterRange.Shape.SPHERE?33:25;check(points.size()==expected&&points.contains(origin),"Shape volume independent of traversal order");
                }
                var huge=new PrinterRange(Vec3i.ZERO,256,PrinterRange.Shape.CUBE,"XYZ",false,false,false);check(huge.volume()==135005697L&&huge.visited()==0,"Maximum range is lazy");
                expect(IllegalArgumentException.class,()->new PrinterRange(Vec3i.ZERO,Double.NaN,PrinterRange.Shape.CUBE,"XYZ",false,false,false));
            });
            test("layer-clipped bounds agree with transformed cells",()->{
                var region=new Region("r",new Vec3i(-3,-2,1),new Vec3i(7,6,5));
                for(int turn=0;turn<4;turn++)for(int mirror=0;mirror<4;mirror++)for(var axis:LayerRange.Axis.values()){
                    var transform=new PlacementTransform(new Vec3i(0,0,0),turn,(mirror&1)!=0,(mirror&2)!=0);var layer=new LayerRange(axis,0,2);var bounds=PlacementBounds.clipped(region,transform,layer);int n=0;
                    for(int x=-3;x<4;x++)for(int y=-2;y<4;y++)for(int z=1;z<6;z++){var point=transform.apply(new Vec3i(x,y,z));if(layer.contains(point)){n++;check(bounds!=null&&point.x()>=bounds.min().x()&&point.x()<=bounds.max().x()&&point.y()>=bounds.min().y()&&point.y()<=bounds.max().y()&&point.z()>=bounds.min().z()&&point.z()<=bounds.max().z(),"Clipped bounds retain exactly eligible cells");}}
                    check((bounds==null)==(n==0),"Empty clipping is explicit");
                }
                var tall=new Region("580 high",Vec3i.ZERO,new Vec3i(809,580,726));var transform=new PlacementTransform(new Vec3i(52,55,193),1,true,false);var clipped=PlacementBounds.clipped(tall,transform,new LayerRange(LayerRange.Axis.Y,64,64));check(clipped.min().y()==64&&clipped.max().y()==64,"Legal layer of oversized source has legal world bounds");
            });
            test("unloaded discovery cannot occupy every queue slot",()->{
                var q=new DeferredSections(List.of(new Region("65536 sections",Vec3i.ZERO,new Vec3i(4096,16,4096))));
                for(int i=0;i<128;i++)q.admit(512,key->key.z()==250);
                check(q.pending()==256,"Late loaded row is admitted past first 32768 unavailable sections");
                var first=q.poll(0);q.unavailable(first);check(q.pending()==255,"Untouched unloaded work releases slot");
                while(q.pending()>0){var work=q.poll(0);work.done.set(0,4096);q.defer(work);}
                check(!q.finished(),"Unloaded regions are not reported complete");
                for(int i=0;i<128;i++)q.admit(512,key->key.equals(first.key));check(q.pending()==1,"Unloaded work is revisited without duplicating completed sections");
                q.clear();check(q.finished(),"Cancellation clears discovery");
                var partial=new DeferredSections(List.of(new Region("unaligned",Vec3i.ZERO,new Vec3i(31,17,19))));partial.admit(1);var work=partial.poll(0);work.done.set(0);work.done.set(4095);partial.unavailable(work);check(partial.pending()==0&&!partial.finished(),"Partially scanned unloaded work also releases its slot");
                partial.admit(32,key->key.equals(work.key));var resumed=partial.poll(0);check(resumed!=null&&resumed.done.get(0)&&resumed.done.get(4095)&&resumed.cursor==0,"Sparse parked progress survives source-coordinate transfer");
            });
            test("system UI font preference and missing-font fallbacks",()->{
                check(OutlineFont.selectSystemFamily(new String[]{"SimHei","PingFang SC","Microsoft YaHei"},n->true).equals("Microsoft YaHei"),"YaHei first regardless of enumeration order");
                check(OutlineFont.selectSystemFamily(new String[]{"SimHei","PingFang SC"},n->true).equals("SimHei"),"SimHei fallback");
                check(OutlineFont.selectSystemFamily(new String[]{"PingFang SC"},n->true).equals("PingFang SC"),"PingFang fallback");
                check(OutlineFont.selectSystemFamily(new String[]{"微软雅黑","黑体"},n->true).equals("微软雅黑"),"Localized family names");
                check(OutlineFont.selectSystemFamily(new String[]{"microsoft yahei","SimHei"},n->true).equals("microsoft yahei"),"Case-insensitive real family match");
                check(OutlineFont.selectSystemFamily(new String[]{"Microsoft YaHei","SimHei"},n->!n.equals("Microsoft YaHei")).equals("SimHei"),"Skip font missing required glyphs");
                check(OutlineFont.selectSystemFamily(new String[]{"Other CJK"},n->n.equals("Other CJK")).equals("Other CJK"),"Other installed CJK fallback");
                check(OutlineFont.selectSystemFamily(new String[0],n->false).equals(java.awt.Font.DIALOG),"No font never invents an installed family");
            });
            test("canonical block-state identity",()->{
                var a=BlockStateSpec.parse("oak_stairs[half=bottom,facing=east]");var b=BlockStateSpec.parse("minecraft:oak_stairs[facing=east,half=bottom]");
                check(a.equals(b),"Canonical equality");check(a.toString().equals("minecraft:oak_stairs[facing=east,half=bottom]"),"Sorted output");
            });
            test("state immutability and duplicate rejection",()->{
                Map<String,String> p=new HashMap<>();p.put("axis","x");var state=new BlockStateSpec("oak_log",p);p.put("axis","z");check(state.properties().get("axis").equals("x"),"Defensive copy");
                expect(UnsupportedOperationException.class,()->state.properties().put("axis","z"));
                expect(IllegalArgumentException.class,()->BlockStateSpec.parse("stone[x=a,x=b]"));expect(IllegalArgumentException.class,()->BlockStateSpec.parse("minecraft:Stone"));
            });
            test("air is namespace-specific",()->{check(BlockStateSpec.parse("minecraft:cave_air").isAir(),"Cave air");check(!BlockStateSpec.parse("custom:air").isAir(),"Modded name is not silently air");});
            test("packed bits: 31 widths and cross-word boundaries",()->{
                Random random=new Random(114514);for(int bits=1;bits<=31;bits++){int[] values=new int[10003];long mask=(1L<<bits)-1;for(int i=0;i<values.length;i++)values[i]=(int)(random.nextInt()&mask);
                    PackedBits packed=PackedBits.pack(bits,values);for(int i=0;i<values.length;i++)check(packed.get(i)==values[i],"Packed value "+bits+"/"+i);
                    PackedBits generated=PackedBits.generate(bits,values.length,i->values[i]);check(Arrays.equals(packed.copyWords(),generated.copyWords()),"Generator parity");}
            });
            test("packed bits: guards and ownership",()->{
                expect(IllegalArgumentException.class,()->PackedBits.pack(2,new int[]{4}));expect(IllegalArgumentException.class,()->new PackedBits(2,33,new long[1]));
                long[] data={3};PackedBits packed=new PackedBits(2,1,data);data[0]=0;check(packed.get(0)==3,"Constructor copy");
                check(PackedBits.takeOwnership(2,1,new long[]{3}).get(0)==3,"Transferred buffer decodes correctly");expect(IllegalArgumentException.class,()->PackedBits.takeOwnership(2,33,new long[1]));expect(IndexOutOfBoundsException.class,()->packed.get(1));
            });
            test("local section palette roundtrip",()->{
                int[] ids=new int[4096];for(int i=0;i<ids.length;i++)ids[i]=(i*17)%239;var p=PackedSection.fromGlobalIds(ids);
                ByteArrayOutputStream b=new ByteArrayOutputStream();p.write(new DataOutputStream(b));var q=PackedSection.read(new DataInputStream(new ByteArrayInputStream(b.toByteArray())),256);
                for(int i=0;i<ids.length;i++)check(q.globalId(i)==ids[i],"Section roundtrip");check(p.estimatedBytes()<16384,"Local packing");
            });
            test("section YZX addressing and bounds",()->{check(PackedSection.index(2,3,4)==2+64+768,"YZX");expect(IndexOutOfBoundsException.class,()->PackedSection.index(16,0,0));check(PackedSection.fromGlobalIds(new int[4096]).isCanonicalAir(),"Air omission");});
            test("sparse section traversal, resume and wire roundtrip",()->{
                Random random=new Random(260921);
                for(int density:new int[]{0,1,20,70,100}){
                    int[] cells=new int[4096];for(int i=0;i<cells.length;i++)if(random.nextInt(100)<density)cells[i]=1+random.nextInt(300);
                    if(density>0){cells[0]=2;cells[63]=1;cells[64]=2;cells[4095]=3;}
                    var packed=PackedSection.fromGlobalIds(cells);var bytes=new ByteArrayOutputStream();packed.write(new DataOutputStream(bytes));
                    var restored=PackedSection.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),301);
                    int next=4096;for(int i=4096;i>=0;i--){if(i<4096&&cells[i]!=0)next=i;check(packed.nextNonZero(i)==next,"Constructed sparse cursor");check(restored.nextNonZero(i)==next,"Decoded sparse cursor");}
                    int seen=0;for(int i=restored.nextNonZero(0);i<4096;i=restored.nextNonZero(i+1)){check(restored.globalId(i)==cells[i],"Same IDs in YZX order");seen++;}
                    check(seen==Arrays.stream(cells).filter(v->v!=0).count(),"No omissions or duplicate visits");
                    expect(IndexOutOfBoundsException.class,()->packed.nextNonZero(-1));expect(IndexOutOfBoundsException.class,()->packed.nextNonZero(4097));
                    check(restored.estimatedBytes()==packed.estimatedBytes(),"Derived bitmap included in cache budget");
                }
            });
            test("sparse render traversal preserves transforms and layers",()->{
                var palette=List.of(BlockStateSpec.AIR,BlockStateSpec.parse("stone"),BlockStateSpec.parse("minecraft:cave_air"),BlockStateSpec.parse("minecraft:light[level=7]"));
                int[] cells=new int[4096];for(int i=0;i<cells.length;i++)cells[i]=i%5==0?1+i%3:0;
                var packed=PackedSection.fromGlobalIds(cells);Vec3i base=new Vec3i(-31,8,127);
                for(int turn=0;turn<4;turn++)for(boolean mx:new boolean[]{false,true})for(boolean mz:new boolean[]{false,true}){
                    var transform=new PlacementTransform(new Vec3i(52,55,193),turn,mx,mz);
                    for(var range:List.of(LayerRange.ALL,new LayerRange(LayerRange.Axis.X,-100,100),new LayerRange(LayerRange.Axis.Y,67,69),new LayerRange(LayerRange.Axis.Z,50,350))){
                        var expected=new ArrayList<Vec3i>();var actual=new ArrayList<Vec3i>();
                        for(int i=0;i<4096;i++){var world=transform.apply(base.add(new Vec3i(i&15,i>>>8,(i>>>4)&15)));if(!palette.get(packed.globalId(i)).isAir()&&range.contains(world))expected.add(world);}
                        for(int i=packed.nextNonZero(0);i<4096;i=packed.nextNonZero(i+1)){if(palette.get(packed.globalId(i)).isAir())continue;var world=transform.apply(base.add(new Vec3i(i&15,i>>>8,(i>>>4)&15)));if(range.contains(world))actual.add(world);}
                        check(actual.equals(expected),"Exact ordered world positions for rotations, mirrors, layers and noncanonical air");
                    }
                }
            });
            test("placement inverse: mirrors and 4 rotations",()->{
                for(int turn=0;turn<4;turn++)for(boolean mx:new boolean[]{false,true})for(boolean mz:new boolean[]{false,true}){
                    var t=new PlacementTransform(new Vec3i(-67,64,113),turn,mx,mz);
                    for(int i=-100;i<100;i++){var p=new Vec3i(i,i%15,i*3);check(t.inverse(t.apply(p)).equals(p),"Transform inverse");}
                }
            });
            test("coordinate overflow is rejected",()->{expect(ArithmeticException.class,()->new Vec3i(Integer.MAX_VALUE,0,0).add(new Vec3i(1,0,0)));expect(IllegalArgumentException.class,()->new Region("bad",Vec3i.ZERO,new Vec3i(0,1,1)));});
            test("world-unknown comparison is not missing",()->{
                var stone=BlockStateSpec.parse("stone");check(Comparison.compare(stone,null,false)==Comparison.UNKNOWN,"Unknown chunk");check(Comparison.compare(stone,BlockStateSpec.AIR,true)==Comparison.MISSING,"Missing");
                check(Comparison.compare(BlockStateSpec.AIR,stone,true)==Comparison.EXTRA,"Extra");check(Comparison.compare(stone,BlockStateSpec.parse("glass"),true)==Comparison.WRONG_BLOCK,"Wrong type");
                check(Comparison.compare(BlockStateSpec.parse("oak_log[axis=x]"),BlockStateSpec.parse("oak_log[axis=y]"),true)==Comparison.WRONG_STATE,"Wrong state");
            });
            test("axis layers and inverted range rejection",()->{check(new LayerRange(LayerRange.Axis.X,-2,2).contains(new Vec3i(-2,100,100)),"X inclusive");check(!new LayerRange(LayerRange.Axis.Z,4,4).contains(new Vec3i(0,0,3)),"Z range");expect(IllegalArgumentException.class,()->new LayerRange(LayerRange.Axis.Y,3,2));});
            test("LRU byte budget, order and release",()->{
                List<Integer> released=new ArrayList<>();try(var lru=new WeightedLru<String,Integer>(10,Integer::longValue,released::add)){
                    lru.put("a",4);lru.put("b",4);lru.get("a");lru.put("c",4);check(lru.contains("a")&&!lru.contains("b"),"Access-order eviction");check(lru.usedBytes()==8,"Byte count");
                    check(!lru.put("too-big",11),"Oversize rejected");check(lru.usedBytes()<=10,"Budget invariant");
                }check(released.size()==4,"Exactly-once release including oversize");
            });
            test("LRU entry cap and zero-weight rejection",()->{
                try(var cache=new WeightedLru<String,Integer>(100,2,Integer::longValue,x->{})){cache.put("a",1);cache.put("b",1);cache.put("c",1);check(cache.size()==2&&!cache.contains("a"),"Entry bound independent of bytes");expect(IllegalArgumentException.class,()->cache.put("zero",0));}
            });
            test("generate and parse independent litematic fixture",()->{
                source=write("normal.litematic",Fixtures.litematic(Map.of("r",Fixtures.region(-5,2,7,33,18,17,i->i%4)),"normal"));expectedHash=SchematicImporter.sha256(source,Cancellation.NEVER);
                check(NbtReader.integer(NbtReader.read(source,NbtReader.Limits.DEFAULT,Cancellation.NEVER),"Version")==6,"NBT parse");cachePath=imported(source);
            });
            test("cache metadata, spatial domain and every cell",()->{
                try(var cache=BlueprintCache.open(cachePath)){
                    check(cache.metadata().sourceSha256().equals(expectedHash),"Content address");Region r=cache.metadata().regions().get(0);check(r.min().equals(new Vec3i(-5,2,7)),"Origin");
                    Map<SectionKey,PackedSection> decoded=new HashMap<>();for(var key:cache.index().keySet())decoded.put(key,cache.read(key));
                    for(int y=0;y<18;y++)for(int z=0;z<17;z++)for(int x=0;x<33;x++){
                        int id=decoded.get(new SectionKey(0,x/16,y/16,z/16)).globalId(x%16,y%16,z%16);String actual=cache.metadata().palette().get(id).name();
                        check(actual.equals(new String[]{"minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs"}[(x+z*33+y*33*17)%4]),"World layout");
                    }
                    check(Arrays.stream(cache.copyBlockStateCounts()).sum()==33L*18*17,"Exact counts including air");
                }
            });
            test("concurrent cache reads preserve section identity and checksums",()->{
                var cache=BlueprintCache.open(cachePath);var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
                try{
                    var keys=new ArrayList<>(cache.index().keySet());var expected=new HashMap<SectionKey,int[]>();
                    for(var key:keys){var section=cache.read(key);int[] cells=new int[4096];for(int i=0;i<4096;i++)cells[i]=section.globalId(i);expected.put(key,cells);}
                    var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
                    for(int batch=0;batch<8;batch++){int offset=batch;futures.add(workers.submit(()->{
                        for(int round=0;round<8;round++)for(int k=0;k<keys.size();k++){
                            var key=keys.get((k+offset)%keys.size());var section=cache.read(key);var cells=expected.get(key);
                            for(int i=0;i<4096;i++)if(section.globalId(i)!=cells[i])return false;
                        }return true;
                    }));}
                    for(var result:futures)check(result.get(10,java.util.concurrent.TimeUnit.SECONDS),"Parallel reads stay byte-equivalent");
                    cache.close();expect(IOException.class,()->cache.read(keys.get(0)));
                }finally{workers.shutdownNow();cache.close();}
            });
            test("whole-file histogram exceeds preview residency",()->{
                Path p=write("wide-count.litematic",Fixtures.litematic(Map.of("wide",Fixtures.region(0,0,0,9000,1,1,i->1)),"wide"));
                try(var c=BlueprintCache.open(imported(p))){
                    check(c.index().size()>512,"Source spans more than preview limit");
                    check(new SpatialIndex(c,Cancellation.NEVER).nearest(new Vec3i(4500,0,0),512,8192).size()>32,"Expanded query covers distant regions");
                    check(c.copyBlockStateCounts()[1]==9000,"Histogram includes distant unrendered cells");
                    check(Arrays.stream(c.copyBlockStateCounts()).sum()==9000,"No section padding counted");
                }
            });
            test("deferred sections retry without blocking or duplication",()->{
                var q=new DeferredSections(List.of(new Region("r",Vec3i.ZERO,new Vec3i(33,1,1))));q.admit(8);
                check(q.pending()==3,"Partial boundary section admitted");var delayed=q.poll(0);delayed.done.set(7);delayed.retryAt=20;q.defer(delayed);
                var ready=q.poll(0);check(ready.key.x()==1,"Waiting chunk does not block loaded work");ready.done.set(0,4096);q.defer(ready);
                ready=q.poll(0);check(ready.key.x()==2,"Later section remains eligible");ready.done.set(0,4096);q.defer(ready);
                check(q.poll(19)==null&&!q.finished(),"Unloaded work remains pending");ready=q.poll(20);
                check(ready.key.x()==0&&ready.done.get(7),"Loaded retry retains completed cells");ready.done.set(0,4096);q.defer(ready);check(q.finished(),"No duplicate completed work");
                var large=new DeferredSections(List.of(new Region("huge",Vec3i.ZERO,new Vec3i(1048576,16,16))));large.admit(Integer.MAX_VALUE);
                check(large.pending()==DeferredSections.LIMIT,"Queue stays bounded");large.clear();check(large.finished(),"Cancellation releases queue");
            });
            test("warm cache bypasses NBT conversion",()->{check(SchematicImporter.importFile(source,temp.resolve("cache"),Cancellation.NEVER,p->{}).cacheHit(),"Cache hit");});
            test("negative dimensions normalize without reversing data",()->{
                Path p=write("negative.litematic",Fixtures.litematic(Map.of("r",Fixtures.region(10,20,30,-4,-3,-2,i->i%4)),"negative"));
                try(var c=BlueprintCache.open(imported(p))){check(c.metadata().regions().get(0).min().equals(new Vec3i(7,18,29)),"Negative min");var section=c.read(new SectionKey(0,0,0,0));check(c.metadata().palette().get(section.globalId(3,2,1)).name().equals("minecraft:oak_stairs"),"Negative packed order");}
            });
            test("multiple disjoint subregions retained",()->{
                Path p=write("multi.litematic",Fixtures.litematic(Map.of("a",Fixtures.region(0,0,0,2,2,2,i->1),"b",Fixtures.region(100,0,0,2,2,2,i->2)),"multi"));
                try(var c=BlueprintCache.open(imported(p))){check(c.metadata().regions().size()==2,"Region count");check(c.index().size()==2,"Independent sections");}
            });
            test("overlap retained with deterministic source region order",()->{
                Path p=write("overlap.litematic",Fixtures.litematic(Map.of("a",Fixtures.region(0,0,0,2,2,2,i->1),"b",Fixtures.region(1,0,0,2,2,2,i->2)),"overlap"));try(var c=BlueprintCache.open(imported(p))){check(c.metadata().regions().size()==2,"Overlapping regions retained");check(c.regionCounts().get(0).total()==8&&c.regionCounts().get(1).total()==8,"Per-region source totals");}
            });
            test("all-air region retains bounds with zero disk sections",()->{
                Path p=write("air.litematic",Fixtures.litematic(Map.of("a",Fixtures.region(0,0,0,16,16,16,i->0)),"air"));try(var c=BlueprintCache.open(imported(p))){check(c.index().isEmpty(),"No air payload");check(c.metadata().regions().get(0).contains(new Vec3i(15,15,15)),"Expected-air domain");check(c.copyBlockStateCounts()[0]==4096,"Air count");}
            });
            for(int version:new int[]{2,3})test("Sponge v"+version+": sparse palette IDs and varints",()->{
                Path p=write("sponge"+version+".schem",Fixtures.sponge(version,17,3,18,i->i%4,new int[]{7,42,300,999}));try(var c=BlueprintCache.open(imported(p))){
                    check(c.metadata().regions().get(0).min().equals(new Vec3i(-3,2,5)),"Offset retained");
                    for(int i=0;i<17*3*18;i++){int x=i%17,z=(i/17)%18,y=i/(17*18);int id=c.read(new SectionKey(0,x/16,y/16,z/16)).globalId(x%16,y%16,z%16);
                        check(c.metadata().palette().get(id).name().equals(new String[]{"minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs"}[i%4]),"Sparse palette mapping");}
                }
            });
            test("Sponge unsigned-short width > 32767",()->{
                Path p=write("wide.schem",Fixtures.sponge(3,40000,1,1,i->i==39999?1:0,new int[]{0,1,2,3}));try(var c=BlueprintCache.open(imported(p))){check(c.metadata().regions().get(0).size().x()==40000,"Unsigned width");check(c.index().size()==1,"Sparse disk storage");}
            });
            test("unknown format version rejected",()->{
                var root=Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,2,2,2,i->1)),"future");root.put("Version",999);Path p=write("future.litematic",root);expect(IOException.class,()->imported(p));
            });
            test("truncated litematic bit array rejected",()->{
                var r=Fixtures.region(0,0,0,8,8,8,i->1);r.put("BlockStates",new long[1]);Path p=write("short.litematic",Fixtures.litematic(Map.of("r",r),"short"));expect(IOException.class,()->imported(p));
            });
            test("oversize dimensions rejected before section allocation",()->{
                var r=Fixtures.region(0,0,0,1,1,1,i->1);r.put("Size",Fixtures.xyz(100000,100000,100000));Path p=write("huge.litematic",Fixtures.litematic(Map.of("r",r),"huge"));expect(IOException.class,()->imported(p));
            });
            test("Sponge varint truncation and overflow rejected",()->{
                for(byte[] bad:new byte[][]{new byte[]{(byte)128},new byte[]{(byte)255,(byte)255,(byte)255,(byte)255,127}}){
                    var root=Fixtures.sponge(2,1,1,1,i->1,new int[]{0,1,2,3});root.put("BlockData",bad);Path p=write("badvarint"+bad.length+".schem",root);expect(IOException.class,()->imported(p));
                }
            });
            test("NBT decompression and allocation limits",()->{
                expect(IOException.class,()->NbtReader.read(source,new NbtReader.Limits(100,1_000_000,64,100000),Cancellation.NEVER));
                expect(IOException.class,()->NbtReader.read(source,new NbtReader.Limits(1_000_000,100,64,100000),Cancellation.NEVER));
            });
            test("NBT nesting limit",()->{Map<String,Object> root=new LinkedHashMap<>();Map<String,Object> at=root;for(int i=0;i<80;i++){Map<String,Object> child=new LinkedHashMap<>();at.put("x",child);at=child;}Path p=write("deep.nbt",root);expect(IOException.class,()->NbtReader.read(p,NbtReader.Limits.DEFAULT,Cancellation.NEVER));});
            test("cancellation before and during import leaves no partial",()->{
                expect(InterruptedIOException.class,()->SchematicImporter.importFile(source,temp.resolve("cancelled"),()->true,p->{}));
                AtomicBoolean stop=new AtomicBoolean();expect(InterruptedIOException.class,()->SchematicImporter.importFile(source,temp.resolve("cancel-mid"),stop::get,p->{if(p.phase().equals("index sections"))stop.set(true);}));
                try(var files=Files.walk(temp)){check(files.noneMatch(p->p.toString().endsWith(".part")),"Temporary files cleaned");}
            });
            test("cache payload corruption detected",()->{
                Path corrupted=temp.resolve("corrupt.bpc");Files.copy(cachePath,corrupted);BlueprintCache.Entry entry;
                try(var c=BlueprintCache.open(corrupted)){entry=c.index().values().iterator().next();}
                try(RandomAccessFile file=new RandomAccessFile(corrupted.toFile(),"rw")){file.seek(entry.offset()+entry.compressedBytes()/2);int value=file.read();file.seek(file.getFilePointer()-1);file.write(value^0xff);}
                try(var c=BlueprintCache.open(corrupted)){expect(IOException.class,()->c.read(entry.key()));}
            });
            test("truncated cache directory detected",()->{
                Path p=temp.resolve("truncated.bpc");Files.copy(cachePath,p);try(RandomAccessFile f=new RandomAccessFile(p.toFile(),"rw")){f.setLength(f.length()-1);}expect(IOException.class,()->BlueprintCache.open(p));
            });
            test("read after cache close fails explicitly",()->{var c=BlueprintCache.open(cachePath);var key=c.index().keySet().iterator().next();c.close();c.close();expect(IOException.class,()->c.read(key));});
            test("spatial queries obey nearest-result cap",()->{
                try(var c=BlueprintCache.open(cachePath)){var index=new SpatialIndex(c,Cancellation.NEVER);var keys=index.nearest(new Vec3i(0,8,10),96,3);check(keys.size()==3,"Nearest cap");check(index.nearest(new Vec3i(10000,0,0),96,3).isEmpty(),"No full-world fallback");}
            });
            test("shared source leases preserve surviving readers",()->{
                var cache=BlueprintCache.open(cachePath);var owner=new LoadCoordinator.Loaded(cache,new SpatialIndex(cache,Cancellation.NEVER),null,"",true,0);var a=owner.retain();var b=owner.retain();check(a.cache()==b.cache()&&a.stream()==b.stream()&&a.index()==b.index(),"Instances share source index and decoded cache");owner.close();a.close();a.close();var key=cache.index().keySet().iterator().next();check(b.cache().read(key)!=null,"Closing one placement cannot close another reader");b.close();expect(IOException.class,()->cache.read(key));expect(IllegalStateException.class,b::retain);
            });
            test("layer modes and invalid selection bounds",()->{
                var below=LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.BELOW,64,0);check(below.shifted(1).min()==Integer.MIN_VALUE&&below.shifted(1).max()==65,"Below layer moves finite boundary only");var above=LayerRange.of(LayerRange.Axis.X,LayerRange.Mode.ABOVE,-10,0);check(above.shifted(-1).max()==Integer.MAX_VALUE&&above.shifted(-1).min()==-11,"Above layer preserves infinite boundary");check(LayerRange.ALL.shifted(1).equals(LayerRange.ALL),"All layers has no arithmetic overflow");expect(IllegalArgumentException.class,()->AreaSelection.EMPTY.add(new SelectionBox("bad",Vec3i.ZERO,new Vec3i(1_048_576,0,0))));
            });
            test("bounded section streamer decode and ownership",()->{
                try(var stream=new SectionStreamer(BlueprintCache.open(cachePath),65536)){
                    List<SectionKey> keys=new ArrayList<>(stream.source().index().keySet());for(var key:keys)stream.request(key);long until=System.nanoTime()+5_000_000_000L;
                    while(System.nanoTime()<until){stream.drain();if(stream.get(keys.get(0))!=null)break;Thread.sleep(2);}
                    check(stream.get(keys.get(0))!=null,"Async decode");check(stream.cachedBytes()<=65536,"CPU memory bound");check(stream.queuedJobs()<=34,"Task bound");stream.cancelPending();
                }
            });
            test("decode backpressure retains work and cancellation resumes",()->{
                Path path=write("backpressure.litematic",Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,16*48,1,1,i->1)),"backpressure"));
                try(var stream=new SectionStreamer(BlueprintCache.open(imported(path)),1L<<20)){
                    var keys=new ArrayList<>(stream.source().index().keySet()).subList(0,24);
                    for(var key:keys)check(stream.request(key),"Bounded batch accepted");
                    Thread.sleep(100); // Deliberately stop consuming to fill the 16-result queue.
                    check(stream.queuedJobs()==24,"Full ready queue cannot discard successful decodes");
                    for(var key:keys)check(!stream.request(key),"No duplicate decode while consumer is paused");
                    stream.cancelPending();check(stream.queuedJobs()==0,"Cancellation releases old requests");
                    for(var key:keys)check(stream.request(key),"New generation can submit the same keys");
                    long until=System.nanoTime()+5_000_000_000L;boolean done=false;
                    while(System.nanoTime()<until){stream.drain();done=keys.stream().allMatch(k->stream.get(k)!=null);if(done&&stream.queuedJobs()==0)break;Thread.sleep(2);}
                    check(done&&stream.queuedJobs()==0,"Blocked old publishers cannot strand new work");
                    check(stream.cachedBytes()<=1L<<20,"Backpressure still respects CPU budget");
                }
            });
            test("load generation: last request wins",()->{
                Path latest=write("latest.litematic",Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,2,2,2,i->2)),"latest"));
                try(var loader=new LoadCoordinator()){
                    for(int i=0;i<30;i++){loader.request(source,temp.resolve("async-cache"));loader.cancel();}
                    loader.request(source,temp.resolve("async-cache"));loader.request(latest,temp.resolve("async-cache"));
                    LoadCoordinator.Loaded loaded=null;long until=System.nanoTime()+10_000_000_000L;
                    while(System.nanoTime()<until&&(loaded=loader.poll())==null){if(loader.status().phase().equals("failed"))throw new AssertionError(loader.status().error());Thread.sleep(2);}
                    check(loaded!=null,"Latest request completed");try(var result=loaded){check(result.cache().metadata().name().equals("latest"),"No stale publication");}
                }
            });
            test("placement identity, lock and independent duplicate",()->{
                var p=new Placement(UUID.randomUUID(),"Tower","folder/demo.litematic",new PlacementTransform(new Vec3i(-12,64,97),1,true,false),true,true);
                expect(IllegalStateException.class,()->p.placed(new PlacementTransform(Vec3i.ZERO,0,false,false)));
                var copy=p.duplicate();check(!copy.id().equals(p.id()),"Duplicate has a new identity");check(!copy.locked(),"Duplicate starts unlocked");
                check(copy.transform().equals(p.transform()),"Duplicate keeps transform");check(!copy.named("Other").name().equals(p.name()),"Rename is independent");
                check(p.locked(false).placed(new PlacementTransform(Vec3i.ZERO,0,false,false)).transform().origin().equals(Vec3i.ZERO),"Unlock permits movement");
            });
            test("placement source paths cannot escape root",()->{
                for(String path:List.of("../a.litematic","/a.litematic","C:/a.litematic","a/../../b.schem","a//b.schem","a\\..\\b.schem"))
                    expect(IllegalArgumentException.class,()->new Placement(UUID.randomUUID(),"bad",path,new PlacementTransform(Vec3i.ZERO,0,false,false),true,false));
            });
            test("session snapshot validates identities and bounds",()->{
                var p=new Placement(UUID.randomUUID(),"A","a.schem",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
                expect(IllegalArgumentException.class,()->new PlacementSession(List.of(p,p),p.id(),LayerRange.ALL,0.5f,true));
                expect(IllegalArgumentException.class,()->new PlacementSession(List.of(p),UUID.randomUUID(),LayerRange.ALL,0.5f,true));
                expect(IllegalArgumentException.class,()->new PlacementSession(List.of(p),p.id(),LayerRange.ALL,Float.NaN,true));
                List<Placement> list=new ArrayList<>();for(int i=0;i<9;i++)list.add(p.duplicate());
                check(new PlacementSession(list,null,LayerRange.ALL,0.5f,true).placements().size()==9,"Session accepts more than eight independent identities");
                var snapshot=new PlacementSession(List.of(p),p.id(),LayerRange.ALL,0.5f,true);
                expect(UnsupportedOperationException.class,()->snapshot.placements().clear());
            });
            test("session restart roundtrip and atomic replacement",()->{
                List<Placement> list=new ArrayList<>();
                for(int i=0;i<8;i++)list.add(new Placement(UUID.randomUUID(),"建筑 "+i,"子目录/demo.litematic",new PlacementTransform(new Vec3i(-100+i,64,300),i,i%2==0,i%3==0),i%2==0,i%3==0));
                var session=new PlacementSession(list,list.get(4).id(),new LayerRange(LayerRange.Axis.X,-8,17),0.75f,false);
                Path file=temp.resolve("settings/world-dimension.blps");PlacementStore.write(file,session);
                check(PlacementStore.read(file).equals(session),"All placement/global fields survive disk reload");
                PlacementStore.write(file,PlacementSession.EMPTY);check(PlacementStore.read(file).equals(PlacementSession.EMPTY),"Explicit deletion persists");
                check(PlacementStore.read(temp.resolve("absent.blps")).equals(PlacementSession.EMPTY),"New world starts empty");
            });
            test("independent opacity and legacy migration",()->{
                var p=new Placement(UUID.randomUUID(),"Alpha","a.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false).opacity(0.05f);
                var q=p.duplicate().opacity(0.85f);
                var session=new PlacementSession(List.of(p,q),p.id(),LayerRange.ALL,0.45f,true);
                Path file=temp.resolve("opacity.blps");PlacementStore.write(file,session);
                check(PlacementStore.read(file).equals(session),"Distinct opacity survives reload");
                check(p.named("Changed").enabled(false).locked(true).duplicate().opacity()==0.05f,"Edits preserve opacity");
                expect(IllegalArgumentException.class,()->p.opacity(Float.NaN));
                expect(IllegalArgumentException.class,()->p.opacity(0));
                expect(IllegalArgumentException.class,()->p.opacity(1.01f));
                var bytes=new java.io.ByteArrayOutputStream();
                try(var out=new java.io.DataOutputStream(bytes)){
                    out.writeInt(0x424c5053);out.writeInt(1);out.writeBoolean(false);
                    out.writeByte(LayerRange.ALL.axis().ordinal());out.writeInt(LayerRange.ALL.min());out.writeInt(LayerRange.ALL.max());
                    out.writeFloat(0.7f);out.writeBoolean(true);out.writeInt(1);
                    out.writeLong(p.id().getMostSignificantBits());out.writeLong(p.id().getLeastSignificantBits());out.writeUTF(p.name());out.writeUTF(p.source());
                    out.writeInt(0);out.writeInt(0);out.writeInt(0);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(false);
                    var crc=new java.util.zip.CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());
                }
                Files.write(file,bytes.toByteArray());var legacy=PlacementStore.read(file);
                check(legacy.placements().get(0).opacity()==0.7f,"Legacy inherits global opacity");
                PlacementStore.write(file,legacy);check(PlacementStore.read(file).equals(legacy),"Migration persists");
            });
            test("corrupt and future session files preserved",()->{
                Path file=temp.resolve("corrupt.blps");PlacementStore.write(file,PlacementSession.EMPTY);byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
                expect(IOException.class,()->PlacementStore.read(file));check(Arrays.equals(bytes,Files.readAllBytes(file)),"Read failure preserves bytes");
                bytes[7]=3;Files.write(file,bytes);expect(IOException.class,()->PlacementStore.read(file));
                Files.write(file,new byte[4]);expect(IOException.class,()->PlacementStore.read(file));
                Files.write(file,new byte[128*1024+1]);expect(IOException.class,()->PlacementStore.read(file));
            });
            test("async session world ordering and file browsing",()->{
                Path root=temp.resolve("browser");Files.createDirectories(root.resolve("sub"));Files.write(root.resolve("demo.litematic"),new byte[0]);Files.write(root.resolve("ignore.txt"),new byte[0]);
                var p=new Placement(UUID.randomUUID(),"A","demo.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
                var session=new PlacementSession(List.of(p),p.id(),LayerRange.ALL,0.5f,true);
                Path a=temp.resolve("world-a.blps"),b=temp.resolve("world-b.blps");
                try(var io=new SessionIo()){
                    io.save(a,session);io.save(b,PlacementSession.EMPTY);
                    check(io.read(a).get(5,java.util.concurrent.TimeUnit.SECONDS).equals(session),"Return to world reads after pending save");
                    check(io.read(b).get(5,java.util.concurrent.TimeUnit.SECONDS).equals(PlacementSession.EMPTY),"World isolation");
                    var listing=io.list(root,"").get(5,java.util.concurrent.TimeUnit.SECONDS);check(listing.entries().size()==2,"Only folders and supported formats");check(listing.entries().get(0).directory(),"Folders first");
                    expect(java.util.concurrent.ExecutionException.class,()->io.list(root,"..").get(5,java.util.concurrent.TimeUnit.SECONDS));
                    check(io.takeError()==null,"No save failure");
                }
            });
            test("inclusive selection corners and persistent regions",()->{
                var box=new SelectionBox("a",new Vec3i(5,8,9),new Vec3i(3,7,6));check(box.region().volume()==24,"Inclusive reversed corners");
                var area=new AreaSelection(List.of(box),"a",new Vec3i(3,7,6),false).put(new SelectionBox("b",new Vec3i(30,0,0),new Vec3i(31,1,1)));
                Path file=temp.resolve("areas.nbt");SelectionStore.write(file,area);check(SelectionStore.read(file).equals(area),"Selection roundtrip");
                expect(IllegalStateException.class,area::toggleMode);check(area.remove().boxes().size()==1,"Remove only selected box");
                check(box.translate(new Vec3i(4,0,0)).region().volume()==box.region().volume(),"Nudge preserves extent");
            });
            test("bounded region cursor includes implicit air domains",()->{
                var cursor=new RegionCursor(List.of(new Region("a",new Vec3i(-4,6,10),new Vec3i(17,2,3)),new Region("b",new Vec3i(100,0,0),new Vec3i(1,1,1))));
                Set<Vec3i> points=new HashSet<>();while(!cursor.done()){check(points.add(cursor.local()),"Unique cell");check(cursor.sectionIndex()>=0&&cursor.sectionIndex()<4096,"Section address");cursor.advance();}
                check(cursor.processed()==103,"Every cell traversed");check(points.contains(new Vec3i(12,7,12)),"Region far corner");
            });
            test("verifier caps and ignores preserve unknown semantics",()->{
                var report=new VerificationReport();report.add(Vec3i.ZERO,Comparison.MISSING,"stone","air");var key=report.groups().keySet().iterator().next();report.ignore(key);check(report.remainingErrors()==0,"Ignore counted group");
                for(int i=0;i<5000;i++)report.add(new Vec3i(i,0,0),Comparison.UNKNOWN,"state"+i,"UNKNOWN");
                check(report.groups().size()==VerificationReport.MAX_GROUPS,"Group budget");check(report.samples().size()<=VerificationReport.MAX_SAMPLES,"Sample budget");check(report.remainingErrors()==0,"Unknown overflow is never a build error");
                report.resetIgnored();check(report.remainingErrors()==1,"Reset ignored");check(report.count(Comparison.UNKNOWN)==5000,"Unknown counts exact beyond sample cap");
            });
            test("NBT output type fidelity and source protection",()->{
                Map<String,Object> root=new LinkedHashMap<>();root.put("bytes",new byte[]{1,2});root.put("ints",new int[]{-1,100});root.put("longs",new long[]{Long.MAX_VALUE});root.put("short",(short)7);root.put("list",List.of(1.5,2.5));root.put("custom",Map.of("name","测试"));
                Path file=temp.resolve("production-nbt.nbt");NbtWriter.writeNew(file,root,Cancellation.NEVER);var read=NbtReader.read(file,NbtReader.Limits.DEFAULT,Cancellation.NEVER);
                check(read.get("short") instanceof Short,"Preserve short type");check(Arrays.equals((long[])read.get("longs"),(long[])root.get("longs")),"Long array fidelity");check(read.get("list").equals(root.get("list")),"Typed list fidelity");
                byte[] original=Files.readAllBytes(file);expect(FileAlreadyExistsException.class,()->NbtWriter.writeNew(file,Map.of(),Cancellation.NEVER));check(Arrays.equals(original,Files.readAllBytes(file)),"No overwriting existing output");
                Path cancelled=temp.resolve("cancelled-export.nbt");expect(InterruptedIOException.class,()->NbtWriter.writeNew(cancelled,root,()->true));check(!Files.exists(cancelled),"Cancelled output not published");
                expect(IOException.class,()->NbtWriter.writeNew(temp.resolve("mixed.nbt"),Map.of("bad",List.of(1,"x")),Cancellation.NEVER));
            });
            test("capture export preserves auxiliary data and origins",()->{
                var region=new Region("r",new Vec3i(10,64,-20),new Vec3i(2,1,1));var be=Map.<String,Object>of("x",0,"y",0,"z",0,"id","minecraft:chest","Items",List.of(Map.of("Slot",(byte)0,"id","minecraft:diamond","Count",(byte)3)));
                var capture=new LitematicExport.Capture(region,List.of(BlockStateSpec.AIR,BlockStateSpec.parse("stone")),new int[]{1,0},List.of(be),List.of(Map.of("id","minecraft:pig","Pos",List.of(0.5,0.0,0.5))),List.of(Map.of("x",0,"y",0,"z",0,"Block","minecraft:stone","Time",4,"Priority",0)),List.of());
                Path file=temp.resolve("captured.litematic");NbtWriter.writeNew(file,LitematicExport.create("capture","tester",3465,new Vec3i(8,64,-24),List.of(capture)),Cancellation.NEVER);
                var doc=SchematicDocument.read(file,Cancellation.NEVER);check(doc.parts().get(0).region().min().equals(new Vec3i(2,0,4)),"Origin-relative coordinates");check(doc.parts().get(0).blockEntities().get(0).get("Items").equals(be.get("Items")),"Inventory retained");check(doc.parts().get(0).entities().size()==1,"Entities retained");check(doc.parts().get(0).blockTicks().size()==1,"Ticks retained");
                try(var c=BlueprintCache.open(imported(file))){check(c.copyBlockStateCounts()[1]==1,"Export imports into independent preview pipeline");}
            });
            test("full document preparation honors cancellation",()->{
                expect(InterruptedIOException.class,()->SchematicDocument.read(source,()->true));
                var polls=new AtomicInteger();expect(InterruptedIOException.class,()->SchematicDocument.read(source,()->polls.incrementAndGet()>20));
                check(NbtReader.Limits.DEFAULT.maxUncompressedBytes()==256L<<20,"Generic NBT default stays bounded");
                check(SchematicDocument.read(source,Cancellation.NEVER).parts().size()==1,"A cancelled read does not poison subsequent reads");
            });
            test("lossless edit preserves unknown tags and untouched NBT",()->{
                var root=Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,4,1,1,i->1)),"edit");root.put("CustomModData",Map.of("seed",123L));var regions=compound(root.get("Regions"));var r=compound(regions.get("r"));
                r.put("TileEntities",List.of(Map.of("x",0,"y",0,"z",0,"id","custom:tile","secret",42),Map.of("x",3,"y",0,"z",0,"id","custom:tile","secret",99)));regions.put("r",r);root.put("Regions",regions);
                Path original=write("edit-source.litematic",root),target=temp.resolve("edit-output.litematic");byte[] saved=Files.readAllBytes(original);
                var result=LitematicEdit.replace(original,target,null,BlockStateSpec.parse("glass"),false,new Vec3i(0,0,0),Cancellation.NEVER);
                check(result.changed()==1&&result.removedBlockEntities()==1,"Change one cell and remove only associated block entity");check(Arrays.equals(saved,Files.readAllBytes(original)),"Editor leaves source byte-identical");
                var edited=NbtReader.read(target,NbtReader.Limits.DEFAULT,Cancellation.NEVER);check(edited.get("CustomModData").equals(root.get("CustomModData")),"Unknown source tags retained");
                var doc=SchematicDocument.read(target,Cancellation.NEVER);check(doc.parts().get(0).blockEntities().get(3).get("secret").equals(99),"Unchanged block entity survives");check(doc.parts().get(0).palette().get(doc.parts().get(0).blocks().get(0)).name().equals("minecraft:glass"),"Edited state correct");
            });
            test("palette edit preserves packed storage and exact mutation bookkeeping",()->{
                var region=Fixtures.region(-2,0,0,96,1,1,i->i%4);var root=Fixtures.litematic(Map.of("r",region),"palette edit");
                var originalWords=((long[])region.get("BlockStates")).clone();region.put("TileEntities",List.of(Map.of("x",1,"y",0,"z",0,"id","custom:a"),Map.of("x",2,"y",0,"z",0,"id","custom:b")));region.put("PendingBlockTicks",List.of(Map.of("x",1,"y",0,"z",0,"Block","minecraft:stone","Time",1,"Priority",0)));
                Path sourceFile=write("palette-edit.litematic",root),output=temp.resolve("palette-edit-out.litematic");
                var result=LitematicEdit.replace(sourceFile,output,BlockStateSpec.parse("stone"),BlockStateSpec.AIR,false,null,Cancellation.NEVER);
                check(result.changed()==24&&result.removedBlockEntities()==1&&result.removedTicks()==1,"Only altered palette cells lose auxiliary data");
                var after=NbtReader.read(output,NbtReader.Limits.DEFAULT,Cancellation.NEVER);var edited=compound(compound(after.get("Regions")).get("r"));check(Arrays.equals(originalWords,(long[])edited.get("BlockStates")),"Bulk edit does not allocate or rewrite packed cell words");check(NbtReader.integer(compound(after.get("Metadata")),"TotalBlocks")==48,"Air replacement updates full source totals");
                Path noOp=temp.resolve("palette-edit-noop.litematic");var noChange=LitematicEdit.replace(sourceFile,noOp,BlockStateSpec.parse("stone"),BlockStateSpec.parse("stone"),false,null,Cancellation.NEVER);check(noChange.changed()==0&&noChange.removedBlockEntities()==0,"Equal-state replacement keeps NBT");
                for(int index:new int[]{0,21,42,63,85,95}){Path one=temp.resolve("palette-point-"+index+".litematic");var editedOne=LitematicEdit.replace(sourceFile,one,null,BlockStateSpec.parse("dirt"),false,new Vec3i(index-2,0,0),Cancellation.NEVER);check(editedOne.changed()==1,"Single cell edit");var document=SchematicDocument.read(one,Cancellation.NEVER).parts().get(0);for(int i=0;i<96;i++)check(document.palette().get(document.blocks().get(i)).name().equals(i==index?"minecraft:dirt":new String[]{"minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs"}[i%4]),"Bit-width growth preserves neighboring and cross-word cells");}
            });
            test("creative replacement modes",()->{
                check(ReplaceRule.NONE.permits(false,true)&&!ReplaceRule.NONE.permits(false,false),"None writes only into air");check(!ReplaceRule.NON_AIR.permits(true,false)&&ReplaceRule.NON_AIR.permits(false,false),"Non-air never carves");check(ReplaceRule.ALL.permits(true,false),"All can carve air");
            });
            test("file metadata and streaming export retain source identity",()->{
                var metadata=SchematicFileInfo.read(source,Cancellation.NEVER);check(metadata.regions()==1,"Metadata reader skips large block arrays");Path file=temp.resolve("metadata-copy.litematic");int[] preview={0xffff0000,0xff00ff00,0xff0000ff,0xffffffff};SchematicFileInfo.export(source,file,"litematic","New title","Builder","Description",preview,Cancellation.NEVER);var info=SchematicFileInfo.read(file,Cancellation.NEVER);check(info.name().equals("New title")&&info.author().equals("Builder")&&info.description().equals("Description")&&Arrays.equals(info.preview(),preview),"Metadata and ARGB preview survive roundtrip");check(SchematicImporter.sha256(source,Cancellation.NEVER).equals(expectedHash),"Metadata export never writes source");expect(java.nio.file.FileAlreadyExistsException.class,()->SchematicFileInfo.export(source,file,"litematic","Title","","",null,Cancellation.NEVER));
                var region=Fixtures.region(0,0,0,20,1,1,i->i%4);var root=Fixtures.litematic(Map.of("r",region),"duplicate states");var palette=new ArrayList<Object>((List<?>)region.get("BlockStatePalette"));palette.set(1,palette.get(0));region.put("BlockStatePalette",palette);Path duplicate=write("duplicate-palette.litematic",root);var document=SchematicDocument.read(duplicate,Cancellation.NEVER);Path sponge=temp.resolve("duplicate-palette.schem");NbtWriter.writeNew(sponge,SchematicFormats.exportSingle(document,"schem"),Cancellation.NEVER);var after=SchematicDocument.read(sponge,Cancellation.NEVER).parts().get(0);for(int i=0;i<20;i++)check(after.palette().get(after.blocks().get(i)).equals(document.parts().get(0).palette().get(document.parts().get(0).blocks().get(i))),"Sponge palette consolidation remaps every encoded cell");
            });
            test("large Sponge full-source roundtrip and metadata preservation",()->{
                Path original=write("large-format.litematic",Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,200,200,126,i->i%4)),"large formats"));var before=SchematicDocument.read(original,Cancellation.NEVER);Path encoded=temp.resolve("large-format.schem");NbtWriter.writeNew(encoded,SchematicFormats.exportSingle(before,"schem"),Cancellation.NEVER);var after=SchematicDocument.read(encoded,Cancellation.NEVER);check(after.parts().get(0).blocks().size()==5_040_000,"Source larger than former 4M cap supports full document operations");for(int i:new int[]{0,1,2097151,4194304,5039999})check(after.parts().get(0).palette().get(after.parts().get(0).blocks().get(i)).equals(before.parts().get(0).palette().get(before.parts().get(0).blocks().get(i))),"Large roundtrip states");
                var raw=Fixtures.sponge(3,2,2,2,i->i%4,new int[]{1,2,3,4});var schematic=compound(raw.get("Schematic"));schematic.put("Biomes",Map.of("Palette",Map.of("minecraft:plains",0),"Data",new byte[]{0}));schematic.put("CustomRoot",42L);schematic.put("Metadata",Map.of("Name","old","CustomMetadata",List.of("x","y")));raw.put("Schematic",schematic);Path custom=write("custom-metadata.schem",raw),copy=temp.resolve("custom-copy.schem");SchematicFileInfo.export(custom,copy,"schem","new","author","desc",null,Cancellation.NEVER);var result=compound(NbtReader.read(copy,SchematicImporter.SOURCE_LIMITS,Cancellation.NEVER).get("Schematic"));check(result.get("CustomRoot").equals(42L)&&compound(result.get("Metadata")).get("CustomMetadata").equals(List.of("x","y")),"Same-format metadata export retains custom root and metadata fields");check(Arrays.equals((byte[])compound(result.get("Biomes")).get("Data"),new byte[]{0}),"Sponge biome data is preserved");
            });
            test("structure exports use the matching source node budget",()->{
                Path original=write("large-structure-source.litematic",Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,100,20,100,i->1)),"structure"));Path output=temp.resolve("large-structure.nbt");NbtWriter.writeNew(output,SchematicFormats.exportSingle(SchematicDocument.read(original,Cancellation.NEVER),"nbt"),Cancellation.NEVER);check(SchematicDocument.read(output,Cancellation.NEVER).parts().get(0).blocks().size()==200_000,"200K structure source writes and reloads above the old writer node boundary");
            });
            test("DDA ray handles negative coordinates and bounded reach",()->{
                var ray=VoxelRay.trace(-0.5,64.5,0.5,1,0,0,3,32);check(ray.size()==4,"Exact axis traversal");check(ray.get(0).position().x()==-1&&ray.get(1).position().x()==0,"Negative floor");check(ray.get(1).face().equals(new Vec3i(-1,0,0)),"Entry face");
                check(VoxelRay.trace(0.5,0.5,0.5,1,1,1,100,4).size()==4,"Ray work budget");check(VoxelRay.trace(0,0,0,0,0,0,6,30).isEmpty(),"Zero direction");
            });
            test("Sponge full document conversion preserves cells",()->{
                Path file=write("full-sponge.schem",Fixtures.sponge(3,3,2,4,i->i%4,new int[]{7,42,300,999}));var doc=SchematicDocument.read(file,Cancellation.NEVER);var part=doc.parts().get(0);
                check(part.region().min().equals(new Vec3i(-3,2,5)),"Sponge offset");for(int i=0;i<24;i++)check(part.palette().get(part.blocks().get(i)).name().equals(new String[]{"minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs"}[i%4]),"Sponge full state");
                Path roundtrip=temp.resolve("roundtrip.schem");NbtWriter.writeNew(roundtrip,SchematicFormats.exportSingle(doc,"schem"),Cancellation.NEVER);var after=SchematicDocument.read(roundtrip,Cancellation.NEVER);check(after.parts().get(0).palette().equals(part.palette()),"Sponge palette roundtrip");
            });
            test("vanilla structure import/export and sparse air",()->{
                Map<String,Object> root=Map.of("DataVersion",3465,"size",List.of(2,1,1),"palette",List.of(Map.of("Name","minecraft:stone")),"blocks",List.of(Map.of("pos",List.of(0,0,0),"state",0)),"entities",List.of());
                Path file=temp.resolve("structure.nbt");NbtWriter.writeNew(file,root,Cancellation.NEVER);var doc=SchematicDocument.read(file,Cancellation.NEVER);var part=doc.parts().get(0);
                check(part.palette().get(part.blocks().get(0)).name().equals("minecraft:stone"),"Defined structure cell");check(part.palette().get(part.blocks().get(1)).isAir(),"Undefined structure cell stays air");
                try(var cache=BlueprintCache.open(imported(file))){check(cache.copyBlockStateCounts()[0]==1,"Structure preview import");}
                Path exported=temp.resolve("structure-roundtrip.nbt");NbtWriter.writeNew(exported,SchematicFormats.exportSingle(doc,"nbt"),Cancellation.NEVER);check(SchematicDocument.read(exported,Cancellation.NEVER).parts().get(0).blocks().size()==2,"Structure export reload");
            });
            test("named selections isolate templates and reject duplicate additions",()->{
                var a=AreaSelection.EMPTY.add(new SelectionBox("a",Vec3i.ZERO,new Vec3i(3,4,5)));expect(IllegalArgumentException.class,()->a.add(new SelectionBox("a",new Vec3i(99,0,0),Vec3i.ZERO)));
                var b=a.add(new SelectionBox("b",new Vec3i(10,0,0),new Vec3i(12,2,2)));check(!b.simple()&&b.boxes().size()==2&&b.boxes().get(0).equals(a.current()),"Adding another box preserves the first and uses multi-region mode");expect(IllegalArgumentException.class,()->b.rename("a"));var renamed=b.rename("c");check(renamed.selected().equals("c")&&renamed.boxes().size()==2,"Rename changes identity without removing other boxes");
                Path library=temp.resolve("selection-library");SelectionLibrary.save(library,"sample",b);expect(java.nio.file.FileAlreadyExistsException.class,()->SelectionLibrary.save(library,"sample",a));check(SelectionLibrary.load(library,"sample").equals(b),"Stored template unchanged after active edits");SelectionLibrary.rename(library,"sample","renamed");check(SelectionLibrary.list(library).equals(List.of("renamed")),"Library rename/list");expect(IOException.class,()->SelectionLibrary.save(library,"../escape",b));SelectionLibrary.delete(library,"renamed");check(SelectionLibrary.list(library).isEmpty(),"Only selected definition removed");
            });
            test("selection picking dragging and expansion respect individual parts",()->{
                var selection=AreaSelection.EMPTY.add(new SelectionBox("box",new Vec3i(0,0,0),new Vec3i(9,9,9))).origin(new Vec3i(50,50,50));var face=SelectionTarget.ray(selection,5,5,-10,0,0,1,100);check(face!=null&&face.part()==SelectionTarget.Part.BOX,"Middle of face selects whole box");var corner=SelectionTarget.ray(selection,0.5,0.5,-10,0,0,1,100);check(corner!=null&&corner.part()==SelectionTarget.Part.FIRST,"Corner handle takes precedence over its surrounding face");var moved=corner.translate(selection,new Vec3i(-2,1,0));check(moved.current().first().equals(new Vec3i(-2,1,0))&&moved.current().second().equals(selection.current().second()),"Dragging first corner never moves second");var origin=SelectionTarget.ray(selection,50,50,40,0,0,1,20);check(origin!=null&&origin.part()==SelectionTarget.Part.ORIGIN&&origin.translate(selection,new Vec3i(0,1,0)).origin().equals(new Vec3i(50,51,50)),"Origin can be picked and moved independently");
                var reversed=new SelectionBox("r",new Vec3i(9,9,9),Vec3i.ZERO);var larger=reversed.expand(new Vec3i(-1,0,0),3);check(larger.first().equals(reversed.first())&&larger.second().x()==-3,"Expansion preserves reversed corner identity");check(reversed.expand(new Vec3i(1,0,0),-100).region().size().x()==1,"Contraction cannot invert or erase an axis");
            });
            test("immutable project versions restore placement and source",()->{
                Path root=temp.resolve("version-project");Files.createDirectories(root);Files.copy(source,root.resolve("source.litematic"));
                var placement=new Placement(UUID.randomUUID(),"original","source.litematic",new PlacementTransform(new Vec3i(-5,70,9),3,true,false),true,true);
                String v=ProjectVersions.save(root,"test",placement,LayerRange.ALL,0.4f);
                check(ProjectVersions.list(root,"test").equals(List.of(v)),"Version listed");
                var restored=ProjectVersions.load(root,"test",v);var saved=restored.placements().get(0);
                check(saved.transform().equals(placement.transform())&&saved.locked(),"Placement settings retained");
                check(!saved.id().equals(placement.id()),"Snapshot independent identity");
                var snapshotId=UUID.randomUUID();check(saved.identity(snapshotId).id().equals(snapshotId)&&saved.identity(snapshotId).locked(),"Restored instance can retain identity without clearing transform locks");
                check(ProjectVersions.projects(root).equals(List.of("test")),"Projects discoverable without typing their names");ProjectVersions.rename(root,"test",v,"Before roof");check(ProjectVersions.details(root,"test").get(0).name().equals("Before roof"),"Version label persists separately from immutable identity");
                Files.writeString(root.resolve("source.litematic"),"modified later");
                check(SchematicImporter.sha256(root.resolve(saved.source()),Cancellation.NEVER).equals(expectedHash),"Snapshot independent of mutable source");
                expect(IOException.class,()->ProjectVersions.list(root,"../escape"));
                expect(IOException.class,()->ProjectVersions.load(root,"test","../escape"));
                Thread.currentThread().interrupt();try{expect(IOException.class,()->ProjectVersions.save(root,"cancelled",placement,LayerRange.ALL,0.4f));}finally{Thread.interrupted();}
                try(var files=Files.list(root.resolve("projects/cancelled"))){check(files.findAny().isEmpty(),"Cancelled snapshot cleans only its own file");}
                ProjectVersions.delete(root,"test",v);check(ProjectVersions.list(root,"test").isEmpty()&&Files.isRegularFile(root.resolve(saved.source())),"Deleted version leaves source snapshot available to active placements");expect(IOException.class,()->ProjectVersions.load(root,"test",v));
            });
            test("cross-section face neighbors and absent air",()->{
                int[] values=new int[4096];Arrays.fill(values,1);var solid=PackedSection.fromGlobalIds(values);
                var halo=new SectionNeighborhood(solid,solid,solid,solid,solid,solid,solid);
                for(int a=0;a<16;a++)for(int b=0;b<16;b++){check(halo.globalId(-1,a,b)==1&&halo.globalId(16,a,b)==1,"X seam");check(halo.globalId(a,-1,b)==1&&halo.globalId(a,16,b)==1,"Y seam");check(halo.globalId(a,b,-1)==1&&halo.globalId(a,b,16)==1,"Z seam");}
                check(new SectionNeighborhood(solid,null,null,null,null,null,null).globalId(-1,0,0)==0,"Known absent neighbor air");
                expect(IndexOutOfBoundsException.class,()->halo.globalId(-1,-1,0));
            });
            test("cached neighbor deltas preserve rotations and mirrors",()->{
                for(int rotation=0;rotation<4;rotation++)for(boolean x:new boolean[]{false,true})for(boolean z:new boolean[]{false,true}){
                    var t=new PlacementTransform(new Vec3i(123,-57,300),rotation,x,z);var local=new Vec3i(-25,61,37);
                    for(var direction:new Vec3i[]{new Vec3i(1,0,0),new Vec3i(-1,0,0),new Vec3i(0,1,0),new Vec3i(0,-1,0),new Vec3i(0,0,1),new Vec3i(0,0,-1)}){
                        var delta=t.inverse(t.origin().add(direction));
                        check(local.add(delta).equals(t.inverse(t.apply(local).add(direction))),"Cached offset matches full world transform");
                    }
                }
            });
            test("expanded render query exceeds former 512 section cap",()->{
                var metadata=new BlueprintMetadata("dense",0,"0".repeat(64),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:stone")),List.of(new Region("r",Vec3i.ZERO,new Vec3i(128,128,144))),List.of());
                int[] cells=new int[4096];Arrays.fill(cells,1);var section=PackedSection.fromGlobalIds(cells);Path file=temp.resolve("wide-render.bpc");
                try(var writer=new BlueprintCache.Writer(file,metadata)){
                    for(int y=0;y<8;y++)for(int z=0;z<9;z++)for(int x=0;x<8;x++){writer.add(new SectionKey(0,x,y,z),section);for(int n=0;n<4096;n++)writer.count(1);}
                    writer.commit(Cancellation.NEVER);
                }
                try(var cache=BlueprintCache.open(file)){
                    var index=new SpatialIndex(cache,Cancellation.NEVER);var center=new Vec3i(64,64,72);
                    check(index.nearest(center,192,512).size()==512,"Old admission cap reproduced");
                    check(index.nearest(center,512,8192).size()==576,"All nearby source sections admitted with new cap");
                    expect(IllegalArgumentException.class,()->index.nearest(center,513,8192));
                    expect(IllegalArgumentException.class,()->index.nearest(center,512,32769));
                }
            });
            test("camera turns retain warm meshes until cache pressure",()->{
                var released=new ArrayList<String>();var cache=new WeightedLru<String,String>(6,3,v->2L,released::add);
                check(cache.tryPutProtecting("front","front",Set.of("front")),"Front admitted");
                check(cache.tryPutProtecting("back","back",Set.of("back")),"Turn does not discard front");
                check(cache.contains("front")&&cache.contains("back")&&released.isEmpty(),"Both views remain resident");
                check(cache.tryPutProtecting("side","side",Set.of("side")),"Spare space keeps warm mesh");
                check(cache.tryPutProtecting("new","new",Set.of("front","new")),"Pressure evicts only unprotected mesh");
                check(cache.contains("front")&&!cache.contains("back")&&released.equals(List.of("back")),"Protected mesh survives turn");
                check(!cache.tryPutProtecting("full","full",Set.of("front","side","new")),"No churn among protected meshes");
                check(released.size()==1,"Rejected admission evicts nothing");cache.close();check(released.size()==4,"Every admitted resource released once");
            });
            test("visible mesh admission cannot churn the resident set",()->{
                List<Integer> freed=new ArrayList<>();try(var lru=new WeightedLru<String,Integer>(10,2,Integer::longValue,freed::add)){
                    check(lru.tryPutWithoutEviction("near",6),"First admitted");
                    for(int i=0;i<1000;i++)check(!lru.tryPutWithoutEviction("far",6),"No eviction for pressure");
                    check(lru.contains("near")&&freed.isEmpty(),"Visible mesh retained");lru.retainKeys(Set.of("far"));check(freed.equals(List.of(6)),"Old working set released");check(lru.tryPutWithoutEviction("far",6),"New working set admitted");
                }
            });
            test("safe-area UI independent of native GUI scale and DPI",()->{
                for(int[] pixels:List.of(new int[]{320,240},new int[]{1280,720},new int[]{1920,1080},new int[]{1709,1003},new int[]{3440,1440},new int[]{1080,1920},new int[]{3840,2160})){
                    UiViewport reference=UiViewport.fit(pixels[0],pixels[1],pixels[0],pixels[1],1);
                    for(double gui:new double[]{1,2,3,4,6,1.5}){
                        int iw=(int)Math.ceil(pixels[0]/gui),ih=(int)Math.ceil(pixels[1]/gui);var v=UiViewport.fit(pixels[0],pixels[1],iw,ih,gui);
                        check(v.pixelX(0)>=pixels[0]*0.05-1e-8&&v.pixelY(0)>=pixels[1]*0.05-1e-8,"Safe margins");
                        check(v.pixelX(UiViewport.WIDTH)<=pixels[0]*0.95+1e-8&&v.pixelY(UiViewport.HEIGHT)<=pixels[1]*0.95+1e-8,"Canvas stays in safe area");
                        for(int x:new int[]{0,24,UiViewport.WIDTH/2,UiViewport.WIDTH-24,UiViewport.WIDTH})for(int y:new int[]{0,52,UiViewport.HEIGHT/2,UiViewport.HEIGHT-52,UiViewport.HEIGHT}){
                            check(Math.abs((v.drawX()+x*v.drawScale())*gui-reference.pixelX(x))<1e-8,"Physical X independent of GUI scale");
                            check(Math.abs((v.drawY()+y*v.drawScale())*gui-reference.pixelY(y))<1e-8,"Physical Y independent of GUI scale");
                            check(Math.abs(v.inputX(v.pixelX(x)*iw/pixels[0])-x)<1e-8,"Mouse X roundtrip including odd framebuffer widths");
                            check(Math.abs(v.inputY(v.pixelY(y)*ih/pixels[1])-y)<1e-8,"Mouse Y roundtrip");
                        }
                        check(v.clip(24,82,UiViewport.WIDTH-24,UiViewport.HEIGHT-56).equals(reference.clip(24,82,UiViewport.WIDTH-24,UiViewport.HEIGHT-56)),"Scissor physical pixels invariant");
                        check(Math.abs(v.deltaX(19*v.scale()*iw/pixels[0])-19)<1e-8,"Drag delta X");
                        check(Math.abs(v.deltaY(-7*v.scale()*ih/pixels[1])+7)<1e-8,"Drag delta Y");
                        var clip=v.clip(-10000,-10000,10000,10000);check(clip.x()==0&&clip.y()==0&&clip.width()==pixels[0]&&clip.height()==pixels[1],"Clip clamped to framebuffer");
                    }
                }
                expect(IllegalArgumentException.class,()->UiViewport.fit(0,1080,640,360,3));
                check(UiViewport.fit(1920,1008,480,252,4).scale()*9>=20,"1080-class window text nominal height at least 20 physical pixels");
                expect(IllegalArgumentException.class,()->UiViewport.fit(1920,1080,640,360,Double.NaN));
            });
            test("source bytes were never overwritten",()->{check(SchematicImporter.sha256(source,Cancellation.NEVER).equals(expectedHash),"Original checksum unchanged");});
            System.out.printf(Locale.ROOT,"RESULT: %d tests passed; %,d assertions; %.3f seconds%n",tests,checks,(System.nanoTime()-started)/1e9);
        }finally{try(var walk=Files.walk(temp)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
