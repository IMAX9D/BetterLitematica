package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.*;
import net.minecraft.client.render.model.*;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.Random;
import net.minecraft.world.EmptyBlockView;
import org.joml.Matrix4f;
import java.util.*;
import java.util.concurrent.*;

/**
 * Development-preview renderer, not a full replacement for vanilla's terrain renderer.
 * Minecraft models are accessed on the render thread. Disk decoding is off-thread.
 * Model callbacks are not preemptible; the time limit is a cooperative budget, not a hard real-time guarantee.
 * Native fluids, block entities and bounded entity meshes share a nearest-surface composite.
 */
final class ProjectionRenderer1201 implements AutoCloseable,RenderScheduler.Worker {
    private static final int MAX_PART_VERTICES=16384,MAX_BLOCK_QUADS=512;
    private static final int BLOCKS_PER_FRAME=8192,UPLOAD_BYTES_PER_FRAME=2*1024*1024,MAX_COMPLETED_PER_FRAME=32;
    private static final int QUERY_RADIUS=512,MAX_CANDIDATES=32768;
    private static final long BUILD_NANOS=4_000_000,MAX_ACTIVE_SECTION_BYTES=8L<<20;
    private static final Direction[] DIRECTIONS=Direction.values();
    private record Part(VertexBuffer buffer,int bytes,net.minecraft.util.Identifier texture,boolean intensity,QuadVisibility.Part visibility) {}
    private record Mesh(List<Part> parts,long bytes,Box bounds,RenderResources.Group resources,long worldRevision,QuadVisibility.Mask mask,QuadVisibility.Mask wrong,RenderedCells cells,BitSet omitted) implements AutoCloseable {
        @Override public void close(){resources.close();}
    }
    private static final class Job {
        RenderResources.Group resources;final SectionKey key;final PackedSection section;final Vec3i localBase;PlacementTransform transform;StateResolver1201 states;Vec3i[] offsets;List<ProjectionScene.Part> overlaps;ProjectionBlockView view;long stalledSince,worldRevision;
        final List<Part> parts=new ArrayList<>();final RenderedCells.Builder cells=new RenderedCells.Builder();final BitSet omitted=new BitSet(4096);QuadVisibility.Mask mask,wrong;long visibilityBytes;SectionNeighborhood neighborhood;int cursor,vertices;long bytes;
        Job(SectionKey key,PackedSection section,Vec3i localBase){this.key=key;this.section=section;this.localBase=localBase;}
    }
    private final MinecraftClient client;private final RenderResources resources;private boolean frameReady,resourcesVisible;private long waitingRevision=-1;
    private final dev.betterlitematica.io.AuxiliaryData details;private final String detailsError;private ProjectionWorld projectionWorld;
    private final Map<BlockPos,net.minecraft.block.entity.BlockEntity> blockEntities=new LinkedHashMap<>();
    private final ProjectionModels specialModels=new ProjectionModels();private final ProjectionEntities entities;private List<ProjectionEntities.Candidate> entityCandidates=List.of();
    private final SectionStreamer stream;private final LoadCoordinator.Loaded sourceLease;
    private final SpatialIndex index;
    private SchematicEdits edits;
    private final WeightedLru<SectionKey,PackedSection> editedSections=new WeightedLru<>(16L<<20,PackedSection::estimatedBytes,value->{});
    // Keep meshes across camera turns; evict cold meshes only under actual memory pressure.
    private final WeightedLru<SectionKey,Mesh> meshes=new WeightedLru<>(512L<<20,32768,Mesh::bytes,Mesh::close);
    private final MeshRefresh<SectionKey> worldRefresh=new MeshRefresh<>(4096);
    private final BufferBuilder builder=new BufferBuilder(MAX_PART_VERTICES*24);
    private final QuadVisibility.Builder ownership=new QuadVisibility.Builder();
    private final Random random=new Random(0);
    private final Set<SectionKey> failed=new HashSet<>(),deferred=new HashSet<>();
    private final Map<SectionKey,Box> boundsCache=new HashMap<>();
    private final Set<SectionKey> visibleSet=new HashSet<>();
    private final List<SectionKey> visibleKeys=new ArrayList<>();
    private record QueryResult(long generation,Vec3i camera,int radius,List<SectionKey> keys,Set<SectionKey> membership,List<ProjectionEntities.Candidate> entities){}
    private final ExecutorService queryWorker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"betterlitematica-spatial");t.setDaemon(true);return t;});
    // Only one future is submitted at a time, so executor backlog is bounded to one query.
    private CompletableFuture<QueryResult> query;
    private long queryGeneration;
    private final net.minecraft.client.texture.Sprite[] lightSprites=new net.minecraft.client.texture.Sprite[16];
    private long nextWorkProbe,decodedRevision=-1;
    private int buildCursor,surroundingCursor,boundsCursor;
    private boolean visibilityDirty=true;
    private Vec3d lastCullCamera;
    private final Matrix4f lastCullView=new Matrix4f(),lastCullProjection=new Matrix4f();
    private final Map<SectionKey,Vec3i> worldBases=new HashMap<>();
    private final ProjectionVertices fluidVertices=new ProjectionVertices();
    private final ArrayList<BakedQuad> quads=new ArrayList<>(48);
    private PlacementLayout layout;private ProjectionScene scene;
    private final Map<PlacementTransform,StateResolver1201> resolvers=new HashMap<>();
    private LayerRange layer=LayerRange.ALL;
    private final Map<BlockState,Boolean> opaqueStates=new IdentityHashMap<>();
    private int lastRadius;
    private List<SectionKey> candidates=List.of();private Set<SectionKey> candidateSet=Set.of();
    private Vec3i lastCamera;private Job job;private SectionKey coolingKey;private long coolingUntil;
    @Override public boolean building(){return job!=null;}
    private boolean visible=true,budgetSaturated;
    private float opacity=0.45f;
    void opacity(float value){opacity=value;}
    private int frameUploadBytes,frameUploadLimit=UPLOAD_BYTES_PER_FRAME,drawCalls;private long builtSections,lastBuildNanos;private String failure="";
    ProjectionRenderer1201(MinecraftClient client,LoadCoordinator.Loaded loaded,RenderResources resources){
        this.client=client;this.resources=resources;sourceLease=loaded;details=loaded.details();detailsError=loaded.detailsError();entities=new ProjectionEntities(client,details,resources);stream=loaded.stream();index=loaded.index();place(new Placement(UUID.randomUUID(),"preview","preview.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false));scene=new ProjectionScene(List.of(this));
    }
    PlacementTransform transform(){return layout.placement().transform();}
    PlacementLayout layout(){return layout;}
    private List<BlockStateSpec> displayPalette;
    private BitSet hiddenStates;
    /** Compile once per immutable palette/filter revision, never once per source cell. */
    boolean displays(int id){
        var palette=metadata().palette();
        if(displayPalette!=palette||hiddenStates==null){
            var hidden=new BitSet(palette.size());var filter=layout.placement().displayFilter();
            for(int i=0;i<palette.size();i++)if(!filter.allows(palette.get(i)))hidden.set(i);
            hiddenStates=hidden;displayPalette=palette;
        }
        return id>=0&&id<palette.size()&&!hiddenStates.get(id);
    }
    void scene(ProjectionScene value){
        var changes=value.changesFrom(scene,this);scene=value;if(changes.empty())return;
        // The moved renderer already invalidated its own layout. Other renderers keep unrelated
        // buffers, spatial queries and in-flight builds, including off-screen warm meshes.
        var affected=new HashSet<SectionKey>();
        meshes.forEach((key,mesh)->{if(changes.affects(sceneBounds(key)))affected.add(key);});
        for(var key:failed)if(changes.affects(sceneBounds(key)))affected.add(key);
        for(var key:deferred)if(changes.affects(sceneBounds(key)))affected.add(key);
        if(job!=null&&changes.affects(sceneBounds(job.key))){affected.add(job.key);discardJob();}
        for(var key:affected){meshes.remove(key);failed.remove(key);deferred.remove(key);}
        blockEntities.keySet().removeIf(pos->changes.affects(new PlacementBounds(new Vec3i(pos.getX()-1,pos.getY()-1,pos.getZ()-1),new Vec3i(pos.getX()+1,pos.getY()+1,pos.getZ()+1))));
        entities.sceneChanged(changes,entityCandidates);
        if(!affected.isEmpty())wakeWorldRefresh();
    }
    private PlacementBounds sceneBounds(SectionKey key){var box=bounds(key);return new PlacementBounds(new Vec3i((int)box.minX-1,(int)box.minY-1,(int)box.minZ-1),new Vec3i((int)box.maxX,(int)box.maxY,(int)box.maxZ));}
    void drain(){stream.drain();}
    StateResolver1201 resolver(int region){var t=layout.part(region).transform();var linear=new PlacementTransform(Vec3i.ZERO,t.quarterTurns(),t.mirrorX(),t.mirrorZ());return resolvers.computeIfAbsent(linear,k->new StateResolver1201(metadata().palette(),k));}
    BlockState resolve(int region,int id){return resolver(region).resolve(id);}
    BlueprintMetadata metadata(){return edits==null?stream.source().metadata():edits.snapshot().metadata();}
    /** Source-owned, read-only auxiliary data; deliberately does not use render entity fallbacks. */
    Map<String,Object> informationBlockData(PlacementLayout.Part region,Vec3i local){
        var patch=edits==null?null:edits.snapshot().patch(region.section(local),region.cell(local));
        if(patch!=null&&!patch.blockData())return null;
        if(details==null)throw new IllegalStateException(detailsError.isEmpty()?"附加数据未加载":"附加数据读取失败");
        return details.parts().get(region.index()).blocks().get(local);
    }
    long[] sourceCounts(){return stream.source().copyBlockStateCounts();}
    void draft(SchematicEdits value){edits=value;editedSections.close();resolvers.clear();}
    int baseState(SectionKey key,int cell){
        if(!stream.source().index().containsKey(key))return 0;var data=stream.get(key);if(data==null){stream.request(key);return -1;}return data.globalId(cell);
    }
    private PackedSection section(SectionKey key){
        var patch=edits==null?null:edits.snapshot().sections().get(key);if(patch!=null){var cached=editedSections.get(key);if(cached!=null)return cached;}
        var base=stream.source().index().containsKey(key)?stream.get(key):ANALYSIS_AIR;
        if(base==null){stream.request(key);return null;}if(patch==null)return base;
        int[] cells=new int[4096];for(int i=0;i<4096;i++)cells[i]=base.globalId(i);patch.forEach((cell,value)->cells[cell]=value.state());var combined=PackedSection.fromGlobalIds(cells);editedSections.put(key,combined);return combined;
    }
    void draftChanged(Set<SectionKey> changed){
        for(var key:changed)editedSections.remove(key);resolvers.clear();blockEntities.clear();
        queryGeneration++;lastCamera=null;nextWorkProbe=0;waitingRevision=-1;
    }
    void invalidateWorld(List<PlacementBounds> boxes){
        var affected=new HashSet<SectionKey>();
        for(var box:boxes)for(var part:layout.overlapping(box)){
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(int x:new int[]{box.min().x(),box.max().x()})for(int y:new int[]{box.min().y(),box.max().y()})for(int z:new int[]{box.min().z(),box.max().z()}){var local=part.local(new Vec3i(x,y,z)).subtract(part.region().min());minX=Math.min(minX,local.x());minY=Math.min(minY,local.y());minZ=Math.min(minZ,local.z());maxX=Math.max(maxX,local.x());maxY=Math.max(maxY,local.y());maxZ=Math.max(maxZ,local.z());}
            var size=part.region().size();for(int y=Math.max(0,minY>>4);y<=Math.min((size.y()-1)>>4,maxY>>4);y++)for(int z=Math.max(0,minZ>>4);z<=Math.min((size.z()-1)>>4,maxZ>>4);z++)for(int x=Math.max(0,minX>>4);x<=Math.min((size.x()-1)>>4,maxX>>4);x++)affected.add(new SectionKey(part.index(),x,y,z));
        }
        if(job!=null&&affected.contains(job.key))discardJob();for(var key:affected){meshes.remove(key);failed.remove(key);deferred.remove(key);}blockEntities.clear();nextWorkProbe=0;budgetSaturated=false;waitingRevision=-1;
    }
    /** World changes arrive on the client thread. Keep the previous complete mesh until commit. */
    void worldChanged(List<PlacementBounds> boxes){
        if(boxes.size()>64||layout.size()>4096){worldAllChanged();return;}
        int visited=0;boolean changed=false;
        for(var raw:boxes){
            var box=MeshRefresh.padded(raw);
            for(var part:layout.overlapping(box)){
                // Orthogonal transforms map opposite AABB corners to the opposite local corners.
                var range=MeshRefresh.sourceSections(part,box);if(range==null)continue;
                for(int y=range.min().y();y<=range.max().y();y++)for(int z=range.min().z();z<=range.max().z();z++)for(int x=range.min().x();x<=range.max().x();x++){
                    if(++visited>8192){worldAllChanged();return;}
                    var key=new SectionKey(part.index(),x,y,z);
                    changed|=failed.remove(key)|deferred.remove(key);
                    if(meshes.contains(key)||job!=null&&job.key.equals(key)){
                        worldRefresh.changed(key);changed=true;
                    }
                }
            }
        }
        if(changed)wakeWorldRefresh();
    }
    /** Runs synchronously with client world mutation, before the next projection draw. */
    void worldBlockChanged(BlockPos pos,BlockState actual){
        var world=new Vec3i(pos.getX(),pos.getY(),pos.getZ());
        if(!layer.contains(world))return;
        boolean changed=false;int raw=Block.getRawIdFromState(actual);boolean loaded=WorldChunks.loaded(client.world,pos);
        var parts=layout.at(world,64);if(parts==null){worldAllChanged();return;}
        for(var part:parts){
            var local=part.local(world);var key=part.section(local);int cell=part.cell(local);
            var mesh=meshes.get(key);var active=job!=null&&job.key.equals(key)?job:null;
            changed|=failed.remove(key)|deferred.remove(key);
            int expected=mesh==null?-1:mesh.cells.state(cell);
            if(expected>=0){mesh.mask.set(cell,loaded&&raw==expected);mesh.wrong.set(cell,wrongBlock(loaded,actual,Block.getStateFromRawId(expected)));}
            boolean rebuild=mesh!=null&&mesh.omitted.get(cell)&&!(loaded&&expected>=0&&raw==expected);
            if(active!=null){
                int id=active.section.globalId(cell);var state=active.states.resolve(id);
                if(!active.states.unresolvedState(id)){
                    boolean complete=completedBlock(loaded,actual,state);active.mask.set(cell,complete);active.wrong.set(cell,wrongBlock(loaded,actual,state));
                    // A build may already have skipped this cell while it was complete.
                    if(!complete&&active.omitted.get(cell))rebuild=true;
                }
            }
            if(rebuild){worldRefresh.changed(key);changed=true;}
        }
        if(changed)wakeWorldRefresh();
    }
    void worldAllChanged(){worldRefresh.allChanged();failed.clear();deferred.clear();wakeWorldRefresh();}
    void worldChunkUnloaded(int x,int z){
        if(client.world==null)return;
        var box=new PlacementBounds(new Vec3i(x<<4,client.world.getBottomY(),z<<4),new Vec3i((x<<4)+15,client.world.getTopY()-1,(z<<4)+15));int visited=0;
        for(var part:layout.overlapping(box)){var range=MeshRefresh.sourceSections(part,box);if(range==null)continue;
            for(int y=range.min().y();y<=range.max().y();y++)for(int sz=range.min().z();sz<=range.max().z();sz++)for(int sx=range.min().x();sx<=range.max().x();sx++){
                if(++visited>8192){meshes.forEach((key,mesh)->mesh.wrong.clear());if(job!=null)job.wrong.clear();return;}
                var key=new SectionKey(part.index(),sx,y,sz);var mesh=meshes.get(key);if(mesh!=null)mesh.wrong.clear();if(job!=null&&job.key.equals(key))job.wrong.clear();
            }
        }
    }
    private void wakeWorldRefresh(){nextWorkProbe=0;budgetSaturated=false;waitingRevision=-1;}
    private boolean needsMesh(SectionKey key){var mesh=meshes.get(key);return mesh==null||worldRefresh.stale(key,mesh.worldRevision());}
    static boolean completedBlock(boolean loaded,BlockState actual,BlockState expected){return loaded&&actual!=null&&actual==expected;}
    static boolean wrongBlock(boolean loaded,BlockState actual,BlockState expected){return loaded&&actual!=null&&expected!=null&&!actual.isAir()&&!expected.isAir()&&actual!=expected;}
    private static final PackedSection ANALYSIS_AIR=PackedSection.fromGlobalIds(new int[4096]);
    PackedSection analysisSection(SectionKey key){stream.drain();return section(key);}
    int sample(SectionKey key,int position){stream.drain();return sampleRaw(key,position);}
    int sampleRaw(SectionKey key,int position){
        var patch=edits==null?null:edits.snapshot().patch(key,position);return patch==null?baseState(key,position):patch.state();
    }
    void place(Placement next){
        layout=new PlacementLayout(next,metadata().regions());displayPalette=null;invalidate();
    }
    void layer(LayerRange next){layer=next;invalidate();}
    LayerRange layer(){return layer;}
    void visible(boolean value){visible=value;}
    boolean visible(){return visible;}
    void suspend(){discardJob();frameReady=false;resourcesVisible=false;meshes.forEach((key,mesh)->mesh.resources().priority(RenderResources.COLD));entities.suspend();}
    void invalidate(){
        discardJob();meshes.close();worldRefresh.clear();editedSections.close();candidates=List.of();candidateSet=Set.of();entityCandidates=List.of();entities.invalidate();visibleKeys.clear();Arrays.fill(lightSprites,null);opaqueStates.clear();failed.clear();deferred.clear();budgetSaturated=false;boundsCache.clear();worldBases.clear();visibilityDirty=true;nextWorkProbe=0;waitingRevision=-1;visibleSet.clear();queryGeneration++;resolvers.clear();blockEntities.clear();projectionWorld=null;lastCamera=null;failure="";
    }
    String status(){return "Partial preview (nearby only, max "+MAX_CANDIDATES+" sections) | "+String.format(Locale.ROOT,"mesh=%d, drawn=%d, built=%d, CPU=%.1f MiB, GPU-est=%.1f MiB, queue=%d, build=%.2f ms, unsupported=%d",
        meshes.size(),drawCalls,builtSections,stream.cachedBytes()/1048576.0,(meshes.usedBytes()+(job==null?0:job.bytes))/1048576.0,stream.queuedJobs(),lastBuildNanos/1e6,resolvers.values().stream().mapToInt(StateResolver1201::unsupportedCount).sum());}
    String error(){return !failure.isEmpty()?failure:!detailsError.isEmpty()?detailsError:!entities.error().isEmpty()?entities.error():stream.error();}
    void prepareFrame(WorldRenderContext context,boolean active){
        stream.drain();drawCalls=0;lastBuildNanos=0;frameReady=active&&visible&&client.world!=null;
        // Disk workers can finish between frames. Wake builds on admitted data instead of
        // leaving a ready section idle until the fallback polling interval expires.
        if(decodedRevision!=stream.revision()){decodedRevision=stream.revision();nextWorkProbe=0;}
        if(!frameReady){discardJob();if(resourcesVisible)meshes.forEach((key,mesh)->mesh.resources().priority(RenderResources.COLD));resourcesVisible=false;entities.prepare(context,List.of(),false);return;}
        boolean budgetChanged=visibilityDirty||!resourcesVisible;resourcesVisible=true;
        try{
            Vec3d camera=context.camera().getPos();Vec3i local=new Vec3i(MathHelper.floor(camera.x),MathHelper.floor(camera.y),MathHelper.floor(camera.z));
            int radius=Math.min(QUERY_RADIUS,Math.max(16,client.options.getViewDistance()*16));
            if(query!=null&&query.isDone()){
                QueryResult result=query.join();query=null;
                if(result.generation()==queryGeneration&&result.radius()==radius&&distanceSquared(result.camera(),local)<=4096){
                    var members=result.membership();budgetChanged=true;
                    boundsCache.keySet().retainAll(members);worldBases.keySet().retainAll(members);failed.retainAll(members);visibilityDirty=true;nextWorkProbe=0;
                    visibleSet.retainAll(members);deferred.clear();budgetSaturated=false;
                    candidates=result.keys();candidateSet=result.membership();entityCandidates=result.entities();lastCamera=result.camera();lastRadius=radius;boundsCursor=0;
                    if(job!=null&&!members.contains(job.key))discardJob();
                }
            }
            if(query==null&&(lastCamera==null||distanceSquared(lastCamera,local)>64||radius!=lastRadius)){
                long generation=queryGeneration;PlacementLayout snapshot=layout;
                var additions=edits==null?List.<SectionKey>of():edits.snapshot().sections().keySet().stream().filter(key->!stream.source().index().containsKey(key)).toList();
                query=CompletableFuture.supplyAsync(()->{var keys=index.nearest(local,radius,MAX_CANDIDATES,snapshot,additions);return new QueryResult(generation,local,radius,keys,new HashSet<>(keys),entities.nearby(snapshot,local,radius));},queryWorker);
            }
            var viewMatrix=LegacyMatrices.joml(context.matrixStack().peek().getPositionMatrix());
            if(visibilityDirty||!camera.equals(lastCullCamera)||!lastCullView.equals(viewMatrix)||!lastCullProjection.equals(LegacyMatrices.joml(context.projectionMatrix()))){
            visibilityDirty=false;
            visibleKeys.clear();boolean visibilityChanged=false;
            int newBounds=0;long boundsDeadline=System.nanoTime()+1_000_000L;
            // Advance through new bounds once. Scanning thousands of already-known boxes
            // must not consume the entire new-box allowance and starve the distant tail.
            while(boundsCursor<candidates.size()&&newBounds<256&&System.nanoTime()<boundsDeadline){
                var key=candidates.get(boundsCursor++);if(boundsCache.containsKey(key))continue;
                var cached=meshes.get(key);boundsCache.put(key,cached==null?bounds(key):cached.bounds());newBounds++;
            }
            if(boundsCursor<candidates.size())visibilityDirty=true;
            for(SectionKey key:candidates){
                Box box=boundsCache.get(key);if(box==null)continue;
                boolean inView=context.frustum()==null||context.frustum().isVisible(box);
                if(inView){visibleKeys.add(key);visibilityChanged|=visibleSet.add(key);}else visibilityChanged|=visibleSet.remove(key);
            }
            if(visibilityChanged){budgetChanged=true;deferred.clear();budgetSaturated=false;nextWorkProbe=0;}
            lastCullCamera=camera;lastCullView.set(viewMatrix);lastCullProjection.set(LegacyMatrices.joml(context.projectionMatrix()));
            }
            if(budgetChanged){waitingRevision=-1;if(job!=null&&!visibleSet.contains(job.key)&&!resources.warm())discardJob();}
            if(budgetChanged)meshes.forEach((key,mesh)->mesh.resources().priority(visibleSet.contains(key)?RenderResources.VISIBLE:candidateSet.contains(key)?RenderResources.NEARBY:RenderResources.COLD));
            entities.prepare(context,entityCandidates,true);
        }catch(RuntimeException e){frameReady=false;failure="Renderer paused: "+e;visible=false;discardJob();BetterLitematicaClient.LOGGER.error("Projection visibility failed",e);}
    }
    @Override public RenderScheduler.Work buildFrame(long deadline,int uploadLimit){
        if(!frameReady||System.nanoTime()>=deadline||uploadLimit<=0)return RenderScheduler.Work.NONE;
        long started=System.nanoTime();frameUploadBytes=0;frameUploadLimit=uploadLimit;boolean progressed=false;
        try{
            long workNow=started;boolean schedule=(job!=null||workNow>=nextWorkProbe)&&waitingRevision!=resources.revision();
            boolean missingWork=job!=null||stream.queuedJobs()>0;
            if(schedule){
            // A mesh needs its six neighboring source sections too. Queue a bounded
            // working set of complete neighborhoods before filling the queue with more
            // centers; otherwise their dependencies sit behind unrelated requests.
            missingWork|=prefetch(visibleKeys,buildCursor,deadline,false);
            boolean warm=resources.warm()&&meshes.usedBytes()<meshes.capacity()*3/4&&meshes.size()<24576;
            if(warm&&stream.queuedJobs()<16)missingWork|=prefetch(candidates,surroundingCursor,deadline,true);
            }
            boolean warm=resources.warm()&&meshes.usedBytes()<meshes.capacity()*3/4&&meshes.size()<24576;
            if(schedule&&!missingWork)nextWorkProbe=Long.MAX_VALUE;
            if(schedule&&missingWork){
                nextWorkProbe=workNow+50_000_000L;
                int completed=0;
                while(completed<1&&System.nanoTime()<deadline&&frameUploadBytes<frameUploadLimit){
                    if(job==null){
                        if(job==null&&!budgetSaturated)job=findJob(visibleKeys,deadline,false);
                        if(job==null&&warm)job=findJob(candidates,deadline,true);
                        if(job==null)break;
                    }
                    int cursorBefore=job.cursor,partsBefore=job.parts.size();
                    build(deadline);
                    if(job!=null){
                        if(job.cursor!=cursorBefore||job.parts.size()!=partsBefore){job.stalledSince=0;progressed=true;}
                        else if(waitingRevision!=resources.revision()){
                            if(job.stalledSince==0)job.stalledSince=System.nanoTime();
                            else if(System.nanoTime()-job.stalledSince>200_000_000L){coolingKey=job.key;coolingUntil=System.nanoTime()+100_000_000L;discardJob();break;}
                        }
                        if(job.vertices>=4096)flushPart();break;
                    }
                    completed++;progressed=true;nextWorkProbe=0;
                }
            }
            if(job==null)frameUploadBytes+=entities.build(entityCandidates,this,scene,deadline,frameUploadLimit-frameUploadBytes);
            return new RenderScheduler.Work(frameUploadBytes,progressed||frameUploadBytes>0);
        }catch(RuntimeException e){failure="Renderer paused: "+e;visible=false;frameReady=false;discardJob();BetterLitematicaClient.LOGGER.error("Projection renderer failed",e);return new RenderScheduler.Work(frameUploadBytes,false);}
        finally{lastBuildNanos+=System.nanoTime()-started;}
    }
    void drawFrame(WorldRenderContext context){
        if(!frameReady)return;
        try{renderMeshes(context,context.camera().getPos(),visibleKeys);entities.render(context,entityCandidates,opacity);}
        catch(RuntimeException e){failure="Renderer paused: "+e;visible=false;frameReady=false;discardJob();BetterLitematicaClient.LOGGER.error("Projection draw failed",e);}
    }
    private Job findJob(List<SectionKey> keys,long deadline,boolean surrounding){
        int start=Math.floorMod(surrounding?surroundingCursor:buildCursor,Math.max(1,keys.size()));
        for(int visited=0;visited<keys.size();visited++){
            int at=(start+visited)%keys.size();SectionKey key=keys.get(at);
            if(System.nanoTime()>=deadline)return null;
            if(surrounding&&visibleSet.contains(key)||key.equals(coolingKey)&&System.nanoTime()<coolingUntil)continue;
            if(!needsMesh(key)||failed.contains(key)||deferred.contains(key))continue;
            var section=section(key);if(section==null)continue;
            Job next=prepare(key,section);if(next!=null){if(surrounding)surroundingCursor=at+1;else buildCursor=at+1;return next;}
        }
        return null;
    }
    private boolean prefetch(List<SectionKey> keys,int cursor,long deadline,boolean surrounding){
        boolean missing=false;int neighborhoods=0,start=Math.floorMod(cursor,Math.max(1,keys.size()));
        for(int visited=0;visited<keys.size()&&neighborhoods<(surrounding?2:8);visited++){
            if(System.nanoTime()>=deadline||stream.queuedJobs()>=24)return true;
            var key=keys.get((start+visited)%keys.size());
            if(surrounding&&visibleSet.contains(key)||failed.contains(key)||deferred.contains(key)||!needsMesh(key))continue;
            missing=true;neighborhoods++;stream.request(key);
            for(var side:DIRECTIONS){int x=key.x()+side.getOffsetX(),y=key.y()+side.getOffsetY(),z=key.z()+side.getOffsetZ();
                if(x>=0&&y>=0&&z>=0)stream.request(new SectionKey(key.region(),x,y,z));
            }
        }
        return missing;
    }
    private Vec3i localBase(SectionKey key){return stream.source().metadata().regions().get(key.region()).sectionOrigin(key);}
    private static double distanceSquared(Vec3i a,Vec3i b){double x=(double)a.x()-b.x(),y=(double)a.y()-b.y(),z=(double)a.z()-b.z();return x*x+y*y+z*z;}
    private Box bounds(SectionKey key){
        var transform=layout.part(key.region()).transform();Vec3i base=localBase(key);int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(int x:new int[]{0,15})for(int y:new int[]{0,15})for(int z:new int[]{0,15}){
            Vec3i p=transform.apply(base.add(new Vec3i(x,y,z)));minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());
        }
        return new Box(minX,minY,minZ,maxX+1.0,maxY+1.0,maxZ+1.0);
    }
    private void build(long deadline){
        int processed=0;
        while(job!=null&&job.cursor<4096&&processed<BLOCKS_PER_FRAME&&System.nanoTime()<deadline){
            Job current=job;var transform=current.transform;var states=current.states;int i=current.section.nextNonZero(current.cursor);current.cursor=i;
            if(i==PackedSection.VOLUME)break;
            int x=i&15,z=(i>>>4)&15,y=i>>>8,id=current.section.globalId(i);
            BlockStateSpec spec=metadata().palette().get(id);
            if(spec.isAir()||spec.name().equals("minecraft:structure_void")||!displays(id)){current.cursor++;processed++;continue;}
            Vec3i local=current.localBase.add(new Vec3i(x,y,z)),world=transform.apply(local);
            if(!layer.contains(world)){current.cursor++;processed++;continue;}
            if(current.overlaps.size()>1){var owner=scene.sampleDisplayed(current.overlaps,world);if(owner!=null&&owner.unknown())return;
                if(owner==null||owner.renderer()!=this||owner.region().index()!=current.key.region()){current.cursor++;processed++;continue;}
                boolean pending=false;for(var direction:DIRECTIONS){var other=scene.sampleDisplayed(current.overlaps,world.add(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ())));if(other!=null&&other.unknown())pending=true;}if(pending)return;
            }
            BlockState state=states.resolve(id);var worldPos=new BlockPos(world.x(),world.y(),world.z());boolean loaded=WorldChunks.loaded(client.world,worldPos);
            BlockState actual=loaded?client.world.getBlockState(worldPos):null;
            boolean complete=!states.unresolvedState(id)&&completedBlock(loaded,actual,state);
            current.mask.set(i,complete);current.wrong.set(i,!states.unresolvedState(id)&&wrongBlock(loaded,actual,state));
            if(complete){current.omitted.set(i);current.cells.record(i,Block.getRawIdFromState(state));current.cursor++;processed++;continue;}
            quads.clear();fluidVertices.clear();specialModels.clear();
            if(state.isOf(Blocks.LIGHT)){
                if(current.vertices+4>MAX_PART_VERTICES&&!flushPart())return;
                if(!builder.isBuilding())builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);
                emitLight(state,world.subtract(transform.apply(current.localBase)));ownership.append(i,4);
                current.vertices+=4;current.cursor++;processed++;continue;
            }
            try{
                if(!state.getFluidState().isEmpty())client.getBlockRenderManager().renderFluid(new BlockPos(world.x(),world.y(),world.z()),current.view,fluidVertices,state.getFluidState());
                if(state.getRenderType()==BlockRenderType.INVISIBLE){
                    if(fluidVertices.size()>0){if(current.vertices+fluidVertices.size()>MAX_PART_VERTICES&&!flushPart())return;if(!builder.isBuilding())builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);var relative=world.subtract(transform.apply(current.localBase));fluidVertices.emit(builder,relative.x()-(world.x()&15),relative.y()-(world.y()&15),relative.z()-(world.z()&15));ownership.append(i,fluidVertices.size());current.vertices+=fluidVertices.size();}
                    current.cursor++;processed++;continue;
                }
                if(state.getRenderType()==BlockRenderType.MODEL){
                int culledSides=0,mask=0;
                for(Direction side:DIRECTIONS)if(cull(current,x,y,z,world,state,side)){culledSides++;mask|=1<<side.ordinal();}
                if(culledSides==6){current.cursor++;processed++;continue;}
                BakedModel model=client.getBlockRenderManager().getModel(state);
                long seed=state.getRenderingSeed(new BlockPos(world.x(),world.y(),world.z()));
                for(Direction side:DIRECTIONS){
                    if((mask&(1<<side.ordinal()))!=0)continue;random.setSeed(seed);append(model.getQuads(state,side,random));
                }
                random.setSeed(seed);append(model.getQuads(state,null,random));
                if(quads.isEmpty()&&culledSides<6){
                    states.markUnsupported(id);state=StateResolver1201.marker();model=client.getBlockRenderManager().getModel(state);
                    for(Direction side:DIRECTIONS){random.setSeed(0);append(model.getQuads(state,side,random));}random.setSeed(0);append(model.getQuads(state,null,random));
                }
                }
                if(state.hasBlockEntity()){
                    if(projectionWorld==null)projectionWorld=new ProjectionWorld(client.world);projectionWorld.view(current.view,pos->blockEntity(current,pos));
                    var entity=blockEntity(current,new BlockPos(world.x(),world.y(),world.z()));if(entity!=null){var renderer=client.getBlockEntityRenderDispatcher().get(entity);if(renderer!=null)renderer.render(entity,0,new net.minecraft.client.util.math.MatrixStack(),specialModels,LightmapTextureManager.MAX_LIGHT_COORDINATE,OverlayTexture.DEFAULT_UV);else if(state.getRenderType()!=BlockRenderType.MODEL)states.markUnsupported(id);}
                }
            }catch(ProjectionBlockView.Pending waiting){return;}catch(RuntimeException e){states.markUnsupported(id);failure="Unsupported model: "+spec+" (block omitted)";current.cursor++;processed++;continue;}
            if(specialModels.vertices()>MAX_PART_VERTICES){states.markUnsupported(id);failure="特殊模型顶点超出预算";current.cursor++;processed++;continue;}
            if(specialModels.vertices()>0&&frameUploadBytes+(current.vertices+quads.size()*4+fluidVertices.size()+specialModels.vertices())*24>frameUploadLimit)return;
            if(quads.isEmpty()&&fluidVertices.size()==0&&specialModels.vertices()==0){current.cursor++;processed++;continue;}
            Vec3i relative=world.subtract(transform.apply(current.localBase));
            if(specialModels.vertices()>0){
                // Reserve the entire block before emitting it: a budget wait must never
                // replay an already uploaded regular face or a subset of special layers.
                if(!flushPart())return;
                var layers=specialModels.layers().entrySet().stream().filter(e->e.getValue().size()>0).toList();
                int regular=(quads.size()*4+fluidVertices.size())*24,offset=regular>0?1:0;
                int[] sizes=new int[layers.size()+offset];if(offset==1)sizes[0]=regular;
                for(int partIndex=0;partIndex<layers.size();partIndex++)sizes[partIndex+offset]=layers.get(partIndex).getValue().size()*24;
                var leases=reserve(sizes);if(leases==null)return;
                if(regular>0){builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);for(BakedQuad quad:quads)emit(quad,state,relative);fluidVertices.emit(builder,relative.x()-(world.x()&15),relative.y()-(world.y()&15),relative.z()-(world.z()&15));ownership.append(i,regular/24);upload(leases.get(0),regular,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false);}
                for(int partIndex=0;partIndex<layers.size();partIndex++){var special=layers.get(partIndex);builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);special.getValue().emit(builder,relative.x(),relative.y(),relative.z());ownership.append(i,special.getValue().size());upload(leases.get(partIndex+offset),sizes[partIndex+offset],special.getKey().texture(),special.getKey().intensity());}
            }else{
                if(current.vertices+quads.size()*4+fluidVertices.size()>MAX_PART_VERTICES&&!flushPart())return;
                if(!builder.isBuilding())builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);
                for(BakedQuad quad:quads)emit(quad,state,relative);fluidVertices.emit(builder,relative.x()-(world.x()&15),relative.y()-(world.y()&15),relative.z()-(world.z()&15));
                ownership.append(i,quads.size()*4+fluidVertices.size());current.vertices+=quads.size()*4+fluidVertices.size();
            }
            current.cursor++;processed++;
        }
        if(job!=null&&job.cursor==4096&&flushPart()){
            var cells=job.cells.build();
            Mesh mesh=new Mesh(List.copyOf(job.parts),Math.max(128,job.bytes+job.visibilityBytes+cells.estimatedBytes()+1792),bounds(job.key),job.resources,job.worldRevision,job.mask,job.wrong,cells,job.omitted);
            boolean onscreen=visibleSet.contains(job.key);
            var old=meshes.get(job.key);boolean room=old==null||(onscreen?meshes.canReplaceProtecting(job.key,mesh.bytes(),visibleSet):meshes.canReplaceWithoutEviction(job.key,mesh.bytes()));
            // Admission is established before detaching the old complete value. Replacement is
            // synchronous on the render thread; no frame can see the cache between remove/put.
            if(old!=null&&room)meshes.remove(job.key);
            boolean admitted=room&&(onscreen?meshes.tryPutProtecting(job.key,mesh,visibleSet):meshes.tryPutWithoutEviction(job.key,mesh));
            if(!admitted){mesh.close();deferred.add(job.key);if(onscreen)budgetSaturated=true;}
            else {SectionKey key=job.key;worldRefresh.completed(key,job.worldRevision);job.resources.finish(onscreen?RenderResources.VISIBLE:RenderResources.NEARBY,()->{meshes.remove(key);deferred.clear();budgetSaturated=false;nextWorkProbe=0;});}

            builtSections++;job=null;
        }
    }
    private net.minecraft.block.entity.BlockEntity blockEntity(Job current,BlockPos pos){
        var cached=blockEntities.get(pos);if(cached!=null)return cached;var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());var cell=scene.sampleDisplayed(current.overlaps,at);if(cell==null)return null;if(cell.unknown())throw new ProjectionBlockView.Pending();var owner=cell.renderer();var state=owner.resolve(cell.region().index(),cell.id());if(!state.hasBlockEntity())return null;
        net.minecraft.block.entity.BlockEntity entity=null;
        if(owner.details!=null&&(owner.edits==null||owner.edits.snapshot().patch(cell.region().section(cell.local()),cell.region().cell(cell.local()))==null||owner.edits.snapshot().patch(cell.region().section(cell.local()),cell.region().cell(cell.local())).blockData())){var data=owner.details.parts().get(cell.region().index()).blocks().get(cell.local());if(data!=null){var tag=(net.minecraft.nbt.NbtCompound)NbtBridge.game(data);BlockEntityNbtTransform.placed(tag,cell.region().transform());tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());entity=net.minecraft.block.entity.BlockEntity.createFromNbt(pos,state,tag);}}
        if(entity==null&&state.getBlock() instanceof BlockEntityProvider provider)entity=provider.createBlockEntity(pos,state);
        if(entity!=null){entity.setWorld(projectionWorld);if(blockEntities.size()>=256)blockEntities.remove(blockEntities.keySet().iterator().next());blockEntities.put(pos.toImmutable(),entity);}return entity;
    }
    private void append(List<BakedQuad> list){if(list.size()>MAX_BLOCK_QUADS-quads.size())throw new IllegalArgumentException("Model exceeds 512 quads per block");quads.addAll(list);}
    private Job prepare(SectionKey key,PackedSection section){
        Job next=new Job(key,section,localBase(key));var old=meshes.get(key);next.mask=old==null?new QuadVisibility.Mask():old.mask;next.wrong=old==null?new QuadVisibility.Mask():old.wrong;next.worldRevision=worldRefresh.revision(key);next.transform=layout.part(key.region()).transform();next.states=resolver(key.region());next.offsets=new Vec3i[6];
        for(var side:DIRECTIONS)next.offsets[side.ordinal()]=next.transform.inverse(next.transform.origin().add(new Vec3i(side.getOffsetX(),side.getOffsetY(),side.getOffsetZ())));
        var box=bounds(key);next.overlaps=scene.overlapping(new PlacementBounds(new Vec3i((int)box.minX-1,(int)box.minY-1,(int)box.minZ-1),new Vec3i((int)box.maxX,(int)box.maxY,(int)box.maxZ)));
        next.view=new ProjectionBlockView(client.world,scene,next.overlaps,layer);
        boolean ready=true;PackedSection[] neighbors=new PackedSection[6];
        for(Direction side:DIRECTIONS){
            int x=key.x()+side.getOffsetX(),y=key.y()+side.getOffsetY(),z=key.z()+side.getOffsetZ();if(x<0||y<0||z<0)continue;
            SectionKey neighbor=new SectionKey(key.region(),x,y,z);
            PackedSection data=section(neighbor);if(data==null){stream.request(neighbor);ready=false;}else neighbors[side.ordinal()]=data;
        }
        next.neighborhood=new SectionNeighborhood(section,neighbors[Direction.WEST.ordinal()],neighbors[Direction.EAST.ordinal()],neighbors[Direction.DOWN.ordinal()],neighbors[Direction.UP.ordinal()],neighbors[Direction.NORTH.ordinal()],neighbors[Direction.SOUTH.ordinal()]);
        if(!ready)return null;int demand=visibleSet.contains(key)?RenderResources.VISIBLE:RenderResources.NEARBY;next.resources=meshes.contains(key)?resources.beginReplacement(demand):resources.begin(demand);if(next.resources==null)waitingRevision=resources.revision();return next.resources==null?null:next;
    }
    static boolean hidesFace(BlockState state,BlockState neighbor,Direction side){
        if(neighbor.isAir())return false;
        if(state.isSideInvisible(neighbor,side))return true;
        return neighbor.isOpaqueFullCube(EmptyBlockView.INSTANCE,BlockPos.ORIGIN);
    }
    private boolean cull(Job current,int x,int y,int z,Vec3i world,BlockState state,Direction side){
        // The usual single-placement, full-height path needs only local palette data.
        // Avoid six temporary world positions for every source block in a huge schematic.
        Vec3i neighborPosition=null;
        if(layer.mode()!=LayerRange.Mode.ALL||current.overlaps.size()>1){neighborPosition=world.add(new Vec3i(side.getOffsetX(),side.getOffsetY(),side.getOffsetZ()));if(!layer.contains(neighborPosition))return false;}
        BlockState neighbor;
        if(current.overlaps.size()>1){var cell=scene.sampleDisplayed(current.overlaps,neighborPosition);if(cell==null||cell.unknown())return false;neighbor=cell.renderer().resolve(cell.region().index(),cell.id());}
        else {Vec3i delta=current.offsets[side.ordinal()];int id=current.neighborhood.globalId(x+delta.x(),y+delta.y(),z+delta.z());if(!displays(id))return false;neighbor=current.states.resolve(id);}
        if(neighbor.isAir()||neighbor.isOf(Blocks.STRUCTURE_VOID))return false;
        return state.isSideInvisible(neighbor,side)||opaqueStates.computeIfAbsent(neighbor,v->v.isOpaqueFullCube(EmptyBlockView.INSTANCE,BlockPos.ORIGIN));
    }
    private void emitLight(BlockState state,Vec3i position){
        int level=state.get(net.minecraft.state.property.Properties.LEVEL_15);
        var sprite=lightSprites[level];
        if(sprite==null){sprite=client.getItemRenderer().getModel(LightProjectionMarker.stack(state),null,null,0).getParticleSprite();lightSprites[level]=sprite;}
        // Four coincident centers; alpha=0 tags a marker and RG encodes view-space corners.
        // The vertex shader expands the quad towards the camera without rebuilding its mesh.
        for(int vertex=0;vertex<4;vertex++){
            boolean right=vertex==1||vertex==2,top=vertex>=2;
            builder.vertex(position.x()+0.5,position.y()+0.5,position.z()+0.5)
                .texture(right?sprite.getMaxU():sprite.getMinU(),top?sprite.getMinV():sprite.getMaxV())
                .color(right?255:0,top?255:0,255,0).next();
        }
    }
    private void emit(BakedQuad quad,BlockState state,Vec3i position){
        int[] data=quad.getVertexData();int stride=data.length/4;if(stride<6||data.length%4!=0)throw new IllegalArgumentException("Unknown baked vertex format");
        int tint=0xffffff;
        if(quad.hasColor())try{tint=client.getBlockColors().getColor(state,null,null,quad.getColorIndex());}catch(RuntimeException ignored){/* Context-sensitive tint: neutral fallback in preview. */}
        float shade=quad.getFace()==Direction.UP?1f:quad.getFace()==Direction.DOWN?0.6f:quad.getFace().getAxis()==Direction.Axis.X?0.8f:0.9f;
        for(int vertex=0;vertex<4;vertex++){
            int at=vertex*stride,color=data[at+3];float red=(color&255)/255f*((tint>>>16)&255)/255f*shade,green=((color>>>8)&255)/255f*((tint>>>8)&255)/255f*shade,blue=((color>>>16)&255)/255f*(tint&255)/255f*shade;
            builder.vertex(position.x()+Float.intBitsToFloat(data[at]),position.y()+Float.intBitsToFloat(data[at+1]),position.z()+Float.intBitsToFloat(data[at+2]))
                .texture(Float.intBitsToFloat(data[at+4]),Float.intBitsToFloat(data[at+5])).color(red,green,blue,1f).next();
        }
    }
    private List<RenderResources.Lease> reserve(int[] sizes){
        try{boolean onscreen=visibleSet.contains(job.key);var result=job.resources.reserve(sizes,onscreen?RenderResources.VISIBLE:RenderResources.NEARBY);if(result==null){if(!onscreen){discardJob();waitingRevision=-1;nextWorkProbe=0;}else waitingRevision=resources.revision();}return result;}
        catch(IllegalArgumentException e){failed.add(job.key);failure="区段模型超过资源预算";discardJob();return null;}
    }
    private void upload(RenderResources.Lease lease,int bytes,net.minecraft.util.Identifier texture,boolean intensity){
        short[] owners=ownership.build(bytes/24);var visibility=new QuadVisibility.Part(owners);
        for(short cell:owners)if(cell>=0){int id=job.section.globalId(cell);var state=job.states.resolve(id);if(!job.states.unresolvedState(id))job.cells.record(cell,Block.getRawIdFromState(state));}
        VertexBuffer buffer=new VertexBuffer();lease.attach(buffer::close);
        try{buffer.bind();builder.end();buffer.upload(builder);}finally{VertexBuffer.unbind();}
        job.parts.add(new Part(buffer,bytes,texture,intensity,visibility));job.visibilityBytes+=visibility.memoryEstimate();ownership.clear();job.bytes+=bytes;frameUploadBytes+=bytes;
    }
    private boolean flushPart(){
        if(job.vertices==0)return true;int bytes=job.vertices*24;
        if(frameUploadBytes+bytes>frameUploadLimit)return false;
        var leases=reserve(new int[]{bytes});if(leases==null)return false;
        upload(leases.get(0),bytes,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false);job.vertices=0;return true;
    }
    private void renderMeshes(WorldRenderContext context,Vec3d camera,List<SectionKey> visibleKeys){
        int previousTexture=RenderSystem.getShaderTexture(0);Shader previous=RenderSystem.getShader();float[] previousColor=RenderSystem.getShaderColor().clone();
        Shader shader=ProjectionShaders.surface();boolean bound=false;
        try{
            RenderSystem.setShader(ProjectionShaders::surface);RenderSystem.setShaderTexture(0,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            RenderSystem.setShaderColor(1,1,1,opacity);RenderSystem.disableBlend();RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableCull();RenderSystem.enablePolygonOffset();RenderSystem.polygonOffset(-1f,-1f);
            Matrix4f view=new Matrix4f();
            for(SectionKey key:visibleKeys){
                Mesh mesh=meshes.get(key);List<Part> parts=mesh!=null?mesh.parts:job!=null&&job.key.equals(key)?job.parts:List.of();if(parts.isEmpty())continue;var mask=mesh!=null?mesh.mask:job.mask;var wrong=mesh!=null?mesh.wrong:job.wrong;Vec3i base=worldBases.get(key);if(base==null){base=layout.part(key.region()).transform().apply(localBase(key));worldBases.put(key,base);}
                view.set(LegacyMatrices.joml(context.matrixStack().peek().getPositionMatrix())).translate((float)(base.x()-camera.x),(float)(base.y()-camera.y),(float)(base.z()-camera.z));
                // This shader uses only Sampler0, ModelViewMat, ProjMat and ColorModulator.
                // Bind the common state once per placement, then upload only the section matrix.
                // VertexBuffer.draw(matrix, projection, shader) would rebind all of it for every part.
                if(shader.modelViewMat!=null)shader.modelViewMat.set(LegacyMatrices.minecraft(view));
                if(!bound){
                    shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));
                    if(shader.projectionMat!=null)shader.projectionMat.set(context.projectionMatrix());
                    if(shader.colorModulator!=null)shader.colorModulator.set(1f,1f,1f,opacity);
                    shader.getUniformOrDefault("TextureMode").set(0);shader.getUniformOrDefault("SurfaceTint").set(0);bound=true;shader.bind();
                }else if(shader.modelViewMat!=null)shader.modelViewMat.upload();
                for(Part part:parts){shader.getUniformOrDefault("TextureMode").set(part.intensity?1:0);if(!part.texture.equals(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)){RenderSystem.setShaderTexture(0,part.texture);shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();}
                    shader.getUniformOrDefault("TextureMode").set(part.intensity?1:0);var mode=shader.getUniform("TextureMode");if(mode!=null)mode.upload();LegacyVertices.bind(part.buffer);
                    if(wrong.isEmpty()){
                        if(mask.isEmpty()||part.visibility.fullyVisible(mask)){part.buffer.drawElements();drawCalls++;}
                        else {var type=part.buffer.elementFormat;if(ProjectionDraw.drawRanges(type.count,type.size,part.visibility,mask))drawCalls++;}
                    }else{
                        var tint=shader.getUniform("SurfaceTint");
                        var type=part.buffer.elementFormat;if(ProjectionDraw.drawRanges(type.count,type.size,part.visibility,mask,wrong,false))drawCalls++;
                        if(tint!=null){tint.set(1);tint.upload();}if(ProjectionDraw.drawRanges(type.count,type.size,part.visibility,mask,wrong,true))drawCalls++;
                        if(tint!=null){tint.set(0);tint.upload();}
                    }
                    if(!part.texture.equals(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)){RenderSystem.setShaderTexture(0,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();}
                }
            }
        }finally{
            if(bound)shader.unbind();
            LegacyVertices.unbind();RenderSystem.setShaderTexture(0,previousTexture);RenderSystem.disablePolygonOffset();RenderSystem.polygonOffset(0f,0f);RenderSystem.depthMask(true);RenderSystem.enableCull();RenderSystem.disableBlend();
            RenderSystem.setShaderColor(previousColor[0],previousColor[1],previousColor[2],previousColor[3]);if(previous!=null)RenderSystem.setShader(()->previous);
        }
    }
    private void discardJob(){
        if(builder.isBuilding())builder.end();builder.clear();ownership.clear();
        if(job!=null){job.resources.close();job=null;}
    }
    @Override public void close(){queryGeneration++;if(query!=null)query.cancel(false);queryWorker.shutdownNow();discardJob();meshes.close();editedSections.close();entities.close();try{sourceLease.close();}catch(java.io.IOException e){failure=e.toString();}}
}
