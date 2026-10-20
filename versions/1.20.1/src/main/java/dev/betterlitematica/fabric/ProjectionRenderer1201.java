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
import net.minecraft.util.math.random.Random;
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
    /** Blocks: surfaces nearer than NEAR_CLEAR are removed and fully back by NEAR_FULL. */
    private static final float NEAR_CLEAR=.75f,NEAR_FULL=2.5f;
    private static final long BUILD_NANOS=4_000_000,MAX_ACTIVE_SECTION_BYTES=8L<<20;
    /** Decoding threads and requests kept in flight; the streamer's queue still bounds memory. */
    private static final int DECODE_THREADS=Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()-2)),PREFETCH_IN_FLIGHT=32;
    private static final Direction[] DIRECTIONS=Direction.values();
    /** Block-atlas geometry lives in a shared page ({@code slice}); special textures keep their own buffer. */
    private record Part(VertexBuffer buffer,ProjectionPages.Slice slice,int bytes,net.minecraft.util.Identifier texture,boolean intensity,QuadVisibility.Part visibility) {}
    private record Mesh(List<Part> parts,long bytes,Box bounds,RenderResources.Group resources,long worldRevision,QuadVisibility.Mask mask,QuadVisibility.Mask wrong,RenderedCells cells,BitSet omitted) implements AutoCloseable {
        @Override public void close(){resources.close();}
    }
    private static final class Job {
        RenderResources.Group resources;final SectionKey key;final PackedSection section;final Vec3i localBase;Vec3i worldBase;PlacementTransform transform;StateResolver1201 states;Vec3i[] offsets;List<ProjectionScene.Part> overlaps;ProjectionBlockView view;long stalledSince,worldRevision;
        final List<Part> parts=new ArrayList<>();final RenderedCells.Builder cells=new RenderedCells.Builder();final BitSet omitted=new BitSet(4096);QuadVisibility.Mask mask,wrong;long visibilityBytes;SectionNeighborhood neighborhood;int cursor,vertices;long bytes;
        Job(SectionKey key,PackedSection section,Vec3i localBase){this.key=key;this.section=section;this.localBase=localBase;}
    }
    private final MinecraftClient client;private final RenderResources resources;private final ProjectionPages pages;private final Matrix4f arenaMatrix=new Matrix4f();private boolean frameReady,resourcesVisible;private long waitingRevision=-1;
    private final dev.betterlitematica.io.AuxiliaryData details;private final String detailsError;private ProjectionWorld projectionWorld;
    private final Map<BlockPos,net.minecraft.block.entity.BlockEntity> blockEntities=new LinkedHashMap<>();
    private final ProjectionModels specialModels=new ProjectionModels();private final ProjectionEntities entities;private List<ProjectionEntities.Candidate> entityCandidates=List.of();
    private final SectionStreamer stream;private final LoadCoordinator.Loaded sourceLease;
    private final SpatialIndex index;
    private SchematicEdits edits;
    private final WeightedLru<SectionKey,PackedSection> editedSections=new WeightedLru<>(16L<<20,PackedSection::estimatedBytes,value->{});
    // Keep meshes across camera turns; evict cold meshes only under actual memory pressure.
    // The shared render budget decides residency (nearest first); this cache only bounds bookkeeping.
    private final WeightedLru<SectionKey,Mesh> meshes=new WeightedLru<>(Long.MAX_VALUE>>2,MAX_CANDIDATES*2,Mesh::bytes,Mesh::close);
    // One entry per candidate: a followed band touching thousands of sections must not degrade into "everything changed".
    private final MeshRefresh<SectionKey> worldRefresh=new MeshRefresh<>(MAX_CANDIDATES);
    private final BufferBuilder builder=new BufferBuilder(MAX_PART_VERTICES*24);
    private final QuadVisibility.Builder ownership=new QuadVisibility.Builder();
    private final Random random=Random.create(0);
    private final Set<SectionKey> failed=new HashSet<>(),deferred=new HashSet<>();
    private final Set<SectionKey> visibleSet=new HashSet<>();
    private final List<SectionKey> visibleKeys=new ArrayList<>();
    /**
     * A nearby query with everything culling needs, computed off-thread: per candidate its world box (six doubles)
     * and arena, per arena the union box. A frame then tests arenas first and only boundary arenas section by section.
     */
    private record QueryResult(long generation,Vec3i camera,int radius,List<SectionKey> keys,Set<SectionKey> membership,Map<SectionKey,Integer> indices,double[] boxes,int[] arenaOf,double[] arenaBoxes,List<ProjectionEntities.Candidate> entities){}
    private static QueryResult query(long generation,Vec3i camera,int radius,List<SectionKey> keys,PlacementLayout layout,List<Region> regions,List<ProjectionEntities.Candidate> entities){
        int n=keys.size();double[] boxes=new double[n*6];int[] arenaOf=new int[n];var indices=new HashMap<SectionKey,Integer>(n*2);var arenas=new HashMap<ProjectionPages.Origin,Integer>();double[] arenaBoxes=new double[96];
        for(int i=0;i<n;i++){
            var key=keys.get(i);indices.put(key,i);var transform=layout.part(key.region()).transform();var base=regions.get(key.region()).sectionOrigin(key);
            // Orthogonal transforms map opposite corners of the section to opposite corners of its world box.
            Vec3i a=transform.apply(base),b=transform.apply(base.add(new Vec3i(15,15,15)));
            boxes[i*6]=Math.min(a.x(),b.x());boxes[i*6+1]=Math.min(a.y(),b.y());boxes[i*6+2]=Math.min(a.z(),b.z());boxes[i*6+3]=Math.max(a.x(),b.x())+1.0;boxes[i*6+4]=Math.max(a.y(),b.y())+1.0;boxes[i*6+5]=Math.max(a.z(),b.z())+1.0;
            var origin=ProjectionPages.origin(a.x(),a.y(),a.z());Integer arena=arenas.get(origin);
            if(arena==null){arena=arenas.size();arenas.put(origin,arena);if(arena*6+6>arenaBoxes.length)arenaBoxes=Arrays.copyOf(arenaBoxes,arenaBoxes.length*2);for(int k=0;k<3;k++){arenaBoxes[arena*6+k]=Double.POSITIVE_INFINITY;arenaBoxes[arena*6+3+k]=Double.NEGATIVE_INFINITY;}}
            arenaOf[i]=arena;for(int k=0;k<3;k++){arenaBoxes[arena*6+k]=Math.min(arenaBoxes[arena*6+k],boxes[i*6+k]);arenaBoxes[arena*6+3+k]=Math.max(arenaBoxes[arena*6+3+k],boxes[i*6+3+k]);}
        }
        return new QueryResult(generation,camera,radius,keys,indices.keySet(),indices,boxes,arenaOf,Arrays.copyOf(arenaBoxes,arenas.size()*6),entities);
    }
    private Map<SectionKey,Integer> candidateIndex=Map.of();private double[] candidateBoxes=new double[0],arenaBoxes=new double[0];private int[] candidateArena=new int[0];
    private boolean[] candidateVisible=new boolean[0];private byte[] arenaCull=new byte[0];
    private final org.joml.FrustumIntersection cull=new org.joml.FrustumIntersection();private final Matrix4f cullMatrix=new Matrix4f();
    private static final byte OUTSIDE=0,PARTIAL=1,INSIDE=2;
    /** Half a block of slack: the render projection includes view bobbing, so edges must not pop. */
    private static final float CULL_MARGIN=.5f;
    private final ExecutorService queryWorker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"betterlitematica-spatial");t.setDaemon(true);return t;});
    // Only one future is submitted at a time, so executor backlog is bounded to one query.
    private CompletableFuture<QueryResult> query;
    private long queryGeneration;
    private final net.minecraft.client.texture.Sprite[] lightSprites=new net.minecraft.client.texture.Sprite[16];
    private long nextWorkProbe,decodedRevision=-1;
    private int buildCursor,surroundingCursor;
    private boolean visibilityDirty=true;
    private Vec3d lastCullCamera;
    private final Matrix4f lastCullView=new Matrix4f(),lastCullProjection=new Matrix4f();
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
    // Per-frame scheduling state: source requests are queued once per frame, hole search stops once exhausted.
    private boolean prefetchedThisFrame,frameMissingWork,holesExhausted,visibleRefreshExhausted,surroundingExhausted;
    private final LinkedHashSet<LayerRange> unsettledLayers=new LinkedHashSet<>();private boolean unsettledOverflow;private long layerChangedAt;
    private LayerRange semantic=LayerRange.ALL;private int capAxis=-1,clipAxis=-1;private long capLo,capHi;private double clipFrom,clipTo,clipFade;
    private static final int FOLLOW_SPAN=6;
    private Vec3d distanceOrigin=Vec3d.ZERO;
    /** Squared distance from the viewer to a section's centre; the residency key under budget pressure. */
    private double sectionDistance(SectionKey key){
        Integer at=candidateIndex.get(key);if(at==null)return distance(bounds(key));
        int i=at*6;double x=(candidateBoxes[i]+candidateBoxes[i+3])*.5-distanceOrigin.x,y=(candidateBoxes[i+1]+candidateBoxes[i+4])*.5-distanceOrigin.y,z=(candidateBoxes[i+2]+candidateBoxes[i+5])*.5-distanceOrigin.z;return x*x+y*y+z*z;
    }
    private double distance(Box box){double x=(box.minX+box.maxX)*.5-distanceOrigin.x,y=(box.minY+box.maxY)*.5-distanceOrigin.y,z=(box.minZ+box.maxZ)*.5-distanceOrigin.z;return x*x+y*y+z*z;}
    /** Off-screen sections around the viewer keep loading while they can still displace farther meshes. */
    private boolean surroundingAllowed(){
        return resources.distanceOrdered()?meshes.size()<MAX_CANDIDATES*2-1024:resources.warm()&&meshes.usedBytes()<meshes.capacity()*3/4&&meshes.size()<24576;
    }
    private final ArrayList<SectionKey> residencyKeys=new ArrayList<>();private int residencyCursor;private boolean releaseWarned;
    /** Up to 512 resident meshes per frame take their new distance; changes under a sixteenth are not worth a re-sort. */
    private void refreshResidency(){
        for(int n=0;n<512&&residencyCursor<residencyKeys.size();n++){
            var key=residencyKeys.get(residencyCursor++);var mesh=meshes.get(key);if(mesh==null)continue;var group=mesh.resources();
            double next=candidateSet.contains(key)?distance(mesh.bounds()):Double.POSITIVE_INFINITY,previous=group.distance();
            if(Double.isInfinite(next)!=Double.isInfinite(previous)||Math.abs(next-previous)>previous/16)group.distance(next);
        }
        if(residencyCursor>=residencyKeys.size()&&!residencyKeys.isEmpty()){residencyKeys.clear();residencyCursor=0;}
    }
    private static final int MAX_UNSETTLED_LAYERS=256;private static final long LAYER_SETTLE_NANOS=300_000_000L;
    private float opacity=0.45f;
    void opacity(float value){opacity=value;}
    private int frameUploadBytes,frameUploadLimit=UPLOAD_BYTES_PER_FRAME,drawCalls;private long builtSections,lastBuildNanos;private String failure="";
    ProjectionRenderer1201(MinecraftClient client,LoadCoordinator.Loaded loaded,RenderResources resources){
        this.client=client;this.resources=resources;pages=new ProjectionPages(resources);resources.concurrentBuilds(true);sourceLease=loaded;details=loaded.details();detailsError=loaded.detailsError();entities=new ProjectionEntities(client,details,resources);stream=loaded.stream();stream.parallelism(DECODE_THREADS);index=loaded.index();place(new Placement(UUID.randomUUID(),"preview","preview.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false));scene=new ProjectionScene(List.of(this));
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
        // In-flight builds read the old scene: overlaps may have appeared, so every one restarts.
        discardMeshing();
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
    void draft(SchematicEdits value){edits=value;editedSections.close();resolvers.clear();discardMeshing();palettes.clear();}
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
        for(var key:changed)editedSections.remove(key);resolvers.clear();blockEntities.clear();discardMeshing();palettes.clear();
        queryGeneration++;lastCamera=null;nextWorkProbe=0;waitingRevision=-1;
    }
    void invalidateWorld(List<PlacementBounds> boxes){
        var affected=new HashSet<SectionKey>();
        for(var box:boxes)for(var part:layout.overlapping(box)){
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(int x:new int[]{box.min().x(),box.max().x()})for(int y:new int[]{box.min().y(),box.max().y()})for(int z:new int[]{box.min().z(),box.max().z()}){var local=part.local(new Vec3i(x,y,z)).subtract(part.region().min());minX=Math.min(minX,local.x());minY=Math.min(minY,local.y());minZ=Math.min(minZ,local.z());maxX=Math.max(maxX,local.x());maxY=Math.max(maxY,local.y());maxZ=Math.max(maxZ,local.z());}
            var size=part.region().size();for(int y=Math.max(0,minY>>4);y<=Math.min((size.y()-1)>>4,maxY>>4);y++)for(int z=Math.max(0,minZ>>4);z<=Math.min((size.z()-1)>>4,maxZ>>4);z++)for(int x=Math.max(0,minX>>4);x<=Math.min((size.x()-1)>>4,maxX>>4);x++)affected.add(new SectionKey(part.index(),x,y,z));
        }
        if(job!=null&&affected.contains(job.key))discardJob();for(var key:affected){meshes.remove(key);failed.remove(key);deferred.remove(key);discardMeshing(key);}blockEntities.clear();nextWorkProbe=0;budgetSaturated=false;waitingRevision=-1;
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
                    if(meshes.contains(key)||meshing.containsKey(key)||job!=null&&job.key.equals(key)){
                        worldRefresh.changed(key);localWorldRefreshes++;changed=true;
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
        boolean changed=false;boolean loaded=WorldChunks.loaded(client.world,pos);
        var parts=layout.at(world,64);if(parts==null){worldAllChanged();return;}
        for(var part:parts){
            var local=part.local(world);var key=part.section(local);int cell=part.cell(local);
            var mesh=meshes.get(key);var active=job!=null&&job.key.equals(key)?job:null;
            changed|=failed.remove(key)|deferred.remove(key);
            var task=meshing.get(key);if(task!=null)task.changed.set(cell);
            int expected=mesh==null?-1:mesh.cells.state(cell);
            boolean complete=expected>=0&&completedBlock(loaded,actual,Block.getStateFromRawId(expected));
            if(expected>=0){boolean dirty=mesh.mask.set(cell,complete)|mesh.wrong.set(cell,wrongBlock(loaded,actual,Block.getStateFromRawId(expected)));if(dirty&&visibleSet.contains(key))drawRevision++;}
            boolean rebuild=mesh!=null&&mesh.omitted.get(cell)&&!complete;
            if(active!=null){
                int id=active.section.globalId(cell);var state=active.states.resolve(id);
                if(!active.states.unresolvedState(id)){
                    boolean activeComplete=completedBlock(loaded,actual,state);active.mask.set(cell,activeComplete);active.wrong.set(cell,wrongBlock(loaded,actual,state));
                    // A build may already have skipped this cell while it was complete.
                    if(!activeComplete&&active.omitted.get(cell))rebuild=true;
                }
            }
            if(rebuild){worldRefresh.changed(key);localWorldRefreshes++;changed=true;}
        }
        if(changed)wakeWorldRefresh();
    }
    private long fullWorldRefreshes,localWorldRefreshes;
    void worldAllChanged(){fullWorldRefreshes++;worldRefresh.allChanged();failed.clear();deferred.clear();wakeWorldRefresh();}
    void worldChunkUnloaded(int x,int z){
        drawRevision++;
        if(client.world==null)return;
        var box=new PlacementBounds(new Vec3i(x<<4,client.world.getBottomY(),z<<4),new Vec3i((x<<4)+15,client.world.getTopY()-1,(z<<4)+15));int visited=0;
        for(var part:layout.overlapping(box)){var range=MeshRefresh.sourceSections(part,box);if(range==null)continue;
            for(int y=range.min().y();y<=range.max().y();y++)for(int sz=range.min().z();sz<=range.max().z();sz++)for(int sx=range.min().x();sx<=range.max().x();sx++){
                if(++visited>8192){meshes.forEach((key,mesh)->mesh.wrong.clear());if(job!=null)job.wrong.clear();meshing.values().forEach(task->task.unloaded=true);return;}
                var key=new SectionKey(part.index(),sx,y,sz);var mesh=meshes.get(key);if(mesh!=null)mesh.wrong.clear();if(job!=null&&job.key.equals(key))job.wrong.clear();var task=meshing.get(key);if(task!=null)task.unloaded=true;
            }
        }
    }
    private void wakeWorldRefresh(){nextWorkProbe=0;budgetSaturated=false;waitingRevision=-1;holesExhausted=visibleRefreshExhausted=surroundingExhausted=false;}
    private boolean needsMesh(SectionKey key){var mesh=meshes.get(key);return mesh==null||worldRefresh.stale(key,mesh.worldRevision());}
    static boolean completedBlock(boolean loaded,BlockState actual,BlockState expected){return PrinterRules.comparison(loaded,actual,expected)==dev.betterlitematica.core.Comparison.MATCH;}
    static boolean wrongBlock(boolean loaded,BlockState actual,BlockState expected){var comparison=PrinterRules.comparison(loaded,actual,expected);return comparison==dev.betterlitematica.core.Comparison.WRONG_BLOCK||comparison==dev.betterlitematica.core.Comparison.WRONG_STATE;}
    private static final PackedSection ANALYSIS_AIR=PackedSection.fromGlobalIds(new int[4096]);
    PackedSection analysisSection(SectionKey key){stream.drain();return section(key);}
    int sample(SectionKey key,int position){stream.drain();return sampleRaw(key,position);}
    int sampleRaw(SectionKey key,int position){
        var patch=edits==null?null:edits.snapshot().patch(key,position);return patch==null?baseState(key,position):patch.state();
    }
    void place(Placement next){
        layout=new PlacementLayout(next,metadata().regions());displayPalette=null;invalidate();
    }
    /**
     * Fixed layers: the surface shader clips every mesh to the new range at once, so nothing is rebuilt while the
     * range keeps changing; once it has rested, one pass marks the sections whose cells or culling neighbours
     * differ from any range seen since, and they replace their old meshes as they finish.
     *
     * Following the player vertically: meshes are built {@link #FOLLOW_SPAN} cells past the boundary and keep
     * top and bottom faces in that band, so the shader can cut anywhere inside it with a capped cross-section.
     * Only when the boundary nears the edge of the built band is the band recentred and rebuilt.
     */
    void layer(LayerRange next){layer(next,false);}
    void layer(LayerRange next,boolean follow){
        var before=layer;int beforeAxis=capAxis;long beforeLo=capLo,beforeHi=capHi;
        try{applyLayer(next,follow);}finally{if(!layer.equals(before)||capAxis!=beforeAxis||capLo!=beforeLo||capHi!=beforeHi)layerRevision++;}
    }
    private void applyLayer(LayerRange next,boolean follow){
        boolean smooth=follow&&next.axis()==LayerRange.Axis.Y&&(next.mode()==LayerRange.Mode.ABOVE||next.mode()==LayerRange.Mode.BELOW);
        boolean wasSmooth=capAxis>=0;semantic=next;
        if(smooth!=wasSmooth||smooth&&layer.mode()!=next.mode()){
            // Entering, leaving or flipping a followed layer changes every section: refresh all, keep old meshes.
            layer=smooth?followRange(next):next;caps(smooth);unsettledLayers.clear();unsettledOverflow=false;
            worldRefresh.allChanged();failed.clear();deferred.clear();wakeWorldRefresh();return;
        }
        if(smooth){
            if(followCovers(layer,next))return;
            long oldLo=capLo,oldHi=capHi;layer=followRange(next);caps(true);
            long lo=Math.min(oldLo,capLo)-1,hi=Math.max(oldHi,capHi)+1;
            meshes.forEach((key,mesh)->{var box=mesh.bounds();if(box.maxY>lo&&box.minY<=hi)worldRefresh.changed(key);});
            failed.clear();deferred.clear();wakeWorldRefresh();return;
        }
        var previous=layer;if(previous.equals(next))return;layer=next;
        if(unsettledLayers.size()<MAX_UNSETTLED_LAYERS)unsettledLayers.add(previous);else unsettledOverflow=true;
        layerChangedAt=System.nanoTime();
    }
    private static LayerRange followRange(LayerRange next){
        return next.mode()==LayerRange.Mode.ABOVE?new LayerRange(next.axis(),(int)Math.max(Integer.MIN_VALUE+1L,(long)next.min()-FOLLOW_SPAN),Integer.MAX_VALUE)
            :new LayerRange(next.axis(),Integer.MIN_VALUE,(int)Math.min(Integer.MAX_VALUE-1L,(long)next.max()+FOLLOW_SPAN));
    }
    /** The built band must hold one cell beyond the boundary (interpolated eye) and no more than two spans of slack. */
    private static boolean followCovers(LayerRange built,LayerRange next){
        if(built.mode()!=next.mode()||built.axis()!=next.axis())return false;
        return next.mode()==LayerRange.Mode.ABOVE?(long)built.min()<=(long)next.min()-1&&(long)built.min()>=(long)next.min()-2L*FOLLOW_SPAN
            :(long)built.max()>=(long)next.max()+2&&(long)built.max()<=(long)next.max()+2L*FOLLOW_SPAN;
    }
    private void caps(boolean smooth){
        if(!smooth){capAxis=-1;capLo=capHi=0;return;}
        capAxis=layer.axis().ordinal();
        if(layer.mode()==LayerRange.Mode.ABOVE){capLo=layer.min();capHi=(long)layer.min()+2L*FOLLOW_SPAN+2;}else{capHi=layer.max();capLo=(long)layer.max()-2L*FOLLOW_SPAN-2;}
    }
    /** Continuous visible slab along {@code axis}, set every frame; meshes may hold more cells than it shows. */
    void visualClip(int axis,double from,double to,double fade){clipAxis=axis;clipFrom=from;clipTo=to;clipFade=fade;}
    private void settleLayer(){
        if(unsettledLayers.isEmpty()&&!unsettledOverflow||System.nanoTime()-layerChangedAt<LAYER_SETTLE_NANOS)return;
        var seen=List.copyOf(unsettledLayers);boolean all=unsettledOverflow;unsettledLayers.clear();unsettledOverflow=false;
        for(var range:seen)if(LayerRange.sharedAxis(range,layer)==null)all=true;
        if(all)worldRefresh.allChanged();
        else meshes.forEach((key,mesh)->{var box=mesh.bounds();
            for(var range:seen){var axis=LayerRange.sharedAxis(range,layer);
                double from=switch(axis){case X->box.minX;case Y->box.minY;case Z->box.minZ;},to=switch(axis){case X->box.maxX;case Y->box.maxY;case Z->box.maxZ;};
                if(LayerRange.affects(range,layer,(int)Math.floor(from),(int)Math.ceil(to)-1)){worldRefresh.changed(key);break;}
            }});
        failed.clear();deferred.clear();wakeWorldRefresh();
    }
    LayerRange layer(){return layer;}
    void visible(boolean value){visible=value;}
    boolean visible(){return visible;}
    void suspend(){discardJob();discardMeshing();frameReady=false;resourcesVisible=false;if(!resources.distanceOrdered())meshes.forEach((key,mesh)->mesh.resources().priority(RenderResources.COLD));entities.suspend();}
    void invalidate(){
        discardJob();discardMeshing();palettes.clear();layerRevision++;meshes.close();worldRefresh.clear();editedSections.close();candidates=List.of();candidateSet=Set.of();candidateIndex=Map.of();candidateBoxes=new double[0];candidateArena=new int[0];arenaBoxes=new double[0];candidateVisible=new boolean[0];arenaCull=new byte[0];entityCandidates=List.of();entities.invalidate();visibleKeys.clear();Arrays.fill(lightSprites,null);opaqueStates.clear();failed.clear();deferred.clear();budgetSaturated=false;visibilityDirty=true;nextWorkProbe=0;waitingRevision=-1;visibleSet.clear();queryGeneration++;resolvers.clear();blockEntities.clear();projectionWorld=null;lastCamera=null;failure="";
    }
    String status(){return "Partial preview (nearby only, max "+MAX_CANDIDATES+" sections) | "+phaseTimes.text()+" | "+String.format(Locale.ROOT,"mesh=%d, drawn=%d, commands-rebuilt=%d, world-full=%d, world-local=%d, built=%d, off-thread=%d (%d in flight, %.2f ms avg), pages=%d (%.1f MiB), CPU=%.1f MiB, GPU-total=%.1f MiB, queue=%d, build=%.2f ms, unsupported=%d",
        meshes.size(),drawCalls,commandRebuilds,fullWorldRefreshes,localWorldRefreshes,builtSections,meshedSections,meshing.size(),meshedSections==0?0:meshNanos/1e6/meshedSections,pages.pages(),pages.reservedBytes()/1048576.0,stream.cachedBytes()/1048576.0,resources.usedBytes()/1048576.0,stream.queuedJobs(),lastBuildNanos/1e6,resolvers.values().stream().mapToInt(StateResolver1201::unsupportedCount).sum());}
    String error(){return !failure.isEmpty()?failure:!detailsError.isEmpty()?detailsError:!entities.error().isEmpty()?entities.error():stream.error();}
    /**
     * Render-thread time of this renderer, per phase, over the last complete second: average and worst frame in
     * milliseconds. Frame-time spikes can then be split into projection work and everything else.
     */
    private static final class PhaseTimes {
        private final long[] total=new long[3],worst=new long[3],current=new long[3],reported=new long[6];private long second=-1;private int frames,reportedFrames;
        void add(int phase,long nanos){current[phase]+=nanos;}
        void frame(){
            long now=System.nanoTime()/1_000_000_000L;
            for(int i=0;i<3;i++){total[i]+=current[i];worst[i]=Math.max(worst[i],current[i]);current[i]=0;}frames++;
            if(now!=second){if(second>=0){for(int i=0;i<3;i++){reported[i]=frames==0?0:total[i]/frames;reported[3+i]=worst[i];}reportedFrames=frames;}second=now;Arrays.fill(total,0);Arrays.fill(worst,0);frames=0;}
        }
        String text(){return String.format(Locale.ROOT,"render-thread ms avg/max: prepare=%.2f/%.2f, build=%.2f/%.2f, draw=%.2f/%.2f over %d frames",
            reported[0]/1e6,reported[3]/1e6,reported[1]/1e6,reported[4]/1e6,reported[2]/1e6,reported[5]/1e6,reportedFrames);}
    }
    private final PhaseTimes phaseTimes=new PhaseTimes();
    void prepareFrame(WorldRenderContext context,boolean active){
        phaseTimes.frame();long started=System.nanoTime();
        try{prepareFrameTimed(context,active);}finally{phaseTimes.add(0,System.nanoTime()-started);}
    }
    private void prepareFrameTimed(WorldRenderContext context,boolean active){
        settleLayer();
        stream.drain();drawCalls=0;lastBuildNanos=0;frameReady=active&&visible&&client.world!=null;prefetchedThisFrame=false;frameMissingWork=false;holesExhausted=visibleRefreshExhausted=surroundingExhausted=false;
        // Disk workers can finish between frames. Wake builds on admitted data instead of
        // leaving a ready section idle until the fallback polling interval expires.
        if(decodedRevision!=stream.revision()){decodedRevision=stream.revision();nextWorkProbe=0;}
        if(!frameReady){discardJob();if(!meshing.isEmpty())discardMeshing();if(resourcesVisible&&!resources.distanceOrdered())meshes.forEach((key,mesh)->mesh.resources().priority(RenderResources.COLD));resourcesVisible=false;entities.prepare(context,List.of(),false);return;}
        // Distance-ordered residency never reads the priority lanes: skip their bookkeeping entirely.
        boolean lanes=!resources.distanceOrdered(),refreshAllPriorities=lanes&&!resourcesVisible;
        boolean budgetChanged=visibilityDirty||!resourcesVisible;resourcesVisible=true;
        // Evicted meshes stay charged until released. Paged geometry releases without GL calls, so release far more
        // than the shared 64 per frame; otherwise fast flight fills the budget with pending deletions and leaves holes.
        try{resources.drain(4096,System.nanoTime()+500_000L);releaseWarned=false;}catch(RuntimeException e){if(!releaseWarned)BetterLitematicaClient.LOGGER.warn("Projection memory release will retry",e);releaseWarned=true;}
        try{
            Vec3d camera=context.camera().getPos();distanceOrigin=camera;Vec3i local=new Vec3i(MathHelper.floor(camera.x),MathHelper.floor(camera.y),MathHelper.floor(camera.z));
            int radius=Math.min(QUERY_RADIUS,Math.max(16,client.options.getViewDistance().getValue()*16));
            if(query!=null&&query.isDone()){
                QueryResult result=query.join();query=null;
                if(result.generation()==queryGeneration&&result.radius()==radius&&distanceSquared(result.camera(),local)<=4096){
                    var members=result.membership();budgetChanged=true;refreshAllPriorities=lanes;
                    failed.retainAll(members);visibilityDirty=true;nextWorkProbe=0;
                    visibleSet.retainAll(members);deferred.clear();budgetSaturated=false;
                    drawRevision++;candidates=result.keys();candidateSet=result.membership();candidateIndex=result.indices();candidateBoxes=result.boxes();candidateArena=result.arenaOf();arenaBoxes=result.arenaBoxes();
                    candidateVisible=new boolean[candidates.size()];for(var key:visibleSet){Integer at=candidateIndex.get(key);if(at!=null)candidateVisible[at]=true;}arenaCull=new byte[arenaBoxes.length/6];
                    entityCandidates=result.entities();lastCamera=result.camera();lastRadius=radius;
                    // Residency follows distance from the viewer. Re-sorting every resident mesh at once would stall this
                    // frame, so distances are refreshed a slice per frame (see refreshResidency).
                    residencyKeys.clear();meshes.forEach((key,mesh)->residencyKeys.add(key));residencyCursor=0;
                    if(job!=null&&!members.contains(job.key))discardJob();
                }
            }
            if(query==null&&(lastCamera==null||distanceSquared(lastCamera,local)>64||radius!=lastRadius)){
                long generation=queryGeneration;PlacementLayout snapshot=layout;
                var additions=edits==null?List.<SectionKey>of():edits.snapshot().sections().keySet().stream().filter(key->!stream.source().index().containsKey(key)).toList();
                var regions=stream.source().metadata().regions();
                query=CompletableFuture.supplyAsync(()->{var keys=index.nearest(local,radius,MAX_CANDIDATES,snapshot,additions);return query(generation,local,radius,keys,snapshot,regions,entities.nearby(snapshot,local,radius));},queryWorker);
            }
            var viewMatrix=context.matrixStack().peek().getPositionMatrix();
            if(visibilityDirty||!camera.equals(lastCullCamera)||!lastCullView.equals(viewMatrix)||!lastCullProjection.equals(context.projectionMatrix())){
            visibilityDirty=false;
            visibleKeys.clear();boolean visibilityChanged=false,newVisibleWork=false;boolean everything=context.frustum()==null;
            if(!everything){cullMatrix.set(context.projectionMatrix()).mul(viewMatrix);cull.set(cullMatrix,false);}
            double cx=camera.x,cy=camera.y,cz=camera.z;
            // Whole arenas first: most are entirely outside or inside, so only boundary arenas test their sections.
            for(int a=0;a<arenaCull.length;a++){
                if(everything){arenaCull[a]=INSIDE;continue;}int i=a*6;
                int result=cull.intersectAab((float)(arenaBoxes[i]-cx)-CULL_MARGIN,(float)(arenaBoxes[i+1]-cy)-CULL_MARGIN,(float)(arenaBoxes[i+2]-cz)-CULL_MARGIN,(float)(arenaBoxes[i+3]-cx)+CULL_MARGIN,(float)(arenaBoxes[i+4]-cy)+CULL_MARGIN,(float)(arenaBoxes[i+5]-cz)+CULL_MARGIN);
                arenaCull[a]=result==org.joml.FrustumIntersection.INSIDE?INSIDE:result==org.joml.FrustumIntersection.INTERSECT?PARTIAL:OUTSIDE;
            }
            for(int k=0,n=candidates.size();k<n;k++){
                byte state=arenaCull[candidateArena[k]];int i=k*6;
                boolean inView=state==INSIDE||state==PARTIAL&&cull.testAab((float)(candidateBoxes[i]-cx)-CULL_MARGIN,(float)(candidateBoxes[i+1]-cy)-CULL_MARGIN,(float)(candidateBoxes[i+2]-cz)-CULL_MARGIN,(float)(candidateBoxes[i+3]-cx)+CULL_MARGIN,(float)(candidateBoxes[i+4]-cy)+CULL_MARGIN,(float)(candidateBoxes[i+5]-cz)+CULL_MARGIN);
                if(inView)visibleKeys.add(candidates.get(k));
                if(inView==candidateVisible[k])continue;
                candidateVisible[k]=inView;var key=candidates.get(k);if(inView){visibleSet.add(key);newVisibleWork|=needsMesh(key);}else visibleSet.remove(key);visibilityChanged=true;
                // Camera movement normally changes only a few sections. A membership
                // refresh or resume still updates every resident mesh, including cold ones.
                if(lanes&&!refreshAllPriorities){var mesh=meshes.get(key);if(mesh!=null)mesh.resources().priority(inView?RenderResources.VISIBLE:RenderResources.NEARBY);}
            }
            if(visibilityChanged){
                drawRevision++;budgetChanged=true;
                // Camera motion alone does not create work for an already resident, current mesh.
                if(newVisibleWork||!deferred.isEmpty()||budgetSaturated)nextWorkProbe=0;
                deferred.clear();budgetSaturated=false;
            }
            lastCullCamera=camera;lastCullView.set(viewMatrix);lastCullProjection.set(context.projectionMatrix());
            }
            if(budgetChanged){waitingRevision=-1;if(job!=null&&!visibleSet.contains(job.key)&&!resources.warm())discardJob();}
            if(refreshAllPriorities)meshes.forEach((key,mesh)->mesh.resources().priority(visibleSet.contains(key)?RenderResources.VISIBLE:candidateSet.contains(key)?RenderResources.NEARBY:RenderResources.COLD));
            refreshResidency();
            entities.prepare(context,entityCandidates,true);
        }catch(RuntimeException e){frameReady=false;failure="Renderer paused: "+e;visible=false;discardJob();discardMeshing();BetterLitematicaClient.LOGGER.error("Projection visibility failed",e);}
    }
    @Override public RenderScheduler.Work buildFrame(long deadline,int uploadLimit){
        long started=System.nanoTime();
        try{return buildFrameTimed(deadline,uploadLimit);}finally{phaseTimes.add(1,System.nanoTime()-started);}
    }
    private RenderScheduler.Work buildFrameTimed(long deadline,int uploadLimit){
        if(!frameReady||System.nanoTime()>=deadline||uploadLimit<=0)return RenderScheduler.Work.NONE;
        long started=System.nanoTime();frameUploadBytes=0;frameUploadLimit=uploadLimit;boolean progressed=false;
        try{
            // Decoded sections wait in a short bounded queue; collect them before every attempt, not once per frame,
            // so decoding threads never stall behind it while this frame still has budget.
            stream.drain();if(decodedRevision!=stream.revision()){decodedRevision=stream.revision();nextWorkProbe=0;}
            // Off-thread builds that arrived since the last attempt are uploaded first.
            if(finishMeshes(deadline))progressed=true;expireMeshing();
            long workNow=System.nanoTime();boolean schedule=(job!=null||workNow>=nextWorkProbe)&&waitingRevision!=resources.revision();
            boolean missingWork=job!=null||stream.queuedJobs()>0||!meshing.isEmpty();
            if(schedule&&!prefetchedThisFrame){
            // A mesh needs its six neighboring source sections too. Queue a bounded
            // working set of complete neighborhoods before filling the queue with more
            // centers; otherwise their dependencies sit behind unrelated requests.
            // Once per frame: later attempts in the same frame only build what has arrived.
            prefetchedThisFrame=true;
            frameMissingWork=prefetch(visibleKeys,buildCursor,deadline,false);
            boolean warm=surroundingAllowed();
            if(warm&&stream.queuedJobs()<16)frameMissingWork|=prefetch(candidates,surroundingCursor,deadline,true);
            }
            if(schedule)missingWork|=frameMissingWork;
            boolean warm=surroundingAllowed();
            if(schedule&&!missingWork)nextWorkProbe=Long.MAX_VALUE;
            if(schedule&&missingWork){
                nextWorkProbe=workNow+50_000_000L;
                // Keep the off-thread builders busy; starting a build costs no upload budget. Visible holes first,
                // refreshing a section that is already drawn can wait. Only a section that needs the scene comes back
                // as a render-thread job.
                if(job==null&&!budgetSaturated&&!holesExhausted){job=findJob(visibleKeys,deadline,false,true);if(job==null)holesExhausted=true;}
                if(job==null&&!budgetSaturated&&!visibleRefreshExhausted)job=findJob(visibleKeys,deadline,false,false);
                if(job==null&&warm&&!surroundingExhausted)job=findJob(candidates,deadline,true,false);
                if(job!=null&&System.nanoTime()<deadline&&frameUploadBytes<frameUploadLimit){
                    int cursorBefore=job.cursor,partsBefore=job.parts.size();
                    build(deadline);
                    if(job!=null){
                        if(job.cursor!=cursorBefore||job.parts.size()!=partsBefore){job.stalledSince=0;progressed=true;}
                        else if(waitingRevision!=resources.revision()){
                            if(job.stalledSince==0)job.stalledSince=System.nanoTime();
                            else if(System.nanoTime()-job.stalledSince>200_000_000L){coolingKey=job.key;coolingUntil=System.nanoTime()+100_000_000L;discardJob();}
                        }
                        if(job!=null&&job.vertices>=4096)flushPart();
                    }else{progressed=true;nextWorkProbe=0;}
                }
            }
            if(job==null&&frameUploadBytes<frameUploadLimit)frameUploadBytes+=entities.build(entityCandidates,this,scene,deadline,frameUploadLimit-frameUploadBytes);
            // Every uploaded part is charged in full; unfinished sections resume next frame.
            return new RenderScheduler.Work(frameUploadBytes,progressed||frameUploadBytes>0);
        }catch(RuntimeException e){failure="Renderer paused: "+e;visible=false;frameReady=false;discardJob();discardMeshing();BetterLitematicaClient.LOGGER.error("Projection renderer failed",e);return new RenderScheduler.Work(frameUploadBytes,false);}
        finally{lastBuildNanos+=System.nanoTime()-started;}
    }
    void drawFrame(WorldRenderContext context){
        if(!frameReady)return;long started=System.nanoTime();
        try{drawFrameTimed(context);}finally{phaseTimes.add(2,System.nanoTime()-started);}
    }
    private void drawFrameTimed(WorldRenderContext context){
        try{renderMeshes(context,context.camera().getPos(),visibleKeys);entities.render(context,entityCandidates,opacity);}
        catch(RuntimeException e){failure="Renderer paused: "+e;visible=false;frameReady=false;discardJob();discardMeshing();BetterLitematicaClient.LOGGER.error("Projection draw failed",e);}
    }
    // ---- Off-thread section builds -------------------------------------------------------------------------------
    /** Render-thread record of one build in flight; world events that arrive meanwhile are replayed when it finishes. */
    private static final class MeshTask {
        final SectionMesher.Input input;final long worldRevision,layerRevision;BoundedTaskPool.Ticket<SectionMesher.Output> ticket;final ProjectionPages.Origin arena;final BitSet changed=new BitSet();boolean unloaded;
        MeshTask(SectionMesher.Input input,long worldRevision,long layerRevision,ProjectionPages.Origin arena){this.input=input;this.worldRevision=worldRevision;this.layerRevision=layerRevision;this.arena=arena;}
    }
    /** An output whose GPU upload or block entity layers continue over several frames. */
    private static final class Finishing {
        final SectionMesher.Output out;final MeshTask task;RenderResources.Group group;final ArrayList<Part> parts=new ArrayList<>();long bytes,visibilityBytes,waitingSince;int entity,part;boolean uploaded;
        List<ProjectionScene.Part> overlaps;ProjectionBlockView view;
        Finishing(SectionMesher.Output out,MeshTask task){this.out=out;this.task=task;}
    }
    private static final int STARTED=0,WAITING=1,RENDER_THREAD=2,DONE=0,WAIT=1,DROPPED=2,NEXT=3;
    private final Map<SectionKey,MeshTask> meshing=new LinkedHashMap<>();
    private volatile long meshGeneration;private volatile boolean closed;
    private long layerRevision,meshedSections,meshNanos,drawRevision,cachedDrawRevision=-1,commandRebuilds;private Finishing finishing;
    private final Map<PlacementTransform,SectionMesher.Palette> palettes=new HashMap<>();
    private final BufferBuilder specialBuilder=new BufferBuilder(4096);
    private net.minecraft.client.world.ClientWorld meshWorld;private SectionMesher.World meshWorldView;
    private static final int MAX_MESHING=SectionMesher.MAX_IN_FLIGHT;
    private final SectionMesher.Geometry geometry=new SectionMesher.Geometry(){
        @Override public BakedModel model(BlockState state){return client.getBlockRenderManager().getModel(state);}
        @Override public BakedModel marker(){return client.getBlockRenderManager().getModel(StateResolver1201.marker());}
        @Override public void fluid(BlockPos pos,net.minecraft.world.BlockRenderView view,VertexConsumer out,BlockState state){client.getBlockRenderManager().renderFluid(pos,view,out,state,state.getFluidState());}
        @Override public int tint(BlockState state,int index){return client.getBlockColors().getColor(state,null,null,index);}
    };
    private SectionMesher.World meshWorld(net.minecraft.client.world.ClientWorld world){
        if(world!=meshWorld){meshWorld=world;meshWorldView=new SectionMesher.World(){
            @Override public boolean loaded(int x,int z){return world.getChunkManager().isChunkLoaded(x,z);}
            @Override public BlockState state(BlockPos pos){return world.getBlockState(pos);}
            @Override public int color(BlockPos pos,net.minecraft.world.biome.ColorResolver resolver){return world.getColor(pos,resolver);}
            @Override public float brightness(Direction direction,boolean shaded){return world.getBrightness(direction,shaded);}
            @Override public int height(){return world.getHeight();}
            @Override public int bottom(){return world.getBottomY();}
            @Override public net.minecraft.world.chunk.light.LightingProvider lighting(){return world.getLightingProvider();}
        };}
        return meshWorldView;
    }
    private SectionMesher.Palette palette(int region){
        var t=layout.part(region).transform();var linear=new PlacementTransform(Vec3i.ZERO,t.quarterTurns(),t.mirrorX(),t.mirrorZ());
        return palettes.computeIfAbsent(linear,k->{var result=new SectionMesher.Palette(metadata().palette().size());var state=StateResolver1201.marker();
            result.marker=FrozenModel.capture(geometry.marker(),state,index->safeTint(state,index));return result;});
    }
    private int safeTint(BlockState state,int index){try{return geometry.tint(state,index);}catch(RuntimeException ignored){return 0xffffff;}}
    /** Preparation is incremental and budgeted. No worker reads Minecraft's mutable model caches. */
    private boolean prepare(SectionMesher.Palette palette,StateResolver1201 resolver,PackedSection section,long deadline){
        if(section==null)return true;
        for(int i=0;i<section.paletteSize();i++){int id=section.paletteId(i);if((palette.flags[id]&SectionMesher.READY)==0){if(System.nanoTime()>=deadline)return false;flag(palette,resolver,id);}}
        return true;
    }
    private void flag(SectionMesher.Palette palette,StateResolver1201 resolver,int id){
        BlockState state=resolver.resolve(id);short flags=SectionMesher.flags(metadata().palette().get(id),state,displays(id),resolver.unresolvedState(id));
        if((flags&SectionMesher.MODEL)!=0&&(flags&SectionMesher.SKIP)==0)try{palette.models[id]=FrozenModel.capture(geometry.model(state),state,index->safeTint(state,index));}
        catch(RuntimeException ignored){/* Unknown/custom models use the render-thread builder. */}
        palette.states[id]=state;palette.flags[id]=flags;
    }
    private void lightSprites(){
        for(int level=0;level<16;level++)if(lightSprites[level]==null)lightSprites[level]=client.getItemRenderer().getModel(LightProjectionMarker.stack(Blocks.LIGHT.getDefaultState().with(net.minecraft.state.property.Properties.LEVEL_15,level)),null,null,0).getParticleSprite();
    }
    /**
     * Starts an off-thread build once the section and every neighbour it reads are decoded: the six faces, or all
     * twenty-six around fluids, whose surfaces sample diagonal heights. Overlapping placements need the scene and
     * stay on the render-thread builder.
     */
    private int startMeshing(SectionKey key,PackedSection center,long deadline){
        if(client.world==null)return WAITING;
        var box=bounds(key);if(scene.overlapping(new PlacementBounds(new Vec3i((int)box.minX-1,(int)box.minY-1,(int)box.minZ-1),new Vec3i((int)box.maxX,(int)box.maxY,(int)box.maxZ))).size()>1)return RENDER_THREAD;
        var palette=palette(key.region());var resolver=resolver(key.region());
        if((palette.flags[0]&SectionMesher.READY)==0)flag(palette,resolver,0);if(!prepare(palette,resolver,center,deadline))return WAITING;
        boolean fluid=false,light=false;for(int i=0;i<center.paletteSize();i++){int id=center.paletteId(i);short f=palette.flags[id];
            if((f&SectionMesher.MODEL)!=0&&(f&SectionMesher.SKIP)==0&&(palette.models[id]==null||palette.marker==null))return RENDER_THREAD;
            fluid|=(f&SectionMesher.FLUID)!=0;light|=(f&SectionMesher.LIGHT)!=0;}
        var sections=new PackedSection[27];boolean ready=true;
        for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            int at=(dx+1)+(dz+1)*3+(dy+1)*9;if(at==13){sections[at]=center;continue;}
            if(!fluid&&Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)continue;
            int x=key.x()+dx,y=key.y()+dy,z=key.z()+dz;if(x<0||y<0||z<0)continue;
            var data=section(new SectionKey(key.region(),x,y,z));if(data==null)ready=false;else{sections[at]=data;ready&=prepare(palette,resolver,data,deadline);}
        }
        if(!ready)return WAITING;
        if(light)lightSprites();
        var transform=layout.part(key.region()).transform();var localBase=localBase(key);var worldBase=transform.apply(localBase);var arena=ProjectionPages.origin(worldBase.x(),worldBase.y(),worldBase.z());
        var input=new SectionMesher.Input(key,meshGeneration,sections,palette,transform,localBase,worldBase,worldBase.x()-arena.x(),worldBase.y()-arena.y(),worldBase.z()-arena.z(),
            layer,capAxis,capLo,capHi,meshWorld(client.world),geometry,lightSprites);
        var task=new MeshTask(input,worldRefresh.revision(key),layerRevision,arena);
        task.ticket=SectionMesher.submit(input,generation->generation!=meshGeneration||closed);
        if(task.ticket==null)return WAITING;meshing.put(key,task);return STARTED;
    }
    private void expireMeshing(){
        long now=System.nanoTime();
        for(var task:meshing.values())if(!task.ticket.done()&&!task.ticket.cancelled()&&task.ticket.started()!=0&&now-task.ticket.started()>10_000_000_000L){
            task.ticket.close();failed.add(task.input.key);failure="区段模型生成超时";
        }
    }
    private void discardMeshing(){meshGeneration++;abortFinishing();for(var task:meshing.values())task.ticket.close();meshing.clear();}
    private void discardMeshing(SectionKey key){var task=meshing.remove(key);if(task==null)return;if(finishing!=null&&finishing.task==task)abortFinishing();task.ticket.close();}
    private void abortFinishing(){var current=finishing;finishing=null;if(current==null)return;current.out.free();current.task.ticket.close();if(current.group!=null)current.group.close();}
    /** Completed outputs retain a global slot until consumed, including while waiting for GPU space. */
    private Finishing pollMeshed(){
        var iterator=meshing.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();var task=entry.getValue();var ticket=task.ticket;if(!ticket.done())continue;
            var out=ticket.take();
            if(out==null||out.cancelled||out.failed){
                if(out!=null){if(out.failed){failed.add(entry.getKey());failure="区段模型生成失败";}out.free();}
                if(ticket.failure()!=null){failed.add(entry.getKey());failure="区段模型生成失败";BetterLitematicaClient.LOGGER.warn("Projection mesh task failed",ticket.failure());}
                ticket.close();iterator.remove();continue;
            }
            meshNanos+=out.nanos;meshedSections++;
            var resolver=resolver(entry.getKey().region());for(int id=out.unsupported.nextSetBit(0);id>=0;id=out.unsupported.nextSetBit(id+1))resolver.markUnsupported(id);
            if(!out.failure.isEmpty())failure=out.failure;return new Finishing(out,task);
        }
        return null;
    }
    /** Consume ready builds incrementally; every upload part must fit the remaining frame budget. */
    private boolean finishMeshes(long deadline){
        boolean progressed=false;int finished=0;
        while(System.nanoTime()<deadline&&(finished==0||frameUploadBytes<frameUploadLimit)){
            if(finishing==null){finishing=pollMeshed();if(finishing==null)break;}
            int entityBefore=finishing.entity,partBefore=finishing.part;
            int result=finish(finishing,deadline);
            if(result==WAIT){
                // A section that cannot finish (no room on screen, a neighbour that never decodes) must not hold up the rest.
                long now=System.nanoTime();if(finishing.entity!=entityBefore||finishing.part!=partBefore)finishing.waitingSince=0;
                else if(finishing.waitingSince==0)finishing.waitingSince=now;
                else if(now-finishing.waitingSince>500_000_000L){deferred.add(finishing.task.input.key);result=DROPPED;}
                if(result==WAIT)break;
            }
            var done=finishing;finishing=null;meshing.remove(done.task.input.key);
            // Meshes own immutable cell snapshots; return staging buffers on success as well as cancellation.
            done.out.free();done.task.ticket.close();progressed=true;finished++;nextWorkProbe=0;
            if(result==DROPPED&&done.group!=null)done.group.close();
        }
        return progressed;
    }
    private int finish(Finishing f,long deadline){
        var key=f.task.input.key;boolean onscreen=visibleSet.contains(key);int demand=onscreen?RenderResources.VISIBLE:RenderResources.NEARBY;
        if(!f.uploaded){
            if(f.group==null){double distance=sectionDistance(key);f.group=meshes.contains(key)?resources.beginReplacement(demand,distance):resources.begin(demand,distance);if(f.group==null){waitingRevision=resources.revision();return WAIT;}}
            while(f.part<f.out.parts.size()){
                var part=f.out.parts.get(f.part);int bytes=part.vertices*SectionMesher.STRIDE;
                if(System.nanoTime()>=deadline||bytes>frameUploadLimit-frameUploadBytes)return WAIT;
                ProjectionPages.Slice slice;
                try{slice=pages.allocate(f.task.arena,part.vertices,f.group,demand);}catch(IllegalArgumentException e){failed.add(key);failure="区段模型超过资源预算";return DROPPED;}
                if(slice==null){if(!onscreen){deferred.add(key);return DROPPED;}waitingRevision=resources.revision();return WAIT;}
                pages.upload(slice,org.lwjgl.system.MemoryUtil.memByteBuffer(part.address(),bytes));
                var visibility=new QuadVisibility.Part(part.owners);f.parts.add(new Part(null,slice,bytes,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false,visibility));
                f.visibilityBytes+=visibility.memoryEstimate();f.bytes+=bytes;frameUploadBytes+=bytes;part.free();f.part++;
            }
            f.uploaded=true;
        }
        // Block entity layers need the render thread, one cell at a time within the frame budget.
        while(f.entity<f.out.entities){
            if(System.nanoTime()>=deadline)return WAIT;
            int result=entityLayers(f,f.out.entityCells[f.entity],demand,onscreen);if(result==WAIT||result==DROPPED)return result;f.entity++;
        }
        // Replay block changes that arrived while the section was being built, exactly as worldBlockChanged would.
        var in=f.task.input;boolean stale=f.task.layerRevision!=layerRevision;var cells=f.out.cells.build();
        for(int cell=f.task.changed.nextSetBit(0);cell>=0;cell=f.task.changed.nextSetBit(cell+1)){
            int expected=cells.state(cell);if(expected<0)continue;var pos=cellPosition(in,cell);boolean loaded=client.world!=null&&WorldChunks.loaded(client.world,pos);
            var actual=loaded?client.world.getBlockState(pos):null;var state=Block.getStateFromRawId(expected);boolean complete=completedBlock(loaded,actual,state);
            f.out.mask.set(cell,complete);f.out.wrong.set(cell,wrongBlock(loaded,actual,state));
            if(f.out.omitted.get(cell)&&!complete)stale=true;
        }
        if(f.task.unloaded)f.out.wrong.clear();
        var mesh=new Mesh(List.copyOf(f.parts),Math.max(128,f.bytes+f.visibilityBytes+cells.estimatedBytes()+1792),bounds(key),f.group,f.task.worldRevision,f.out.mask,f.out.wrong,cells,f.out.omitted);
        if(admit(key,mesh)&&stale){worldRefresh.changed(key);wakeWorldRefresh();}
        builtSections++;return DONE;
    }
    private static BlockPos cellPosition(SectionMesher.Input in,int cell){
        int lx=in.localBase.x()+(cell&15),ly=in.localBase.y()+(cell>>>8),lz=in.localBase.z()+((cell>>>4)&15);
        return new BlockPos(in.ox+lx*in.exx+lz*in.ezx,in.oy+ly,in.oz+lx*in.exz+lz*in.ezz);
    }
    /** Captures one block entity renderer into its own textured parts, owned by that cell. */
    private int entityLayers(Finishing f,int cell,int demand,boolean onscreen){
        var in=f.task.input;var key=in.key;var pos=cellPosition(in,cell);int id=in.sections[13].globalId(cell);var state=in.palette.states[id];
        if(f.overlaps==null){var box=bounds(key);f.overlaps=scene.overlapping(new PlacementBounds(new Vec3i((int)box.minX-1,(int)box.minY-1,(int)box.minZ-1),new Vec3i((int)box.maxX,(int)box.maxY,(int)box.maxZ)));f.view=new ProjectionBlockView(client.world,scene,f.overlaps,in.layer);}
        specialModels.clear();
        try{
            if(projectionWorld==null)projectionWorld=new ProjectionWorld(client.world);var overlaps=f.overlaps;projectionWorld.view(f.view,at->blockEntity(overlaps,at));
            var entity=blockEntity(overlaps,pos);
            if(entity!=null){var renderer=client.getBlockEntityRenderDispatcher().get(entity);if(renderer!=null)renderer.render(entity,0,new net.minecraft.client.util.math.MatrixStack(),specialModels,LightmapTextureManager.MAX_LIGHT_COORDINATE,OverlayTexture.DEFAULT_UV);else if((in.palette.flags[id]&SectionMesher.MODEL)==0)resolver(key.region()).markUnsupported(id);}
        }catch(ProjectionBlockView.Pending waiting){return WAIT;}
        catch(RuntimeException e){resolver(key.region()).markUnsupported(id);failure="Unsupported model: "+state+" (block omitted)";return NEXT;}
        if(specialModels.vertices()>MAX_PART_VERTICES){resolver(key.region()).markUnsupported(id);failure="特殊模型顶点超出预算";return NEXT;}
        if(specialModels.vertices()==0)return NEXT;
        if(frameUploadBytes+specialModels.vertices()*24>frameUploadLimit)return WAIT;
        var layers=specialModels.layers().entrySet().stream().filter(e->e.getValue().size()>0).toList();int[] sizes=new int[layers.size()];
        for(int i=0;i<sizes.length;i++)sizes[i]=layers.get(i).getValue().size()*24;
        List<RenderResources.Lease> leases;
        try{leases=f.group.reserve(sizes,demand);}catch(IllegalArgumentException e){failed.add(key);failure="区段模型超过资源预算";return DROPPED;}
        if(leases==null){if(!onscreen){deferred.add(key);return DROPPED;}waitingRevision=resources.revision();return WAIT;}
        int rx=pos.getX()-in.worldBase.x(),ry=pos.getY()-in.worldBase.y(),rz=pos.getZ()-in.worldBase.z();
        for(int i=0;i<sizes.length;i++){
            var layer=layers.get(i);specialBuilder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);layer.getValue().emit(specialBuilder,rx,ry,rz);
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);leases.get(i).attach(buffer::close);var built=specialBuilder.end();try{buffer.bind();buffer.upload(built);}finally{VertexBuffer.unbind();}
            short[] owners=new short[sizes[i]/96];Arrays.fill(owners,(short)cell);var visibility=new QuadVisibility.Part(owners);
            f.parts.add(new Part(buffer,null,sizes[i],layer.getKey().texture(),layer.getKey().intensity(),visibility));f.visibilityBytes+=visibility.memoryEstimate();f.bytes+=sizes[i];frameUploadBytes+=sizes[i];
        }
        if((in.palette.flags[id]&SectionMesher.INVALID)==0)f.out.cells.record(cell,Block.getRawIdFromState(state));
        return NEXT;
    }
    /**
     * Starts off-thread builds for the next sections in {@code keys} until the workers are busy, and returns a
     * render-thread job only for a section whose neighbours belong to another placement. Holes start from the nearest.
     */
    private Job findJob(List<SectionKey> keys,long deadline,boolean surrounding,boolean holesOnly){
        int start=holesOnly?0:Math.floorMod(surrounding?surroundingCursor:buildCursor,Math.max(1,keys.size()));
        for(int visited=0;visited<keys.size();visited++){
            int at=(start+visited)%keys.size();SectionKey key=keys.get(at);
            // Busy workers end the scan; the rare section that needs the render-thread builder waits for them too.
            if(System.nanoTime()>=deadline||meshing.size()>=MAX_MESHING||!SectionMesher.hasCapacity())return null;
            if(surrounding&&visibleSet.contains(key)||key.equals(coolingKey)&&System.nanoTime()<coolingUntil)continue;
            if(meshing.containsKey(key)||holesOnly&&meshes.contains(key)||!needsMesh(key)||failed.contains(key)||deferred.contains(key))continue;
            // Under a full budget a section farther than every resident mesh would only be rejected: skip it.
            if(!resources.admits(sectionDistance(key)))continue;
            var section=section(key);if(section==null)continue;
            int started=startMeshing(key,section,deadline);
            if(started==STARTED){if(!holesOnly){if(surrounding)surroundingCursor=at+1;else buildCursor=at+1;}continue;}
            if(started==WAITING||job!=null)continue;
            Job next=prepare(key,section);if(next!=null){if(surrounding)surroundingCursor=at+1;else buildCursor=at+1;return next;}
        }
        // A completed scan found every eligible key. Do not repeat it for each scheduler attempt in this frame.
        // Early exits for a deadline or busy workers deliberately leave the scan retryable.
        if(holesOnly)holesExhausted=true;else if(surrounding)surroundingExhausted=true;else visibleRefreshExhausted=true;
        return null;
    }
    private boolean prefetch(List<SectionKey> keys,int cursor,long deadline,boolean surrounding){
        boolean missing=false;int neighborhoods=0,start=Math.floorMod(cursor,Math.max(1,keys.size()));
        for(int visited=0;visited<keys.size()&&neighborhoods<(surrounding?2:8);visited++){
            if(System.nanoTime()>=deadline||stream.queuedJobs()>=PREFETCH_IN_FLIGHT)return true;
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
                emitLight(state,world.x()-current.worldBase.x(),world.y()-current.worldBase.y(),world.z()-current.worldBase.z());ownership.append(i,4);
                current.vertices+=4;current.cursor++;processed++;continue;
            }
            try{
                if(!state.getFluidState().isEmpty())client.getBlockRenderManager().renderFluid(new BlockPos(world.x(),world.y(),world.z()),current.view,fluidVertices,state,state.getFluidState());
                if(state.getRenderType()==BlockRenderType.INVISIBLE){
                    if(fluidVertices.size()>0){if(current.vertices+fluidVertices.size()>MAX_PART_VERTICES&&!flushPart())return;if(!builder.isBuilding())builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);fluidVertices.emit(builder,world.x()-current.worldBase.x()-(world.x()&15),world.y()-current.worldBase.y()-(world.y()&15),world.z()-current.worldBase.z()-(world.z()&15));ownership.append(i,fluidVertices.size());current.vertices+=fluidVertices.size();}
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
            Vec3i relative=world.subtract(current.worldBase);int ax=relative.x(),ay=relative.y(),az=relative.z();
            if(specialModels.vertices()>0){
                // Reserve the entire block before emitting it: a budget wait must never
                // replay an already uploaded regular face or a subset of special layers.
                if(!flushPart())return;
                var layers=specialModels.layers().entrySet().stream().filter(e->e.getValue().size()>0).toList();
                int regular=(quads.size()*4+fluidVertices.size())*24,offset=regular>0?1:0;
                int[] sizes=new int[layers.size()+offset];if(offset==1)sizes[0]=regular;
                for(int partIndex=0;partIndex<layers.size();partIndex++)sizes[partIndex+offset]=layers.get(partIndex).getValue().size()*24;
                var leases=reserve(sizes);if(leases==null)return;
                if(regular>0){builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);for(BakedQuad quad:quads)emit(quad,state,ax,ay,az);fluidVertices.emit(builder,ax-(world.x()&15),ay-(world.y()&15),az-(world.z()&15));ownership.append(i,regular/24);upload(leases.get(0),regular,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false);}
                for(int partIndex=0;partIndex<layers.size();partIndex++){var special=layers.get(partIndex);builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);special.getValue().emit(builder,relative.x(),relative.y(),relative.z());ownership.append(i,special.getValue().size());upload(leases.get(partIndex+offset),sizes[partIndex+offset],special.getKey().texture(),special.getKey().intensity());}
            }else{
                if(current.vertices+quads.size()*4+fluidVertices.size()>MAX_PART_VERTICES&&!flushPart())return;
                if(!builder.isBuilding())builder.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);
                for(BakedQuad quad:quads)emit(quad,state,ax,ay,az);fluidVertices.emit(builder,ax-(world.x()&15),ay-(world.y()&15),az-(world.z()&15));
                ownership.append(i,quads.size()*4+fluidVertices.size());current.vertices+=quads.size()*4+fluidVertices.size();
            }
            current.cursor++;processed++;
        }
        if(job!=null&&job.cursor==4096&&flushPart()){
            var cells=job.cells.build();
            admit(job.key,new Mesh(List.copyOf(job.parts),Math.max(128,job.bytes+job.visibilityBytes+cells.estimatedBytes()+1792),bounds(job.key),job.resources,job.worldRevision,job.mask,job.wrong,cells,job.omitted));
            builtSections++;job=null;
        }
    }
    /** Admission is established before detaching the old complete value; replacement never leaves a frame without either. */
    private boolean admit(SectionKey key,Mesh mesh){
        boolean onscreen=visibleSet.contains(key);
        var old=meshes.get(key);boolean room=old==null||(onscreen?meshes.canReplaceProtecting(key,mesh.bytes(),visibleSet):meshes.canReplaceWithoutEviction(key,mesh.bytes()));
        // Replacement is synchronous on the render thread; no frame can see the cache between remove/put.
        if(old!=null&&room)meshes.remove(key);
        boolean admitted=room&&(onscreen?meshes.tryPutProtecting(key,mesh,visibleSet):meshes.tryPutWithoutEviction(key,mesh));
        if(!admitted){mesh.close();deferred.add(key);if(onscreen)budgetSaturated=true;return false;}
        if(onscreen)drawRevision++;
        worldRefresh.completed(key,mesh.worldRevision());mesh.resources().finish(onscreen?RenderResources.VISIBLE:RenderResources.NEARBY,()->{if(visibleSet.contains(key))drawRevision++;meshes.remove(key);deferred.clear();budgetSaturated=false;nextWorkProbe=0;});
        return true;
    }
    private net.minecraft.block.entity.BlockEntity blockEntity(Job current,BlockPos pos){return blockEntity(current.overlaps,pos);}
    private net.minecraft.block.entity.BlockEntity blockEntity(List<ProjectionScene.Part> overlaps,BlockPos pos){
        var cached=blockEntities.get(pos);if(cached!=null)return cached;var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());var cell=scene.sampleDisplayed(overlaps,at);if(cell==null)return null;if(cell.unknown())throw new ProjectionBlockView.Pending();var owner=cell.renderer();var state=owner.resolve(cell.region().index(),cell.id());if(!state.hasBlockEntity())return null;
        net.minecraft.block.entity.BlockEntity entity=null;
        if(owner.details!=null&&(owner.edits==null||owner.edits.snapshot().patch(cell.region().section(cell.local()),cell.region().cell(cell.local()))==null||owner.edits.snapshot().patch(cell.region().section(cell.local()),cell.region().cell(cell.local())).blockData())){var data=owner.details.parts().get(cell.region().index()).blocks().get(cell.local());if(data!=null){var tag=(net.minecraft.nbt.NbtCompound)NbtBridge.game(data);BlockEntityNbtTransform.placed(tag,cell.region().transform());tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());entity=net.minecraft.block.entity.BlockEntity.createFromNbt(pos,state,tag);}}
        if(entity==null&&state.getBlock() instanceof BlockEntityProvider provider)entity=provider.createBlockEntity(pos,state);
        if(entity!=null){entity.setWorld(projectionWorld);if(blockEntities.size()>=256)blockEntities.remove(blockEntities.keySet().iterator().next());blockEntities.put(pos.toImmutable(),entity);}return entity;
    }
    private void append(List<BakedQuad> list){if(list.size()>MAX_BLOCK_QUADS-quads.size())throw new IllegalArgumentException("Model exceeds 512 quads per block");quads.addAll(list);}
    private Job prepare(SectionKey key,PackedSection section){
        Job next=new Job(key,section,localBase(key));var old=meshes.get(key);next.mask=old==null?new QuadVisibility.Mask():old.mask;next.wrong=old==null?new QuadVisibility.Mask():old.wrong;next.worldRevision=worldRefresh.revision(key);next.transform=layout.part(key.region()).transform();next.worldBase=next.transform.apply(next.localBase);
        next.states=resolver(key.region());next.offsets=new Vec3i[6];
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
        if(!ready)return null;int demand=visibleSet.contains(key)?RenderResources.VISIBLE:RenderResources.NEARBY;double distance=sectionDistance(key);next.resources=meshes.contains(key)?resources.beginReplacement(demand,distance):resources.begin(demand,distance);if(next.resources==null)waitingRevision=resources.revision();return next.resources==null?null:next;
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
        // Inside a followed band every cut must be capped, so faces along the layer axis are never culled there.
        if(capAxis>=0&&side.getAxis().ordinal()==capAxis){int c=capAxis==0?world.x():capAxis==1?world.y():world.z();if(c>=capLo&&c<=capHi)return false;}
        if(layer.mode()!=LayerRange.Mode.ALL||current.overlaps.size()>1){neighborPosition=world.add(new Vec3i(side.getOffsetX(),side.getOffsetY(),side.getOffsetZ()));if(!layer.contains(neighborPosition))return false;}
        BlockState neighbor;
        if(current.overlaps.size()>1){var cell=scene.sampleDisplayed(current.overlaps,neighborPosition);if(cell==null||cell.unknown())return false;neighbor=cell.renderer().resolve(cell.region().index(),cell.id());}
        else {Vec3i delta=current.offsets[side.ordinal()];int id=current.neighborhood.globalId(x+delta.x(),y+delta.y(),z+delta.z());if(!displays(id))return false;neighbor=current.states.resolve(id);}
        if(neighbor.isAir()||neighbor.isOf(Blocks.STRUCTURE_VOID))return false;
        return state.isSideInvisible(neighbor,side)||opaqueStates.computeIfAbsent(neighbor,v->v.isOpaqueFullCube(EmptyBlockView.INSTANCE,BlockPos.ORIGIN));
    }
    private void emitLight(BlockState state,int x,int y,int z){
        int level=state.get(net.minecraft.state.property.Properties.LEVEL_15);
        var sprite=lightSprites[level];
        if(sprite==null){sprite=client.getItemRenderer().getModel(LightProjectionMarker.stack(state),null,null,0).getParticleSprite();lightSprites[level]=sprite;}
        // Four coincident centers; alpha=0 tags a marker and RG encodes view-space corners.
        // The vertex shader expands the quad towards the camera without rebuilding its mesh.
        for(int vertex=0;vertex<4;vertex++){
            boolean right=vertex==1||vertex==2,top=vertex>=2;
            builder.vertex(x+0.5,y+0.5,z+0.5)
                .texture(right?sprite.getMaxU():sprite.getMinU(),top?sprite.getMinV():sprite.getMaxV())
                .color(right?255:0,top?255:0,255,0).next();
        }
    }
    private void emit(BakedQuad quad,BlockState state,int x,int y,int z){
        int[] data=quad.getVertexData();int stride=data.length/4;if(stride<6||data.length%4!=0)throw new IllegalArgumentException("Unknown baked vertex format");
        int tint=0xffffff;
        if(quad.hasColor())try{tint=client.getBlockColors().getColor(state,null,null,quad.getColorIndex());}catch(RuntimeException ignored){/* Context-sensitive tint: neutral fallback in preview. */}
        float shade=quad.getFace()==Direction.UP?1f:quad.getFace()==Direction.DOWN?0.6f:quad.getFace().getAxis()==Direction.Axis.X?0.8f:0.9f;
        for(int vertex=0;vertex<4;vertex++){
            int at=vertex*stride,color=data[at+3];float red=(color&255)/255f*((tint>>>16)&255)/255f*shade,green=((color>>>8)&255)/255f*((tint>>>8)&255)/255f*shade,blue=((color>>>16)&255)/255f*(tint&255)/255f*shade;
            builder.vertex(x+Float.intBitsToFloat(data[at]),y+Float.intBitsToFloat(data[at+1]),z+Float.intBitsToFloat(data[at+2]))
                .texture(Float.intBitsToFloat(data[at+4]),Float.intBitsToFloat(data[at+5])).color(red,green,blue,1f).next();
        }
    }
    private List<RenderResources.Lease> reserve(int[] sizes){
        try{boolean onscreen=visibleSet.contains(job.key);var result=job.resources.reserve(sizes,onscreen?RenderResources.VISIBLE:RenderResources.NEARBY);if(result==null){if(!onscreen){discardJob();waitingRevision=-1;nextWorkProbe=0;}else waitingRevision=resources.revision();}return result;}
        catch(IllegalArgumentException e){failed.add(job.key);failure="区段模型超过资源预算";discardJob();return null;}
    }
    /** The rare overlapping/custom-model path keeps atomic, section-relative private buffers. */
    private void upload(RenderResources.Lease lease,int bytes,net.minecraft.util.Identifier texture,boolean intensity){
        short[] owners=ownership.build(bytes/24);var visibility=new QuadVisibility.Part(owners);
        for(short cell:owners)if(cell>=0){int id=job.section.globalId(cell);var state=job.states.resolve(id);if(!job.states.unresolvedState(id))job.cells.record(cell,Block.getRawIdFromState(state));}
        var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);lease.attach(buffer::close);var built=builder.end();
        try{buffer.bind();buffer.upload(built);}finally{VertexBuffer.unbind();}
        job.parts.add(new Part(buffer,null,bytes,texture,intensity,visibility));job.visibilityBytes+=visibility.memoryEstimate();ownership.clear();job.bytes+=bytes;frameUploadBytes+=bytes;
    }
    private boolean flushPart(){
        if(job.vertices==0)return true;int bytes=job.vertices*24;
        if(frameUploadBytes+bytes>frameUploadLimit)return false;
        var leases=reserve(new int[]{bytes});if(leases==null)return false;
        upload(leases.get(0),bytes,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false);job.vertices=0;return true;
    }
    /** Parts with their own texture (block entity layers) draw one by one after the paged geometry. */
    private record Special(Vec3i base,Part part,QuadVisibility.Mask mask,QuadVisibility.Mask wrong){}
    private final ArrayList<Special> specials=new ArrayList<>();
    private Vec3i worldBase(SectionKey key){return layout.part(key.region()).transform().apply(localBase(key));}
    private void renderMeshes(WorldRenderContext context,Vec3d camera,List<SectionKey> visibleKeys){
        ShaderProgram shader=ProjectionShaders.surface();
        // Values live on the shared program; entity meshes drawn after this bind the same state, even when no section draws.
        shader.getUniformOrDefault("CameraPos").set((float)camera.x,(float)camera.y,(float)camera.z);
        shader.getUniformOrDefault("ViewInverse").set(new Matrix4f(context.matrixStack().peek().getPositionMatrix()).invert());
        shader.getUniformOrDefault("LayerClip").set((float)clipAxis,(float)Math.max(-1e9,clipFrom),(float)Math.min(1e9,clipTo),(float)clipFade);
        shader.getUniformOrDefault("Fade").set(NEAR_CLEAR,NEAR_FULL);
        // Queue before binding anything: a frame without projection geometry leaves the composite target untouched.
        if(job!=null||cachedDrawRevision!=drawRevision){
        pages.beginFrame();specials.clear();commandRebuilds++;
        for(SectionKey key:visibleKeys){
            Mesh mesh=meshes.get(key);List<Part> parts=mesh!=null?mesh.parts:job!=null&&job.key.equals(key)?job.parts:List.of();if(parts.isEmpty())continue;
            var mask=mesh!=null?mesh.mask:job.mask;var wrong=mesh!=null?mesh.wrong:job.wrong;
            for(Part part:parts){
                var slice=part.slice();if(slice==null){specials.add(new Special(worldBase(key),part,mask,wrong));continue;}
                var visibility=part.visibility();
                if(wrong.isEmpty()){
                    if(mask.isEmpty()||visibility.fullyVisible(mask))pages.add(slice,0,slice.vertices/4,false);
                    else for(int i=0,n=visibility.rangeCount(mask);i<n;i++)pages.add(slice,visibility.firstQuad(i),visibility.quadCount(i),false);
                }else{
                    // Read each group right after counting it: the visibility caches one split at a time.
                    for(int i=0,n=visibility.rangeCount(mask,wrong,false);i<n;i++)pages.add(slice,visibility.firstQuad(i),visibility.quadCount(i),false);
                    for(int i=0,n=visibility.rangeCount(mask,wrong,true);i<n;i++)pages.add(slice,visibility.firstQuad(i),visibility.quadCount(i),true);
                }
            }
        }
        cachedDrawRevision=drawRevision;
        }
        if(!pages.queued()&&specials.isEmpty())return;
        ProjectionComposite.activate();
        int previousTexture=RenderSystem.getShaderTexture(0);ShaderProgram previous=RenderSystem.getShader();float[] previousColor=RenderSystem.getShaderColor().clone();
        boolean bound=false;
        try{
            RenderSystem.setShader(ProjectionShaders::surface);RenderSystem.setShaderTexture(0,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            RenderSystem.setShaderColor(1,1,1,opacity);RenderSystem.disableBlend();RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.enablePolygonOffset();RenderSystem.polygonOffset(-1f,-1f);
            // Block models are made for back-face culling, and every cut of "all" or a followed band has real cap faces.
            // Only a fixed cut whose sections are still being rebuilt would show block interiors through it.
            if(clipAxis<0||capAxis>=0)RenderSystem.enableCull();else RenderSystem.disableCull();
            Matrix4f view=context.matrixStack().peek().getPositionMatrix();
            if(shader.modelViewMat!=null)shader.modelViewMat.set(view);
            if(shader.projectionMat!=null)shader.projectionMat.set(context.projectionMatrix());
            if(shader.colorModulator!=null)shader.colorModulator.set(1f,1f,1f,opacity);
            shader.getUniformOrDefault("TextureMode").set(0);shader.getUniformOrDefault("SurfaceTint").set(0);
            shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();bound=true;
            drawCalls+=pages.draw(shader,view,camera.x,camera.y,camera.z,arenaMatrix);
            if(!specials.isEmpty()){
                RenderSystem.disableCull();var mode=shader.getUniform("TextureMode");var tint=shader.getUniform("SurfaceTint");
                for(var special:specials){
                    var part=special.part();var base=special.base();boolean atlas=part.texture().equals(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
                    arenaMatrix.set(view).translate((float)(base.x()-camera.x),(float)(base.y()-camera.y),(float)(base.z()-camera.z));
                    if(shader.modelViewMat!=null){shader.modelViewMat.set(arenaMatrix);shader.modelViewMat.upload();}
                    if(!atlas){RenderSystem.setShaderTexture(0,part.texture());shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();}
                    if(mode!=null){mode.set(part.intensity()?1:0);mode.upload();}
                    part.buffer().bind();var mask=special.mask();var wrong=special.wrong();
                    if(wrong.isEmpty()){
                        if(mask.isEmpty()||part.visibility().fullyVisible(mask)){part.buffer().draw();drawCalls++;}
                        else {var type=part.buffer().getIndexType();if(ProjectionDraw.drawRanges(type.glType,type.size,part.visibility(),mask))drawCalls++;}
                    }else{
                        var type=part.buffer().getIndexType();if(ProjectionDraw.drawRanges(type.glType,type.size,part.visibility(),mask,wrong,false))drawCalls++;
                        if(tint!=null){tint.set(1);tint.upload();}if(ProjectionDraw.drawRanges(type.glType,type.size,part.visibility(),mask,wrong,true))drawCalls++;
                        if(tint!=null){tint.set(0);tint.upload();}
                    }
                    if(!atlas){RenderSystem.setShaderTexture(0,SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();}
                }
                if(mode!=null){mode.set(0);mode.upload();}
            }
        }finally{
            if(bound)shader.unbind();
            VertexBuffer.unbind();RenderSystem.setShaderTexture(0,previousTexture);RenderSystem.disablePolygonOffset();RenderSystem.polygonOffset(0f,0f);RenderSystem.depthMask(true);RenderSystem.enableCull();RenderSystem.disableBlend();
            RenderSystem.setShaderColor(previousColor[0],previousColor[1],previousColor[2],previousColor[3]);if(previous!=null)RenderSystem.setShader(()->previous);
        }
    }
    private void discardJob(){
        if(job!=null)drawRevision++;
        if(builder.isBuilding())builder.end().release();builder.clear();ownership.clear();
        if(job!=null){job.resources.close();job=null;}
    }
    @Override public void close(){closed=true;discardMeshing();queryGeneration++;if(query!=null)query.cancel(false);queryWorker.shutdownNow();discardJob();meshes.close();editedSections.close();entities.close();try{sourceLease.close();}catch(java.io.IOException e){failure=e.toString();}}
}
