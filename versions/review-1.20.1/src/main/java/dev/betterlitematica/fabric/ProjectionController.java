package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.*;
import dev.betterlitematica.io.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Main-thread placement ownership. Disk workers receive snapshots, never a live game world. */
final class ProjectionController implements AutoCloseable {
    private static final class Entry {
        Placement placement; ProjectionRenderer1201 renderer; BlueprintMetadata metadata; long[] counts; FileMaterials materials;
        StateResolver1201 queryStates;
        String state = "Queued";
        Entry(Placement placement) { this.placement = placement; }
        void close() { if (renderer != null) renderer.close(); renderer = null; metadata = null; counts = null; materials=null; queryStates=null; }
    }
    private final MinecraftClient client;
    private final ToolInteractions tool;private final ToolWorldOperations toolWorld;
    private final PrinterEngine printer;private final InventoryTransfers inventoryTransfers;private final BedrockController bedrock;
    private final NearbyProjectionHighlights nearbyHighlights=new NearbyProjectionHighlights();
    private final DraftWriter draftWriter=new DraftWriter();private Path draftFile;private CompletableFuture<DraftStore.Draft> draftReading;private DraftStore.Draft recoveringDraft;private CompletableFuture<SchematicEdits> draftRecovering;private CompletableFuture<Path> draftArchiving;private boolean draftRetrySource;private String draftError="";private long checkpointRevision=-1;private Placement checkpointPlacement;private String checkpointFailure="";
    static final class EditorBaseline {
        final Path path;final NewFileCommit commit=new NewFileCommit();CompletableFuture<String> result;CompletableFuture<Void> cleanup;boolean abandoned,accepted;
        EditorBaseline(Path path){this.path=path;}
        void cancel(){abandoned=true;commit.cancel();}
    }
    private final List<EditorBaseline> editorBaselines=new ArrayList<>();
    private record DraftAssociation(UUID id,ProjectionRenderer1201 previous,BlueprintMetadata metadata,long[] counts,CompletableFuture<Void> stored){}
    private DraftAssociation draftAssociation;
    private final SchematicEditor editor;private final Map<UUID,Placement> draftAdoptions=new HashMap<>();
    private final LoadCoordinator loader = new LoadCoordinator();
    private final SessionIo io = new SessionIo();
    private final Path schematicDirectory, cacheDirectory, settingsDirectory;
    private final TemporarySources temporarySources;
    private final FilePreviews filePreviews;
    private final java.util.concurrent.atomic.AtomicBoolean cleanupWarning=new java.util.concurrent.atomic.AtomicBoolean();
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String,LoadCoordinator.Loaded> resources=new LinkedHashMap<>();
    private final Map<UUID,String> resourceRequests=new LinkedHashMap<>();private final Map<String,String> resourceFailures=new LinkedHashMap<>();
    private final ArrayDeque<UUID> queued = new ArrayDeque<>();
    private UUID selected, importing;
    private ClientWorld lastWorld;
    private Path sessionFile;
    private CompletableFuture<PlacementSession> restoring;
    private boolean ready, writable = true, dirty, rendering = true;
    private boolean temporarilyHidden;
    void temporarilyHidden(boolean value){temporarilyHidden=value;}
    private LayerRange layer = LayerRange.ALL;
    private float opacity = 0.45f;
    private String message = "", cachedHud = "";
    private String actionError = "";
    private int ticks, buildTurn, materialTurn;
    private final ProjectionComposite composite=new ProjectionComposite();
    private final RenderResources gpuResources=new RenderResources(1L<<30,65536,65536,8L<<20,8192,1);
    private boolean gpuReleaseFailure;
    private void releaseGpu(int limit,long deadline){try{gpuResources.drain(limit,deadline);gpuReleaseFailure=false;}catch(RuntimeException e){if(!gpuReleaseFailure)BetterLitematicaClient.LOGGER.error("GPU resource release will retry",e);gpuReleaseFailure=true;}}
    private ProjectionScene scene=new ProjectionScene(List.of());
    private final WorldSectionChanges renderChanges=new WorldSectionChanges(2048);
    private PlacementAnalysis analysis;
    private boolean errorOverlay;
    private final Map<String, CompletableFuture<String>> background = new LinkedHashMap<>();
    private final Map<UUID,CompletableFuture<String>> editingJobs=new HashMap<>();
    private AreaSelection selection = AreaSelection.EMPTY;
    private SelectionTarget selectionTarget;
    private CompletableFuture<AreaSelection> selectionRestoring;
    private Path selectionFile;
    private boolean selectionDirty, selectionWritable = true;
    private final AtomicReference<WorldCapture> serverCapture = new AtomicReference<>();
    private final AtomicLong captureEpoch = new AtomicLong();
    private CompletableFuture<WorldCapture> captureStarting;
    private WorldCapture capture;
    private String captureName;
    private Vec3i captureOrigin;
    private boolean captureWithPreview;private CompletableFuture<String> captureSaving;
    private boolean captureTemporary;private CompletableFuture<String> capturePreparing;private long capturePreparingEpoch;private Vec3i temporaryOrigin;private String temporaryName;
    private final AtomicReference<CreativePasteTask> serverPaste = new AtomicReference<>();
    private final AtomicLong pasteEpoch = new AtomicLong();
    private CompletableFuture<CreativePasteTask> pasteStarting;
    private CreativePasteTask paste;
    private InteractionOptions options = new InteractionOptions();
    private final Path optionsFile;
    private CompletableFuture<InteractionOptions> optionsLoading;
    private boolean optionsWritable = true;
    private final AtomicReference<CreativeFillTask> serverFill=new AtomicReference<>();
    private CompletableFuture<CreativeFillTask> fillStarting;private CreativeFillTask fill;
    private CompletableFuture<SchematicDocument> commandsLoading;private CommandOperation commands;
    private Placement commandPlacement;private LayerRange commandLayer;private ReplaceRule commandRule;private boolean commandNbt,commandEntities;private Path commandOutput;
    record CreativeJob(UUID id,UUID target,String name,boolean commands,boolean export,ReplaceRule rule,boolean entities,boolean nbt,String output,CommandSettings settings){}
    private CreativeJob creativeJob;private String creativeResult="";private CommandSettings commandSettings;private volatile boolean creativePauseRequested;
    private CompletableFuture<PlacementSession> projectLoading;
    private UUID projectReplacement;private long sessionEpoch;
    private record ProjectActivation(UUID id,Placement placement,PlacementSession settings,boolean existing){}
    private ProjectActivation projectActivation;
    private CompletableFuture<int[]> previewCapture;

    ProjectionController(MinecraftClient client) {
        this.client = client;editor=new SchematicEditor(client,this);inventoryTransfers=new InventoryTransfers(client,()->options);bedrock=new BedrockController(client,this);printer=new PrinterEngine(client,this);toolWorld=new ToolWorldOperations(client,this);tool=new ToolInteractions(client,this);
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath();
        schematicDirectory = game.resolve("schematics"); cacheDirectory = game.resolve(".betterlitematica-cache");
        temporarySources=new TemporarySources(cacheDirectory);
        filePreviews=new FilePreviews(cacheDirectory);
        settingsDirectory = game.resolve("config/betterlitematica/placements");
        optionsFile=game.resolve("config/betterlitematica/options.json");optionsLoading=io.submit(()->InteractionOptions.read(optionsFile));
        try { Files.createDirectories(schematicDirectory); } catch (IOException e) { report(e.toString()); }
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            toolWorld.serverTick(server);
            WorldCapture task = serverCapture.get(); if (task != null) { task.tick(); if (task.result().isDone()) serverCapture.compareAndSet(task,null); }
            CreativePasteTask write = serverPaste.get(); if (write != null) { write.tick(); if (write.result().isDone()) serverPaste.compareAndSet(write,null); }
            CreativeFillTask filling=serverFill.get();if(filling!=null){filling.tick();if(filling.result().isDone())serverFill.compareAndSet(filling,null);}
        });
    }
    void tick() {
        filePreviews.tick();
        cleanupEditorBaselines();releaseGpu(64,System.nanoTime()+1_000_000L);
        if(optionsLoading!=null&&optionsLoading.isDone()){try{options=optionsLoading.join();}catch(CompletionException e){optionsWritable=false;report("设置读取失败，原文件保留："+e.getCause());}optionsLoading=null;UiTheme.apply(UiTheme.Palette.parse(options.display.uiTheme));}
        for (var iterator = background.entrySet().iterator(); iterator.hasNext();) {
            var task = iterator.next(); if (!task.getValue().isDone()) continue;
            try { String result=task.getValue().join();if(task.getKey().startsWith("设置保存-")||task.getKey().startsWith("选区保存-"))BetterLitematicaClient.LOGGER.info(result);else report(result); } catch (CompletionException e) { fail(task.getKey() + "失败：" + e.getCause()); } catch(CancellationException e){report(task.getKey()+"已取消");}
            iterator.remove();
        }
        if (client.world != lastWorld) {
            disconnect(); lastWorld = client.world;
            if (lastWorld != null) {
                sessionFile = settingsDirectory.resolve(worldKey() + ".blps");
                draftFile=sessionFile.resolveSibling(sessionFile.getFileName()+".draft");draftReading=draftWriter.read(draftFile);restoring = io.read(sessionFile); message = "Restoring placements...";
                selectionFile = sessionFile.resolveSibling(sessionFile.getFileName()+".selection.nbt");
                Path restoreSelectionFile = selectionFile;
                selectionRestoring = io.submit(() -> SelectionStore.read(restoreSelectionFile));
            }
        }
        if (selectionRestoring != null && selectionRestoring.isDone()) {
            try { selection = selectionRestoring.join(); } catch (CompletionException e) { selectionWritable = false; report("选区配置损坏，保留原文件："+e.getCause()); }
            selectionRestoring = null;
        }
        tickCapture();
        tickPaste();
        toolWorld.tick();tool.tick();
        tickFill();
        tickCommands();
        if(projectLoading!=null&&projectLoading.isDone()){
            try{var version=projectLoading.join();if(version.placements().size()!=1)throw new IllegalStateException("无效项目版本");if(projectReplacement!=null&&!entries.containsKey(projectReplacement))throw new IllegalStateException("目标摆放已移除");
                var placement=version.placements().get(0).identity(projectReplacement==null?UUID.randomUUID():projectReplacement);UUID id=placement.id();
                projectActivation=new ProjectActivation(id,placement,version,projectReplacement!=null);
                if(id.equals(importing)){loader.cancel();importing=null;}queued.remove(id);queued.addFirst(id);
            }catch(RuntimeException e){fail("版本恢复失败："+e);}projectLoading=null;projectReplacement=null;
        }
        if (restoring != null && restoring.isDone()) {
            try {
                PlacementSession session = restoring.join();
                layer = session.layer(); opacity = session.opacity(); rendering = session.rendering(); selected = session.selected();
                for (Placement placement : session.placements()) { entries.put(placement.id(), new Entry(placement)); queued.add(placement.id()); }
                message = entries.isEmpty() ? "Press M to load a blueprint" : "Restored " + entries.size() + " placements";
            } catch (CompletionException e) {
                writable = false; report("Cannot restore placement settings; original file retained, automatic saving disabled: " + e.getCause());
            }
            restoring = null; ready = true;
        }
        LoadCoordinator.Loaded loaded = loader.poll();
        if (loaded != null) activate(loaded);
        else if (importing != null && loader.status().phase().equals("failed")) {
            Entry entry = entries.get(importing);String failedResource=resourceRequests.remove(importing);if(failedResource!=null){resourceFailures.put(failedResource,loader.status().error());fail("投影加载失败："+loader.status().error());}
            if(projectActivation!=null&&projectActivation.id().equals(importing)){if(!projectActivation.existing())entries.remove(importing);else if(entry!=null)entry.state=entry.renderer==null?"版本加载失败":"Ready";projectActivation=null;fail("版本加载失败："+loader.status().error());}
            else if (entry != null) { draftLoadFailed(importing);entry.state = "Load failed: " + loader.status().error(); fail(entry.placement.name() + ": " + entry.state); }
            importing = null;
        }
        if (ready && importing == null && !queued.isEmpty()) {
            importing = queued.removeFirst(); Entry entry = entries.get(importing);
            boolean project=projectActivation!=null&&projectActivation.id().equals(importing);
            String resourceFile=resourceRequests.get(importing);
            if(entry==null&&!project&&resourceFile==null)importing=null;
            else{if(entry!=null&&entry.renderer==null)entry.state="Loading";String source=resourceFile!=null?resourceFile:project?projectActivation.placement().source():entry.placement.source();var shared=resources.get(sourceKey(source));if(shared!=null&&!project)activate(shared.retain());else try{var reference=temporarySources.reference(schematicDirectory,source);loader.request(reference.path(),cacheDirectory,reference.root());}catch(RuntimeException e){if(entry!=null){draftLoadFailed(entry.placement.id());entry.state="加载失败："+e.getMessage();}if(resourceFile!=null){resourceRequests.remove(importing);resourceFailures.put(resourceFile,e.getMessage());}if(project)projectActivation=null;importing=null;fail(e.getMessage());}}
        }
        tickDraftAssociation();tickDraftRecovery();editor.tick();
        if(draftFile!=null){var status=draftWriter.status(draftFile);if(!status.error().isEmpty()&&!status.error().equals(checkpointFailure)){checkpointFailure=status.error();fail("草稿保存失败，正在重试："+status.error());}else if(status.error().isEmpty())checkpointFailure="";}
        if(ready&&client.player!=null&&options.followLayer&&layer.mode()!=LayerRange.Mode.ALL)layerAtPlayer();
        if (analysis != null) {analysis.tick();if(errorOverlay&&client.player!=null){var position=net.minecraft.util.math.BlockPos.ofFloored(client.player.getEyePos());analysis.updateHighlights(new Vec3i(position.getX(),position.getY(),position.getZ()));}}
        nearbyHighlights.tick(client,this);
        materialTotals();
        // A screen can remain pinned to a placement after the global selection changes.
        var pendingMaterials=entries.values().stream().map(e->e.materials).filter(Objects::nonNull).filter(m->!m.finished()).toList();
        if(!pendingMaterials.isEmpty())pendingMaterials.get(Math.floorMod(materialTurn++,pendingMaterials.size())).tick();
        if (++ticks % 20 == 0) save();
        if(ticks%100==0&&temporarySources.cleanupPending())cleanupTemporary();
        String error = io.takeError(); if (error != null) { dirty = true; report(error); }
        if (ticks % 5 == 0) cachedHud = hudStatus();
    }
    String worldKey() {
        String identity;
        if (client.getServer() != null) identity = "local:" + client.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        else if (client.getCurrentServerEntry() != null) identity = "server:" + client.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
        else identity = "connection:" + UUID.randomUUID();
        identity += "|" + client.world.getRegistryKey().getValue();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void activate(LoadCoordinator.Loaded loaded) {
        String onlyResource=resourceRequests.remove(importing);if(onlyResource!=null){importing=null;try{admitResource(onlyResource,loaded);report("已加载："+onlyResource);}catch(RuntimeException e){resourceFailures.put(onlyResource,e.getMessage());fail(e.getMessage());}finally{closeLoaded(loaded);}return;}

        if(projectActivation!=null&&projectActivation.id().equals(importing)){activateProject(loaded);return;}
        Entry entry = entries.get(importing); importing = null;
        if (entry == null || client.world == null) { closeLoaded(loaded); return; }
        var previous=entry.renderer;var previousMetadata=entry.metadata;var previousCounts=entry.counts;
        try {
            loaded=canonicalResource(entry.placement.source(),loaded);entry.metadata = loaded.cache().metadata(); entry.counts = loaded.cache().copyBlockStateCounts();
            entry.renderer = new ProjectionRenderer1201(client, loaded,gpuResources);
            apply(entry);rebuildScene(); entry.state = "Ready";entry.materials=null;
            if(draftAdoptions.containsKey(entry.placement.id())){
                if(previous!=null)previous.suspend();
                if(sessionFile==null||!writable)throw new IllegalStateException("摆放配置不可保存，草稿已保留");
                var persistent=placements().stream().filter(p->!TemporarySources.temporary(p.source())).toList();UUID savedSelection=persistent.stream().anyMatch(p->p.id().equals(selected))?selected:null;
                var settings=new PlacementSession(persistent,savedSelection,layer,opacity,rendering);Path file=sessionFile;
                draftAssociation=new DraftAssociation(entry.placement.id(),previous,previousMetadata,previousCounts,io.submit(()->{PlacementStore.write(file,settings);return null;}));
            }else if(previous!=null)previous.close();
            report("已加载：" + entry.placement.name());if(!loaded.detailsError().isEmpty())report("附加数据读取失败："+loaded.detailsError());
            int current = SharedConstants.getGameVersion().getSaveVersion().getId();
            if (entry.metadata.dataVersion() > current) report("投影来自更新版本，无法识别的方块已标记");
        } catch (RuntimeException e) { if(entry.renderer!=previous&&entry.renderer!=null)entry.renderer.close();closeLoaded(loaded);entry.renderer=previous;entry.metadata=previousMetadata;entry.counts=previousCounts;draftLoadFailed(entry.placement.id());rebuildScene();entry.state = "Activation failed: " + e; report(entry.state); }
    }
    private void activateProject(LoadCoordinator.Loaded loaded){
        var pending=projectActivation;projectActivation=null;importing=null;Entry old=entries.get(pending.id()),next=new Entry(pending.placement());
        LayerRange previousLayer=layer;float previousOpacity=opacity;boolean previousRendering=rendering;UUID previousSelection=selected;
        if(old==null&&pending.existing()||client.world==null){closeLoaded(loaded);return;}
        try{
            guardEditing(pending.id());
            if(!loaded.detailsError().isEmpty())throw new IllegalStateException(loaded.detailsError());loaded=canonicalResource(next.placement.source(),loaded);next.metadata=loaded.cache().metadata();next.counts=loaded.cache().copyBlockStateCounts();
            next.renderer=new ProjectionRenderer1201(client,loaded,gpuResources);next.renderer.place(next.placement);next.renderer.layer(pending.settings().layer());next.renderer.visible(next.placement.enabled()&&next.placement.renderBlocks());next.renderer.opacity(next.placement.opacity());
            printer.stop();cancelPaste();cancelCommands();cancelAnalysis();layer=pending.settings().layer();opacity=pending.settings().opacity();rendering=pending.settings().rendering();
            entries.put(pending.id(),next);for(var entry:entries.values())apply(entry);next.state="Ready";selected=pending.id();rebuildScene();dirty=true;
        }catch(RuntimeException e){layer=previousLayer;opacity=previousOpacity;rendering=previousRendering;selected=previousSelection;if(old==null)entries.remove(pending.id());else entries.put(pending.id(),old);next.close();closeLoaded(loaded);for(var entry:entries.values())apply(entry);rebuildScene();fail("版本加载失败："+e.getMessage());return;}
        if(old!=null)old.close();report("已恢复项目快照");
    }
    private void cancelProjectActivation(UUID id){if(projectLoading!=null&&(id==null||id.equals(projectReplacement))){projectLoading.cancel(true);projectLoading=null;projectReplacement=null;}if(projectActivation==null||id!=null&&!projectActivation.id().equals(id))return;UUID pending=projectActivation.id();if(pending.equals(importing)){loader.cancel();importing=null;}queued.remove(pending);projectActivation=null;}
    private void closeLoaded(LoadCoordinator.Loaded loaded) { try { loaded.close(); } catch (IOException e) { report(e.toString()); } }
    private void apply(Entry entry) {
        if(entry.renderer==null)return;
        var drawn=entry.renderer.layout().placement();
        if(!drawn.sameGeometry(entry.placement)||!drawn.displayFilter().equals(entry.placement.displayFilter()))entry.renderer.place(entry.placement);
        entry.renderer.visible(entry.placement.enabled()&&entry.placement.renderBlocks());entry.renderer.opacity(entry.placement.opacity());
        if(!entry.renderer.layer().equals(layer))entry.renderer.layer(layer);
    }
    private void rebuildScene(){var renderers=entries.values().stream().filter(e->e.renderer!=null).map(e->e.renderer).toList();scene=new ProjectionScene(renderers);for(var renderer:renderers)renderer.scene(scene);}
    boolean entityOverlayMaskNeeded(WorldRenderContext context){
        if(!rendering||temporarilyHidden)return false;
        var gizmo=tool.gizmo();gizmo.prepareFrame();
        return gizmo.visible(context)||editor.marker()!=null||(errorOverlay&&analysis!=null&&!analysis.highlightBoxes().isEmpty())
            ||(options.printer.highlights&&options.printer.highlightOnTop&&(!nearbyHighlights.boxes().isEmpty()||!printer.actionMarks(System.nanoTime()).isEmpty()));
    }
    void render(WorldRenderContext context) {
        gpuResources.nextFrame();
        if(renderChanges.takeAll()){for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.worldAllChanged();}
        else{var changes=renderChanges.drain(64);if(!changes.isEmpty())for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.worldChanged(changes);}
        boolean show=rendering&&!temporarilyHidden&&options.display.projection;
        // All placements publish their visibility before any one can reclaim memory.
        for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.prepareFrame(context,show&&entry.placement.enabled()&&entry.placement.renderBlocks());
        cleanupEditorBaselines();releaseGpu(64,System.nanoTime()+1_000_000L);
        if (!rendering||temporarilyHidden) return;
        var gizmo=tool.gizmo();boolean toolBounds=gizmo.showBounds();
        if(options.boxes||toolBounds)ProjectionOverlays.selection(client,context,gizmo.selectionPreview(),selectionTarget,toolBounds);
        for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.drain();
        List<Entry> active=entries.values().stream().filter(e->e.renderer!=null&&e.placement.enabled()&&e.placement.renderBlocks()).toList();
        if(!active.isEmpty()&&options.display.projection){
            composite.begin(client);
            try{
                buildTurn=RenderScheduler.run(active.stream().map(entry->entry.renderer).toList(),buildTurn,32,System.nanoTime()+4_000_000L,2*1024*1024);
                for(var entry:active)entry.renderer.drawFrame(context);
            }finally{composite.finish(client,1f);}
        }

        ProjectionOverlays.placements(client,context,entries.values().stream().filter(e->e.renderer!=null&&e.placement.enabled()).map(e->e.renderer.layout()).toList(),selected,options.display,toolBounds,gizmo.previewPlacement(),gizmo.previewOffset());
        if(options.printer.highlights){ProjectionOverlays.prepareNearby(client,context,nearbyHighlights.boxes(),options.printer,options.display.extraColor);ProjectionOverlays.drawNearby(client,context,options.printer,false);}
        printer.render(context);ProjectionOverlays.marker(client,context,editor.marker());
        if(options.printer.highlights)ProjectionOverlays.drawNearby(client,context,options.printer,true);
        if (errorOverlay && analysis != null) ProjectionOverlays.errorBoxes(client,context,analysis.highlightBoxes(),options.display,true,options.printer.highlightLimit);
        gizmo.render(context);
    }
    List<Placement> placements() { return entries.values().stream().map(e -> e.placement).toList(); }
    UUID selectedId() { return selected; }
    SchematicEditor editor(){return editor;}
    ProjectionScene editorScene(){return scene;}
    ProjectionRenderer1201 editorRenderer(UUID id){ensureDraftReady();if(projectLoading!=null&&Objects.equals(id,projectReplacement)||projectActivation!=null&&id.equals(projectActivation.id()))throw new IllegalStateException("请等待版本恢复完成");var entry=require(id);if(entry.renderer==null)throw new IllegalStateException("请等待投影加载完成");return entry.renderer;}
    ToolInteractions tool(){return tool;}
    ToolWorldOperations toolWorld(){return toolWorld;}
    boolean toolRenderingEnabled(){return rendering&&!temporarilyHidden;}
    InventoryTransfers inventoryTransfers(){return inventoryTransfers;}
    PrinterEngine printer(){return printer;}
    BedrockController bedrock(){return bedrock;}
    NearbyProjectionHighlights nearbyHighlights(){return nearbyHighlights;}
    Placement placement(UUID id){Entry e=entries.get(id);return e==null?null:e.placement;}
    boolean worldWriteBusy(){return toolWorld.busy()||pasteStarting!=null||paste!=null||fillStarting!=null||fill!=null||commands!=null||commandsLoading!=null;}
    record PrinterSample(boolean inside,net.minecraft.block.BlockState state){}
    record PrinterSource(Placement placement,ProjectionRenderer1201 renderer,BlueprintMetadata metadata){
        @Override public boolean equals(Object value){return value instanceof PrinterSource other&&placement.id().equals(other.placement.id())&&placement.sameGeometry(other.placement)&&placement.name().equals(other.placement.name())&&placement.locked()==other.placement.locked()&&placement.opacity()==other.placement.opacity()&&placement.renderBlocks()==other.placement.renderBlocks()&&placement.lockedAxes()==other.placement.lockedAxes()&&renderer==other.renderer&&metadata==other.metadata;}
        @Override public int hashCode(){return Objects.hash(placement.id(),placement.name(),placement.source(),placement.transform(),placement.enabled(),placement.locked(),placement.opacity(),placement.regions(),placement.renderBlocks(),placement.lockedAxes(),placement.overlapRule(),System.identityHashCode(renderer),System.identityHashCode(metadata));}
    }
    List<Placement> printerPlacements(){return entries.values().stream().map(e->e.placement).filter(Placement::enabled).toList();}
    List<PrinterSource> printerSources(){return entries.values().stream().filter(e->e.placement.enabled()).map(e->new PrinterSource(e.placement,e.renderer,e.renderer==null?e.metadata:e.renderer.metadata())).toList();}
    java.util.function.Function<Vec3i,PrinterSample> printerSampler(){
        var sources=printerSources();for(var source:sources)if(source.renderer()!=null)source.renderer().drain();
        return new PrinterProjectionView(sources);
    }
    java.util.function.Function<Vec3i,PrinterSample> printerSampler(UUID id){
        Entry entry=entries.get(id);if(entry==null||entry.renderer==null)return world->null;var r=entry.renderer;r.drain();
        var source=new PlacementLayout.Source(){public int state(PlacementLayout.Part p,Vec3i local){return r.sampleRaw(p.section(local),p.cell(local));}public BlockStateSpec spec(int region,int state){return r.metadata().palette().get(state);}};
        // The client-thread batch owns this view only until the end of its tick. Sampling still
        // reads live decoded source cells; an unavailable section remains unknown.
        var layout=r.layout();return world->{var cell=layout.sample(world,source);
            if(cell==null)return new PrinterSample(false,null);return new PrinterSample(true,cell.unknown()||r.resolver(cell.part().index()).unresolved(cell.state())?null:r.resolve(cell.part().index(),cell.state()));
        };
    }
    LayerRange layerRange() { return layer; }
    float opacity() { return opacity; }
    InteractionOptions options(){return options;}
    void saveOptions(){if(!optionsWritable)throw new IllegalStateException("设置原文件损坏，已禁用覆盖保存");var snapshot=options.snapshot();submit("设置保存",()->{InteractionOptions.write(optionsFile,snapshot);return "设置已保存";});}
    /** Bounded hit lookup with the same overlap ordering as the renderer. */
    ProjectionScene.Cell informationCell(Vec3i position){
        var parts=new ArrayList<ProjectionScene.Part>();
        for(var entry:entries.values()){
            if(!entry.placement.enabled())continue;
            if(entry.renderer==null){
                if(entry.metadata==null)return null;
                int checked=0;
                for(var region:entry.metadata.regions()){
                    if(++checked>256)return null;
                    if(entry.placement.region(region).enabled()&&PlacementLayout.inside(PlacementBounds.clipped(region,entry.placement.transformFor(region),LayerRange.ALL),position))return null;
                }
                continue;
            }
            var found=entry.renderer.layout().at(position,Math.max(1,256-parts.size()));
            if(found==null||parts.size()+found.size()>256)return null;
            for(var part:found)parts.add(new ProjectionScene.Part(entry.renderer,part));
        }
        return scene.sample(parts,position);
    }
    record Target(Vec3i position,Vec3i local,Vec3i face,net.minecraft.block.BlockState state){}
    Target target(double reach){return target(reach,false);}
    Target target(double reach,boolean last){
        Target result=null;
        if(client.player==null||client.world==null)return null;var eye=client.player.getEyePos();var direction=client.player.getRotationVec(1);
        for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.drain();
        for(var hit:VoxelRay.trace(eye.x,eye.y,eye.z,direction.x,direction.y,direction.z,reach,128)){
            if(!layer.contains(hit.position()))continue;var cell=scene.sample(hit.position());if(cell==null)continue;if(cell.unknown())return null;
            var r=cell.renderer();if(r.metadata().palette().get(cell.id()).isAir()||r.resolver(cell.region().index()).unresolved(cell.id()))continue;
            result=new Target(hit.position(),cell.local(),hit.face(),r.resolve(cell.region().index(),cell.id()));if(!last)return result;
        }return result;
    }
    FileMaterials materialTotals(){return materialTotals(selected);}
    /** Complete source histogram; independent of selection, visibility and resident sections. */
    record BlockFilterSource(BlueprintMetadata metadata,long[] counts){
        List<String> blockIds(){
            var ids=new TreeSet<String>();
            for(int i=0;i<counts.length;i++)if(counts[i]>0&&!metadata.palette().get(i).isAir())ids.add(metadata.palette().get(i).name());
            return List.copyOf(ids);
        }
    }
    BlockFilterSource blockFilterSource(UUID id){
        var entry=entries.get(id);
        return entry==null||entry.metadata==null||entry.counts==null?null:new BlockFilterSource(entry.metadata,entry.counts);
    }
    FileMaterials materialTotals(UUID id){
        Entry entry=entries.get(id);if(entry==null||entry.metadata==null||entry.counts==null)return null;
        if(entry.materials==null)entry.materials=new FileMaterials(entry.metadata,entry.counts,entry.placement.transform());
        return entry.materials;
    }
    void startMaterials(){startMaterials(selected);}
    void startMaterials(UUID id){Entry entry=entries.get(id);if(entry==null)throw new IllegalStateException("投影已移除");if(entry.metadata==null)throw new IllegalStateException("投影加载中");entry.materials=new FileMaterials(entry.metadata,entry.counts,entry.placement.transform());}
    PlacementAnalysis analysis() { return analysis; }
    void worldBlockChanged(net.minecraft.world.BlockView world,BlockPos pos){
        if(world!=client.world||client.world==null||!client.isOnThread())return;
        nearbyHighlights.changed(pos,client.world.getBlockState(pos));
        if(!entries.isEmpty()){var state=client.world.getBlockState(pos);for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.worldBlockChanged(pos,state);}
        if(analysis!=null&&analysis.world(client.world))analysis.changed(pos);
    }
    void worldChunkChanged(net.minecraft.world.BlockView world,int x,int z){
        if(world!=client.world||client.world==null||!client.isOnThread())return;
        boolean loaded=client.world.getChunkManager().isChunkLoaded(x,z);
        if(commands!=null&&loaded)commands.chunkLoaded(x,z);
        nearbyHighlights.chunkChanged(x,z,loaded);
        if(!loaded)for(var entry:entries.values())if(entry.renderer!=null)entry.renderer.worldChunkUnloaded(x,z);
        if(!entries.isEmpty())renderChanges.chunk(x,z,client.world.getBottomY(),client.world.getTopY());
        if(analysis!=null&&analysis.world(client.world))analysis.chunkChanged(x,z);
    }
    void startAnalysis(){startAnalysis(selected);}
    void startAnalysis(UUID id) { Entry entry=entries.get(id);if(entry==null)throw new IllegalStateException("投影已移除"); if (entry.renderer == null) throw new IllegalStateException("请等待投影加载完成"); cancelAnalysis(); analysis = new PlacementAnalysis(entry.renderer, client.world, layer); }
    void pauseAnalysis() { if (analysis == null) throw new IllegalStateException("尚无扫描任务"); analysis.pause(); }
    void cancelAnalysis() { errorOverlay = false; if (analysis != null) analysis.cancel(); ProjectionOverlays.releaseVerificationIndex(); }
    boolean errorOverlayEnabled() { return errorOverlay; }
    void toggleErrorOverlay() { errorOverlay = !errorOverlay; if (!errorOverlay) ProjectionOverlays.releaseVerificationIndex(); }
    void exportMaterials() { exportMaterials(1,false,""); }
    void exportMaterials(int multiplier,boolean missingOnly,String query){exportMaterials(selected,multiplier,missingOnly,query);}
    void exportMaterials(UUID id,int multiplier,boolean missingOnly,String query) {
        exportMaterials(id,multiplier,missingOnly,query,FileMaterials.Sort.MISSING,true);
    }
    void exportMaterials(UUID id,int multiplier,boolean missingOnly,String query,FileMaterials.Sort sort,boolean descending) {
        var totals=materialTotals(id);if(totals==null||!totals.finished())throw new IllegalStateException("材料统计中");
        List<String> lines = new ArrayList<>(); lines.add("item,total,missing,unknown,available");
        for (var row : totals.materials(client, multiplier, missingOnly, query,sort,descending)) lines.add(net.minecraft.registry.Registries.ITEM.getId(row.item()) + "," + row.total() + "," + Math.max(0,row.total()-row.available()) + "," + row.unknown() + "," + row.available());
        Path target = schematicDirectory.resolve("materials-" + System.currentTimeMillis() + ".csv");
        submit("材料导出", () -> { Files.write(target, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); return "材料已导出：" + target.getFileName(); });
    }
    void exportVerifier() { exportVerifier(selected); }
    void exportVerifier(UUID id) {
        if (analysis == null || !Objects.equals(id,analysis.target())) throw new IllegalStateException("请先校验此投影");
        List<String> lines = new ArrayList<>(); lines.add("type,count,expected,actual,ignored");
        analysis.report().groups().forEach((key,count) -> lines.add(key.type() + "," + count + "," + csv(key.expected()) + "," + csv(key.actual()) + "," + analysis.report().ignored(key)));
        Path target = schematicDirectory.resolve("verification-" + System.currentTimeMillis() + ".csv");
        submit("校验导出", () -> { Files.write(target, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); return "校验已导出：" + target.getFileName(); });
    }
    private record SourceInput(TemporarySources.Reference reference,SchematicEdits.Snapshot snapshot,Path directory){
        SchematicPatchIO.Source open(Cancellation cancel)throws IOException{return SchematicPatchIO.open(reference.read(),snapshot,directory,cancel);}
        SchematicDocument document(Cancellation cancel)throws IOException{try(var source=open(cancel)){return SchematicDocument.read(source.path(),cancel);}}
    }
    private SourceInput sourceInput(Entry entry){if(draftAdoptions.containsKey(entry.placement.id()))throw new IllegalStateException("投影正在关联新文件");return new SourceInput(temporarySources.reference(schematicDirectory,entry.placement.source()),editor.snapshot(entry.placement.id()),cacheDirectory.resolve("edit-operations"));}
    void editorChanged(UUID id,Set<SectionKey> changed,SchematicEdits draft){
        nearbyHighlights.clear();
        var entry=require(id);var renderer=entry.renderer;if(renderer==null)throw new IllegalStateException("投影已卸载");
        entry.metadata=renderer.metadata();entry.counts=draft==null?renderer.sourceCounts():draft.counts();entry.materials=null;entry.queryStates=null;renderer.draftChanged(changed);
        var boxes=new ArrayList<PlacementBounds>();for(var key:changed){var part=renderer.layout().part(key.region());var base=part.region().sectionOrigin(key);var box=PlacementBounds.clipped(new Region("edited",base,new Vec3i(16,16,16)),part.transform(),LayerRange.ALL);boxes.add(new PlacementBounds(box.min().add(new Vec3i(-1,-1,-1)),box.max().add(new Vec3i(1,1,1))));}
        for(var value:entries.values())if(value.renderer!=null)value.renderer.invalidateWorld(boxes);
        checkpointEditor(id,draft);if(analysis!=null)for(var box:boxes)for(int x=box.min().x()>>4;x<=box.max().x()>>4;x++)for(int z=box.min().z()>>4;z<=box.max().z()>>4;z++)analysis.chunkChanged(x,z);
    }
    void ensureDraftReady(){if(draftFile!=null&&!draftWriter.reserve(draftFile))throw new IllegalStateException("草稿保存队列已满");if(draftReading!=null||draftRecovering!=null||recoveringDraft!=null||draftArchiving!=null||!draftError.isEmpty())throw new IllegalStateException("请先处理编辑草稿恢复");}
    boolean hasDraftRecovery(){return draftReading!=null||draftRecovering!=null||recoveringDraft!=null||draftArchiving!=null||!draftError.isEmpty();}
    String draftRecoveryStatus(){return !draftError.isEmpty()?draftError:draftArchiving!=null?"正在保留恢复文件":draftRecovering!=null?"正在恢复修改":"正在读取草稿";}
    void retryDraftRecovery(){if(!hasDraftRecovery()||draftFile==null||draftArchiving!=null)return;draftRetrySource=true;draftError="";recoveringDraft=null;draftRecovering=null;draftReading=draftWriter.read(draftFile);}
    boolean canRecoverDraftSeparately(){return recoveringDraft!=null&&!draftError.isEmpty()&&draftArchiving==null;}
    void recoverDraftSeparately(){
        if(!canRecoverDraftSeparately())throw new IllegalStateException("草稿尚未就绪");
        var next=new DraftStore.Draft(recoveringDraft.worldKey(),recoveringDraft.placement().identity(UUID.randomUUID()),recoveringDraft.checkpoint());draftWriter.write(draftFile,next);draftRecovering=null;recoveringDraft=next;draftError="";
    }
    void archiveDraftRecovery(){if(!hasDraftRecovery()||draftFile==null||draftArchiving!=null)return;draftRetrySource=false;draftRecovering=null;draftReading=null;draftArchiving=draftWriter.archive(draftFile);}
    private void tickDraftRecovery(){
        if(draftArchiving!=null&&draftArchiving.isDone()){var result=draftArchiving;draftArchiving=null;try{Path backup=result.join();recoveringDraft=null;draftError="";if(backup!=null)report("草稿已保留："+backup.getFileName());}catch(RuntimeException failure){draftError="保留失败："+failure.getMessage();}}
        if(draftReading!=null&&draftReading.isDone()){var result=draftReading;draftReading=null;try{recoveringDraft=result.join();if(recoveringDraft!=null&&!recoveringDraft.worldKey().equals(sessionFile.getFileName().toString()))throw new IllegalStateException("草稿所属世界不符");}catch(RuntimeException failure){draftError="草稿读取失败："+failure.getMessage();}}
        if(!ready||recoveringDraft==null||draftArchiving!=null||!draftError.isEmpty())return;
        var saved=recoveringDraft.placement();var entry=entries.get(saved.id());
        if(entry==null){entry=new Entry(saved);entries.put(saved.id(),entry);queued.add(saved.id());dirty=true;}
        if(!entry.placement.source().equals(saved.source())){draftError="摆放已关联其他源文件";return;}
        if(draftRetrySource){
            draftRetrySource=false;if(saved.id().equals(importing)){loader.cancel();importing=null;}queued.remove(saved.id());entry.close();var resource=resources.remove(sourceKey(saved.source()));if(resource!=null)closeLoaded(resource);entry.state="Loading";queued.add(saved.id());rebuildScene();
        }
        if(entry.renderer==null){if(!queued.contains(saved.id())&&!saved.id().equals(importing))draftError="草稿源文件未能加载";return;}
        if(draftRecovering==null){var metadata=entry.renderer.metadata();var counts=entry.renderer.sourceCounts();var checkpoint=recoveringDraft.checkpoint();draftRecovering=io.submit(()->SchematicEdits.restore(checkpoint,metadata,counts));}
        if(draftRecovering.isDone()){var result=draftRecovering;draftRecovering=null;try{var restored=result.join();editor.restore(saved.id(),entry.renderer,restored);editorChanged(saved.id(),restored.snapshot().sections().keySet(),restored);checkpointRevision=restored.snapshot().revision();checkpointPlacement=saved;recoveringDraft=null;report("已恢复编辑草稿");}catch(RuntimeException failure){draftError="草稿恢复失败："+(failure.getCause()==null?failure.getMessage():failure.getCause().getMessage());}}
    }
    void admitEditorCheckpoint(UUID id,SchematicEdits edits){
        if(draftFile==null)throw new IllegalStateException("尚未连接世界");if(!draftWriter.reserve(draftFile))throw new IllegalStateException("草稿保存队列已满");
        var placement=require(id).placement;draftWriter.write(draftFile,new DraftStore.Draft(sessionFile.getFileName().toString(),placement,edits.checkpoint()));checkpointRevision=edits.snapshot().revision();checkpointPlacement=placement;
    }
    void checkpointEditor(UUID id,SchematicEdits edits){
        if(draftFile==null||recoveringDraft!=null)return;
        if(edits==null){draftWriter.clear(draftFile);checkpointRevision=-1;checkpointPlacement=null;return;}
        var placement=draftAdoptions.getOrDefault(id,require(id).placement);if(checkpointRevision==edits.snapshot().revision()&&placement.equals(checkpointPlacement))return;if(TemporarySources.temporary(placement.source()))return;
        draftWriter.write(draftFile,new DraftStore.Draft(sessionFile.getFileName().toString(),placement,edits.checkpoint()));checkpointRevision=edits.snapshot().revision();checkpointPlacement=placement;
    }
    EditorBaseline editorBaseline(UUID id){
        var entry=require(id);if(!TemporarySources.temporary(entry.placement.source()))return null;if(editorBaselines.size()>=16)throw new IllegalStateException("请等待临时投影保存完成");
        var lease=temporarySources.lease(schematicDirectory,entry.placement.source());Path root=schematicDirectory,directory=root.resolve(".betterlitematica-drafts"),target=directory.resolve(UUID.randomUUID()+".litematic");String expected=entry.metadata.sourceSha256();var job=new EditorBaseline(target);editorBaselines.add(job);
        var future=io.submit(()->{try(lease){Path source=lease.reference().read();Files.createDirectories(directory);if(!directory.toRealPath().startsWith(root.toRealPath()))throw new IOException("草稿底本目录越界");Path temporary=Files.createTempFile(directory,"baseline-",".part");try{
            try(var in=Files.newInputStream(source);var out=Files.newOutputStream(temporary)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){Cancellation.THREAD.check();if(job.commit.cancelled())throw new InterruptedIOException("临时投影保存已取消");out.write(buffer,0,n);}}
            if(!SchematicImporter.sha256(temporary,Cancellation.THREAD).equals(expected))throw new IOException("临时投影源已改变");if(!job.commit.commit(temporary,target))throw new InterruptedIOException("临时投影保存已取消");return root.relativize(target).toString().replace('\\','/');
        }finally{Files.deleteIfExists(temporary);}}});
        future.whenComplete((value,error)->{if(error!=null)lease.close();});job.result=future;return job;
    }
    void adoptEditorBaseline(UUID id,EditorBaseline job){if(job.abandoned)throw new IllegalStateException("临时投影保存已取消");var entry=require(id);entry.placement=entry.placement.source(job.result.join());job.accepted=true;dirty=true;}
    private void cleanupEditorBaselines(){
        for(var it=editorBaselines.iterator();it.hasNext();){var job=it.next();if(job.accepted||job.result.isCompletedExceptionally()){it.remove();continue;}
            if(!job.abandoned||!job.result.isDone())continue;
            if(job.cleanup!=null&&job.cleanup.isDone()){try{job.cleanup.join();it.remove();continue;}catch(RuntimeException failure){job.cleanup=null;}}
            if(job.cleanup==null&&ticks%20==0)job.cleanup=io.submit(()->{Files.deleteIfExists(job.path);return null;});
        }
    }
    CompletableFuture<String> saveDraft(UUID id,SchematicEdits.Snapshot patch,String name,NewFileCommit commit){
        var entry=require(id);var reference=temporarySources.reference(schematicDirectory,entry.placement.source());Path target=exportPath(name,".litematic"),directory=cacheDirectory.resolve("edit-operations");
        return io.submit(()->{Files.createDirectories(directory);Path temporary=directory.resolve(UUID.randomUUID()+".litematic");try{SchematicPatchIO.write(reference.read(),temporary,patch,()->Thread.currentThread().isInterrupted()||commit.cancelled());if(!commit.commit(temporary,target))throw new CancellationException("另存已取消");return schematicDirectory.relativize(target).toString().replace('\\','/');}finally{Files.deleteIfExists(temporary);}});
    }
    void adoptDraft(UUID id,String source){var entry=require(id);draftAdoptions.put(id,entry.placement);entry.placement=entry.placement.source(source);entry.state="Loading";queued.add(id);}
    private void tickDraftAssociation(){
        var pending=draftAssociation;if(pending==null||!pending.stored().isDone())return;
        try{pending.stored().join();}catch(RuntimeException failure){draftLoadFailed(pending.id());dirty=true;fail("投影文件已保存，关联保存失败；草稿已保留");return;}
        draftAssociation=null;draftAdoptions.remove(pending.id());dirty=true;
        if(pending.previous()!=null)pending.previous().close();
        try{checkpointEditor(pending.id(),null);}catch(RuntimeException failure){fail("关联已保存，恢复文件清理失败："+failure.getMessage());}finally{editor.sourceLoaded(pending.id(),true);}
    }
    private void draftLoadFailed(UUID id){
        var previous=draftAdoptions.remove(id);if(previous==null)return;var entry=entries.get(id);
        if(draftAssociation!=null&&draftAssociation.id().equals(id)){
            var pending=draftAssociation;draftAssociation=null;if(entry!=null){if(entry.renderer!=null)entry.renderer.close();entry.renderer=pending.previous();entry.metadata=pending.metadata();entry.counts=pending.counts();entry.materials=null;}else if(pending.previous()!=null)pending.previous().close();
        }
        if(entry!=null){entry.placement=previous;entry.state=entry.renderer==null?"加载失败":"Ready";}editor.sourceLoaded(id,false);rebuildScene();dirty=true;
    }
    private void guardEditing(UUID id){if(recoveringDraft!=null&&id.equals(recoveringDraft.placement().id()))throw new IllegalStateException("请先处理编辑草稿恢复");if(editor.owns(id)&&!editor.dirty()&&!editor.busy())editor.discard();if(editor.owns(id))throw new IllegalStateException("请先另存或放弃当前编辑");}
    private void guardEditingSource(String source){var target=placement(editor.target());if(target!=null&&sourceKey(target.source()).equals(sourceKey(source)))guardEditing(target.id());}
    void editReplace(String from,String to,String output,boolean blockType) {
        editReplace(selected,from,to,output,blockType);
    }
    CompletableFuture<String> editingJob(UUID id){return editingJobs.get(id);}
    void editReplace(UUID id,String from,String to,String output,boolean blockType) {
        var existing=editingJobs.get(id);if(existing!=null&&!existing.isDone())throw new IllegalStateException("此投影正在导出");
        Entry entry=require(id);var input=sourceInput(entry);Path target=exportPath(output,".litematic");
        BlockStateSpec original=BlockStateSpec.parse(from),replacement=BlockStateSpec.parse(to);
        editingJobs.entrySet().removeIf(e->!entries.containsKey(e.getKey())&&e.getValue().isDone());editingJobs.put(id,submit("投影替换",()->{try(var source=input.open(Cancellation.THREAD)){var result=LitematicEdit.replace(source.path(),target,original,replacement,blockType,null,Cancellation.THREAD);return "已生成 "+target.getFileName()+" · 修改 "+result.changed()+" 格";}}));
    }
    void editSet(int x,int y,int z,String state,String output) {
        Entry entry=require();var input=sourceInput(entry);Path target=exportPath(output,".litematic");BlockStateSpec replacement=BlockStateSpec.parse(state);
        submit("投影单格编辑",()->{try(var source=input.open(Cancellation.THREAD)){var result=LitematicEdit.replace(source.path(),target,null,replacement,false,new Vec3i(x,y,z),Cancellation.THREAD);return "已生成 "+target.getFileName()+"：修改 "+result.changed()+" 格";}});
    }
    private Path exportPath(String name,String extension) {
        if(!name.matches("[\\p{L}\\p{N}_ .-]{1,100}")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("请使用普通文件名，不能包含路径");
        return schematicDirectory.resolve(name.endsWith(extension)?name:name+extension);
    }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    void convertSource(String format,String name){
        Entry entry=require();var input=sourceInput(entry);if(!List.of("litematic","schem","nbt").contains(format))throw new IllegalArgumentException("格式应为 litematic/schem/nbt");Path target=exportPath(name,"."+format);
        submit("格式转换",()->{try(var handle=input.open(Cancellation.THREAD)){Path source=handle.path();
            Map<String,Object> root;if(format.equals("litematic"))root=SourceVersions.litematic(SchematicFormats.canonical(NbtReader.read(source,SchematicImporter.SOURCE_LIMITS,Cancellation.THREAD),Cancellation.THREAD),Cancellation.THREAD);else root=SchematicFormats.exportSingle(SchematicDocument.read(source,Cancellation.THREAD),format);
            NbtWriter.writeNew(target,root,Cancellation.THREAD);return "已导出 "+target.getFileName()+(format.equals("litematic")?"":"；目标格式不保存 Litematica 计划刻，原文件保留");}});
    }
    void saveVersion(String project){CompletableFuture<String> task=saveProjectVersion(project);background.put("项目快照-"+UUID.randomUUID(),task.thenApply(v->"项目版本已保存："+v));}
    CompletableFuture<String> saveProjectVersion(String project){Entry entry=require();Placement p=entry.placement;var input=sourceInput(entry);LayerRange range=layer;float alpha=opacity;boolean visible=rendering;return io.submit(()->{try(var source=input.open(Cancellation.THREAD)){return ProjectVersions.save(schematicDirectory,project,p,range,alpha,visible,source.path(),source.temporary()?source.path().getParent():input.reference().root());}});}
    CompletableFuture<List<String>> projectNames(){return io.submit(()->ProjectVersions.projects(schematicDirectory));}
    CompletableFuture<List<ProjectVersions.Version>> versionDetails(String project){return io.submit(()->ProjectVersions.details(schematicDirectory,project));}
    CompletableFuture<String> renameVersion(String project,String version,String name){return io.submit(()->{ProjectVersions.rename(schematicDirectory,project,version,name);return version;});}
    CompletableFuture<String> deleteVersion(String project,String version){return io.submit(()->{ProjectVersions.delete(schematicDirectory,project,version);return "";});}
    CompletableFuture<List<String>> versions(String project){return io.submit(()->ProjectVersions.list(schematicDirectory,project));}
    void restoreVersion(String project,String version){restoreVersion(project,version,null);}
    void restoreVersion(String project,String version,UUID target){requireWorld();if(target!=null)guardEditing(target);if(projectLoading!=null||projectActivation!=null)throw new IllegalStateException("版本正在加载");if(target!=null&&!entries.containsKey(target))throw new IllegalStateException("目标摆放已移除");projectReplacement=target;projectLoading=io.submit(()->ProjectVersions.load(schematicDirectory,project,version));}
    private CompletableFuture<String> submit(String label, Callable<String> task) { if (background.size() >= 16) throw new IllegalStateException("后台任务已满");var job=io.submit(task);background.put(label + "-" + UUID.randomUUID(),job);return job; }
    Placement selectedPlacement() { Entry entry = entries.get(selected); return entry == null ? null : entry.placement; }
    String entryStatus(UUID id) { Entry entry = entries.get(id); return entry == null ? "已移除" : switch(entry.state){case "Queued"->"排队中";case "Loading"->"加载中";case "Ready"->"已加载";default->entry.state;}; }
    CompletableFuture<SessionIo.Listing> files(String relative) { return io.list(schematicDirectory, relative); }
    CompletableFuture<SessionIo.Listing> files(String relative,String query) { return io.list(schematicDirectory,relative,query); }
    private Path sourcePath(String relative)throws IOException{return temporarySources.reference(schematicDirectory,relative).read();}
    CompletableFuture<SchematicFileInfo> fileInfo(String relative){return io.submit(()->SchematicFileInfo.read(sourcePath(relative),Cancellation.THREAD));}
    CompletableFuture<SchematicPreview.Images> previewFile(String relative){return filePreviews.request(temporarySources.reference(schematicDirectory,relative));}
    CompletableFuture<FilePreviews.Preview> interactivePreview(String relative){return filePreviews.interactive(temporarySources.reference(schematicDirectory,relative));}
    CompletableFuture<FilePreviews.Preview> interactivePreview(String relative,Map<String,RegionPlacement> regions){return filePreviews.interactive(temporarySources.reference(schematicDirectory,relative),regions);}
    FilePreviews.Orbit previewOrbit(SchematicPreview.OrbitModel model){return filePreviews.orbit(model);}
    FilePreviews.Orbit previewOrbit(FilePreviews.Preview preview){return filePreviews.orbit(preview);}
    FilePreviews.Stats previewStats(){return filePreviews.stats();}
    CompletableFuture<String> exportFile(String relative,String output,String format,String name,String author,String description,int[] preview){Path filename=exportPath(output,"."+format).getFileName();var reference=temporarySources.reference(schematicDirectory,relative);int[] pixels=preview==null?null:preview.clone();return io.submit(()->{Path source=reference.read(),target=(reference.temporary()?schematicDirectory.toRealPath():source.getParent()).resolve(filename);SchematicFileInfo.export(source,target,format,name,author,description,pixels,Cancellation.THREAD);return schematicDirectory.toRealPath().relativize(target).toString().replace((char)92,(char)47);});}
    CompletableFuture<String> createDirectory(String relative,String name){return io.submit(()->{if(!name.matches("[\\p{L}\\p{N}_ -]{1,80}"))throw new IOException("无效目录名称");Path root=schematicDirectory.toRealPath(),parent=root.resolve(relative).normalize().toRealPath();if(!parent.startsWith(root))throw new IOException("目录越界");Files.createDirectory(parent.resolve(name));return name;});}
    CompletableFuture<int[]> preview(){requireWorld();if(previewCapture!=null&&!previewCapture.isDone())throw new IllegalStateException("预览图正在获取");previewCapture=new CompletableFuture<>();return previewCapture;}
    @SuppressWarnings("deprecation") void capturePreview(){var request=previewCapture;if(request==null)return;previewCapture=null;if(request.isCancelled())return;try{var framebuffer=client.getFramebuffer();if((long)framebuffer.textureWidth*framebuffer.textureHeight>16_777_216)throw new IllegalStateException("窗口分辨率超过截图预算");try(var image=net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(framebuffer);var small=new net.minecraft.client.texture.NativeImage(140,140,false)){int side=Math.min(image.getWidth(),image.getHeight());image.resizeSubRectTo((image.getWidth()-side)/2,(image.getHeight()-side)/2,side,side,small);request.complete(small.makePixelArray());}}catch(RuntimeException e){request.completeExceptionally(e);}}
    boolean projectionRenderingEnabled() { return rendering; }
    boolean hasProjection() { return !entries.isEmpty(); }
    String hud() { return cachedHud; }
    private String hudStatus() {
        if(!actionError.isEmpty())return actionError;
        if(editor.active())return editor.hud();
        if(!writable)return "配置异常 · 自动保存已停用";
        if(restoring!=null)return "恢复投影中…";
        if(entries.isEmpty())return "";
        if(!rendering)return "投影已隐藏";
        Entry entry=entries.get(selected);
        if(entry!=null){
            // The HUD card is narrow: the state leads and the name, without its extension, may be cut.
            if(entry.renderer==null)return entryStatus(selected)+" · "+hudName(entry.placement.name());
            if(!entry.renderer.error().isEmpty())return "渲染异常 · "+hudName(entry.placement.name());
        }
        return "投影 "+entries.size()+" · 仅预览附近区域";
    }
    private static String hudName(String name){return name.replaceFirst("(?i)\\.(litematic|schem|schematic|nbt)$","");}
    String menuStatus() {
        if(!actionError.isEmpty())return actionError;
        if(!writable)return "配置文件异常，自动保存已停用";
        if(restoring!=null)return "正在恢复摆放…";
        var entry=entries.get(selected);
        return entries.size()+" 个摆放  ·  "+(rendering?"渲染开启":"渲染关闭")+"  ·  "+(entry==null?"未选择摆放":"已选："+entry.placement.name());
    }
    String status() {
        if (!actionError.isEmpty()) return actionError;
        Entry entry = entries.get(selected);
        String prefix = "Partial preview | " + entries.size() + " placements";
        if (!writable) prefix = "SAVE DISABLED (settings error) | " + prefix;
        if (!rendering) prefix += " | Rendering OFF";
        if (restoring != null) return "Restoring placements...";
        if (entry == null) return prefix + " | " + message;
        String detail = entry.renderer == null ? entry.state : entry.renderer.status();
        String error = entry.renderer == null ? "" : entry.renderer.error();
        return prefix + " | " + entry.placement.name() + " | " + detail + (error.isEmpty() ? "" : " | " + error);
    }
    private void requireWorld() {
        if (client.world == null || client.player == null) throw new IllegalStateException("请先进入世界");
        if (!ready) throw new IllegalStateException("摆放设置仍在加载，请稍候");
    }
    AreaSelection selection() { return selection; }
    void selectionAdd(String name) { requireSelection(); Vec3i here=playerPosition();selection=selection.add(new SelectionBox(name,here,here));selectionTarget=null;selectionDirty=true; }
    void selectionRename(String name){requireSelection();selection=selection.rename(name);selectionTarget=null;selectionDirty=true;}
    void selectionCoordinates(Vec3i first,Vec3i second,Vec3i origin){requireSelection();var box=new SelectionBox(selection.current().name(),first,second);box.region();selection=selection.put(box).origin(origin);selectionDirty=true;}
    void selectionApply(AreaSelection value){requireSelection();selection=value;selectionTarget=null;selectionDirty=true;}
    SelectionTarget selectionTarget(){return selectionTarget;}
    void selectionTarget(SelectionTarget value){requireSelection();selectionTarget=value;if(value==null)selection=selection.select("");if(value!=null&&value.part()!=SelectionTarget.Part.ORIGIN)selection=selection.select(value.name());selectionDirty=true;}
    void selectionDrag(AreaSelection original,SelectionTarget target,Vec3i offset){requireSelection();selection=target.translate(original,offset);selectionDirty=true;}
    void selectionExpand(Vec3i direction,int amount){requireSelection();selection=selection.put(selection.current().expand(direction,amount));selectionDirty=true;}
    private Path selectionLibrary(){requireSelection();return settingsDirectory.resolve("selections").resolve(worldKey());}
    CompletableFuture<List<String>> selectionNames(){Path directory=selectionLibrary();return io.submit(()->SelectionLibrary.list(directory));}
    CompletableFuture<AreaSelection> selectionLoad(String name){Path directory=selectionLibrary();return io.submit(()->SelectionLibrary.load(directory,name));}
    CompletableFuture<String> selectionSave(String name){Path directory=selectionLibrary();AreaSelection value=selection;return io.submit(()->{SelectionLibrary.save(directory,name,value);return name;});}
    CompletableFuture<String> selectionLibraryRename(String name,String next){Path directory=selectionLibrary();return io.submit(()->{SelectionLibrary.rename(directory,name,next);return next;});}
    CompletableFuture<String> selectionDelete(String name){Path directory=selectionLibrary();return io.submit(()->{SelectionLibrary.delete(directory,name);return "";});}
    void selectionSelect(String name) { requireSelection();selectionTarget=null;selection=selection.select(name);selectionDirty=true; }
    void selectionCorner(boolean first) { selectionCorner(first,playerPosition()); }
    void selectionCorner(boolean first,Vec3i pos) { requireSelection(); var box=selection.current();selection=selection.put(new SelectionBox(box.name(),first?pos:box.first(),first?box.second():pos));selectionTarget=new SelectionTarget(first?SelectionTarget.Part.FIRST:SelectionTarget.Part.SECOND,box.name(),0);selectionDirty=true; }
    void selectionOrigin() { requireSelection();selection=selection.origin(playerPosition());selectionTarget=new SelectionTarget(SelectionTarget.Part.ORIGIN,"",0);selectionDirty=true; }
    void selectionRemove() { requireSelection();selection=selection.remove();selectionTarget=null;selectionDirty=true; }
    void selectionMode() { requireSelection();selection=selection.toggleMode();selectionDirty=true; }
    void selectionNudge(Vec3i offset) { requireSelection();selection=selectionTarget==null?selection.put(selection.current().translate(offset)):selectionTarget.translate(selection,offset);selectionDirty=true; }
    private void requireSelection() { requireWorld(); if(selectionRestoring!=null)throw new IllegalStateException("选区正在恢复"); }
    private Vec3i playerPosition() { requireWorld();var pos=client.player.getBlockPos();return new Vec3i(pos.getX(),pos.getY(),pos.getZ()); }
    void capture(String name) {
        capture(name,false);
    }
    void captureTemporary(String name){capture(name,true);}
    void captureTemporary(){capture(selection.selected().isBlank()?"选区":selection.selected(),true);}
    private void capture(String name,boolean temporary){
        requireSelection(); if(capture!=null||captureStarting!=null||capturePreparing!=null||captureSaving!=null&&!captureSaving.isDone())throw new IllegalStateException("已有捕获任务，请先完成或取消");
        if(!temporary&&(!name.matches("[\\p{L}\\p{N}_ .-]{1,100}")||name.equals(".")||name.equals("..")))throw new IllegalArgumentException("文件名只能包含文字、数字、空格、下划线、点和短横线");
        if(selection.boxes().isEmpty())throw new IllegalStateException("请先设置选区");
        captureTemporary=temporary;captureWithPreview=options.capturePreviews&&!temporary;captureName=temporary?name.substring(0,Math.min(name.length(),110)):name.endsWith(".litematic")?name:name+".litematic";captureOrigin=selection.origin();List<SelectionBox> boxes=selection.boxes();
        long epoch=captureEpoch.incrementAndGet();var key=client.world.getRegistryKey();var server=client.getServer();
        if(server==null){capture=new WorldCapture(client.world,boxes);report("多人捕获只能保存客户端收到的数据，库存和计划刻可能缺失");}
        else {var starting=new CompletableFuture<WorldCapture>();captureStarting=starting;server.execute(()->{
            try { if(captureEpoch.get()!=epoch)throw new CancellationException("世界已切换"); var world=server.getWorld(key);if(world==null)throw new IllegalStateException("服务端世界已卸载");
                WorldCapture task=new WorldCapture(world,boxes);synchronized(captureEpoch){if(captureEpoch.get()!=epoch)throw new CancellationException("捕获已取消");serverCapture.set(task);starting.complete(task);}
            }catch(RuntimeException e){starting.completeExceptionally(e);}
        });}
    }
    String captureStatus() { return captureSaving!=null&&!captureSaving.isDone()?"正在保存投影":capturePreparing!=null?(capturePreparingEpoch==captureEpoch.get()?"准备临时投影":"取消中"):captureStarting!=null?"准备捕获...":capture==null?"没有捕获任务":capture.status(); }
    void cancelCapture() { synchronized(captureEpoch){captureEpoch.incrementAndGet();if(capture!=null)capture.cancel();if(captureSaving!=null)captureSaving.cancel(true);captureSaving=null;WorldCapture serverTask=serverCapture.getAndSet(null);if(serverTask!=null)serverTask.cancel();captureStarting=null;capture=null;} }
    private void tickCapture() {
        if(capturePreparing!=null&&capturePreparing.isDone()){
            try{String key=capturePreparing.join();if(capturePreparingEpoch!=captureEpoch.get()||client.world==null)removeTemporary(key);else try{add(new Placement(UUID.randomUUID(),temporaryName+" · 临时",key,new PlacementTransform(temporaryOrigin,0,false,false),true,false,opacity));report("已创建临时投影");}catch(RuntimeException e){removeTemporary(key);throw e;}}
            catch(RuntimeException e){if(capturePreparingEpoch==captureEpoch.get())fail("临时投影创建失败："+(e.getCause()==null?e.getMessage():e.getCause().getMessage()));}capturePreparing=null;
        }
        if(captureStarting!=null&&captureStarting.isDone()){
            try{capture=captureStarting.join();}catch(CompletionException|CancellationException e){fail("捕获启动失败："+e);}
            captureStarting=null;
        }
        if(capture==null)return;
        if(!(capture.world() instanceof net.minecraft.server.world.ServerWorld))capture.tick();
        if(capture.result().isDone()){
            try{var data=capture.result().join();String name=captureName;Vec3i origin=captureOrigin;int version=SharedConstants.getGameVersion().getSaveVersion().getId();String author=client.getSession().getUsername();Path output=schematicDirectory.resolve(name);
                if(captureTemporary){long epoch=captureEpoch.get();capturePreparingEpoch=epoch;temporaryOrigin=origin;temporaryName=name;capturePreparing=io.submit(()->temporarySources.create(LitematicExport.create(name,author,version,origin,data),()->Thread.currentThread().isInterrupted()||captureEpoch.get()!=epoch));}
                else {boolean attach=captureWithPreview;long epoch=captureEpoch.get();captureSaving=submit("投影保存",()->{
                    Cancellation cancel=()->Thread.currentThread().isInterrupted()||captureEpoch.get()!=epoch;cancel.check();
                    var root=LitematicExport.create(name,author,version,origin,data);
                    if(attach)FilePreviews.await(filePreviews.attach(root,cancel),cancel);
                    NbtWriter.writeNew(output,root,cancel);return "投影已保存："+output.getFileName();
                });}
            }catch(CompletionException|CancellationException e){fail("捕获未完成："+e);}capture=null;
        }
    }
    void paste(ReplaceRule rule,boolean entities,boolean nbt) {
        paste(selected,rule,entities,nbt);
    }
    UUID paste(UUID id,ReplaceRule rule,boolean entities,boolean nbt) {
        Entry entry=require(id);if(!client.player.isCreative())throw new IllegalStateException("粘贴需要创造模式");
        if(worldWriteBusy())throw new IllegalStateException("已有施工任务");
        var server=client.getServer();if(server==null)return commands(id,null,rule,nbt,entities);
        printer.pause("创造粘贴");creativeJob=new CreativeJob(UUID.randomUUID(),id,entry.placement.name(),false,false,rule,entities,nbt,null,new CommandSettings());creativeResult="";creativePauseRequested=false;
        long epoch=pasteEpoch.incrementAndGet();var input=sourceInput(entry);editor.pause();var placement=entry.placement;LayerRange range=layer;var dimension=client.world.getRegistryKey();UUID actor=client.player.getUuid();
        var future=new CompletableFuture<CreativePasteTask>();pasteStarting=future;
        io.submit(()->input.document(()->Thread.currentThread().isInterrupted()||pasteEpoch.get()!=epoch)).whenComplete((document,failure)->{
            if(failure!=null){future.completeExceptionally(failure);return;}server.execute(()->{
                try{if(pasteEpoch.get()!=epoch)throw new CancellationException("粘贴已取消");var world=server.getWorld(dimension);if(world==null)throw new IllegalStateException("目标世界已关闭");
                    CreativePasteTask task=new CreativePasteTask(world,actor,document,placement,range,rule,entities,nbt);synchronized(pasteEpoch){if(pasteEpoch.get()!=epoch)throw new CancellationException("粘贴已取消");task.paused(creativePauseRequested);serverPaste.set(task);future.complete(task);}
                }catch(RuntimeException e){future.completeExceptionally(e);}
            });
        });
        return creativeJob.id();
    }
    String pasteStatus(){return pasteStarting!=null?"正在读取完整投影...":paste==null?"没有粘贴任务":paste.status();}
    void pausePaste(){if(paste!=null)paste.pause();}
    void cancelPaste(){synchronized(pasteEpoch){pasteEpoch.incrementAndGet();if(paste!=null)paste.cancel();var task=serverPaste.getAndSet(null);if(task!=null)task.cancel();pasteStarting=null;paste=null;}}
    private void pasteFailure(Throwable failure){
        Throwable cause=failure;while((cause instanceof CompletionException||cause instanceof ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();
        BetterLitematicaClient.LOGGER.error("Creative paste failed",cause);creativeResult="粘贴失败："+(cause.getMessage()==null?cause.toString():cause.getMessage());fail(creativeResult);
    }
    private void tickPaste(){
        if(pasteStarting!=null&&pasteStarting.isDone()){try{paste=pasteStarting.join();}catch(CompletionException|CancellationException e){pasteFailure(e);}pasteStarting=null;}
        if(paste!=null&&paste.result().isDone()){try{creativeResult=paste.result().join();report(creativeResult);}catch(CompletionException e){pasteFailure(e);}paste=null;}
    }
    void fill(String state,String match){
        requireSelection();if(!client.player.isCreative()||client.getServer()==null)throw new IllegalStateException("此入口要求单人创造模式");if(worldWriteBusy())throw new IllegalStateException("已有施工任务");printer.pause("工具操作");
        var server=client.getServer();var dimension=client.world.getRegistryKey();UUID actor=client.player.getUuid();var boxes=selection.boxes();BlockStateSpec target=BlockStateSpec.parse(state),from=match==null?null:BlockStateSpec.parse(match);long epoch=pasteEpoch.get();
        var future=new CompletableFuture<CreativeFillTask>();fillStarting=future;server.execute(()->{try{if(pasteEpoch.get()!=epoch)throw new CancellationException("世界已切换");var task=new CreativeFillTask(server.getWorld(dimension),actor,boxes,target,from);synchronized(pasteEpoch){if(pasteEpoch.get()!=epoch)throw new CancellationException("填充已取消");serverFill.set(task);future.complete(task);}}catch(RuntimeException e){future.completeExceptionally(e);}});
    }
    private void tickFill(){
        if(fillStarting!=null&&fillStarting.isDone()){try{fill=fillStarting.join();}catch(CompletionException|CancellationException e){fail("填充失败："+e);}fillStarting=null;}
        if(fill!=null&&fill.result().isDone()){try{report(fill.result().join());}catch(CompletionException e){fail("填充失败："+e.getCause());}fill=null;}
    }
    void commands(String output,ReplaceRule rule,boolean nbt){
        commands(selected,output,rule,nbt,false);
    }
    UUID commands(UUID id,String output,ReplaceRule rule,boolean nbt,boolean entities){
        Entry entry=require(id);if(worldWriteBusy())throw new IllegalStateException("已有施工任务");if(output==null&&!canSendCommands())throw new IllegalStateException("需要创造模式与服务器 setblock 权限");
        var input=sourceInput(entry);editor.pause();commandPlacement=entry.placement;commandLayer=layer;commandRule=rule;commandNbt=nbt;commandEntities=entities;commandOutput=output==null?null:exportPath(output,".mcfunction");
        if(output==null)printer.pause("创造粘贴");commandSettings=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(options.commands),CommandSettings.class);creativeJob=new CreativeJob(UUID.randomUUID(),id,entry.placement.name(),true,output!=null,rule,entities,nbt,output,commandSettings);creativeResult="";creativePauseRequested=false;
        commandsLoading=io.submit(()->input.document(Cancellation.THREAD));
        return creativeJob.id();
    }
    boolean canSendCommands(){return client.player!=null&&client.player.isCreative()&&client.getNetworkHandler()!=null&&client.getNetworkHandler().getCommandDispatcher().getRoot().getChild("setblock")!=null;}
    CreativeJob creativeJob(UUID target){return creativeJob!=null&&creativeJob.target().equals(target)?creativeJob:null;}
    boolean creativeActive(UUID id){return creativeJob!=null&&creativeJob.id().equals(id)&&(creativeJob.commands()?commands!=null&&!commands.result().isDone()||commandsLoading!=null:paste!=null&&!paste.result().isDone()||pasteStarting!=null);}
    boolean creativePaused(UUID id){return creativeActive(id)&&(creativeJob.commands()?commands==null?creativePauseRequested:commands.paused():paste==null?creativePauseRequested:paste.paused());}
    String creativeStatus(UUID id){if(creativeJob==null||!creativeJob.id().equals(id))return "";if(creativePaused(id))return "已暂停";if(creativeActive(id))return creativeJob.commands()?commands==null?"读取中":commands.status():pasteStatus();if(!creativeResult.isEmpty())return creativeResult;return creativeJob.commands()?commands==null?"":commands.status():paste==null?"":paste.status();}
    void pauseCreative(UUID id){if(!creativeActive(id))return;boolean value=!creativePaused(id);if(creativeJob.commands()){creativePauseRequested=value;if(commands!=null)commands.paused(value);}else synchronized(pasteEpoch){creativePauseRequested=value;if(paste!=null)paste.paused(value);var task=serverPaste.get();if(task!=null)task.paused(value);}}
    void cancelCreative(UUID id){if(!creativeActive(id))return;if(creativeJob.commands()){if(!cancelCommands())return;}else cancelPaste();creativeResult="已取消";}
    private boolean cancelCommands(){if(commands!=null&&!commands.cancel())return false;if(commandsLoading!=null)commandsLoading.cancel(true);commands=null;commandsLoading=null;return true;}
    private void tickCommands(){
        if(commandsLoading!=null&&commandsLoading.isDone()){try{commands=new CommandOperation(client,commandsLoading.join(),commandPlacement,commandLayer,commandRule,commandNbt,commandEntities,commandOutput,commandSettings);commands.paused(creativePauseRequested);}catch(RuntimeException e){creativeResult="命令任务失败："+(e.getCause()==null?e.getMessage():e.getCause().getMessage());fail(creativeResult);}commandsLoading=null;}
        if(commands!=null){commands.tick();if(commands.result().isDone()){try{creativeResult=commands.result().join();report(creativeResult);}catch(CompletionException e){creativeResult="命令任务失败："+e.getCause().getMessage();fail(creativeResult);}commands=null;}}
    }
    List<String> tasks(){List<String> list=new ArrayList<>();if(toolWorld.busy())list.add(toolWorld.status());if(hasDraftRecovery())list.add("编辑草稿："+draftRecoveryStatus());if(editor.busy())list.add("投影编辑："+editor.status);if(projectLoading!=null||projectActivation!=null)list.add("项目版本加载中");if(printer.state()!=PrinterEngine.State.STOPPED)list.add("打印机："+printer.status());if(importing!=null)list.add("投影导入："+loader.status().phase());if(analysis!=null)list.add(analysis.status());if(capture!=null||captureStarting!=null||capturePreparing!=null)list.add(captureStatus());if(paste!=null||pasteStarting!=null)list.add(pasteStatus());if(fill!=null||fillStarting!=null)list.add(fill==null?"准备填充":fill.status());if(commands!=null)list.add(commands.status());if(commandsLoading!=null)list.add("命令任务读取中");list.addAll(background.keySet());return list;}
    void cancelTasks(){toolWorld.cancel();editor.cancel();for(var id:List.copyOf(draftAdoptions.keySet()))draftLoadFailed(id);cancelProjectActivation(null);printer.stop();cancelAnalysis();cancelCapture();cancelPaste();loader.cancel();if(importing!=null){var entry=entries.get(importing);if(entry!=null)entry.state="已取消，可重新加载";}importing=null;for(var request:resourceRequests.entrySet())resourceFailures.put(request.getValue(),"已取消");resourceRequests.clear();for(var id:queued){var entry=entries.get(id);if(entry!=null)entry.state="已取消，可重新加载";}queued.clear();if(projectLoading!=null)projectLoading.cancel(true);projectLoading=null;if(commands!=null)commands.cancel();if(commandsLoading!=null)commandsLoading.cancel(true);commandsLoading=null;commands=null;var task=serverFill.getAndSet(null);if(task!=null)task.cancel();fillStarting=null;fill=null;for(var job:background.values())job.cancel(true);}
    private Entry require() { requireWorld(); Entry entry = entries.get(selected); if (entry == null) throw new IllegalStateException("请先选择投影"); return entry; }
    private Entry require(UUID id){requireWorld();Entry entry=entries.get(id);if(entry==null)throw new IllegalStateException("目标投影已移除");return entry;}
    record ResourceView(String source,String name,int placements,String status){}
    private static String sourceKey(String source){return Path.of(source).normalize().toString().replace('\\','/');}
    private void admitResource(String source,LoadCoordinator.Loaded loaded){String key=sourceKey(source);var previous=resources.get(key);if(previous!=null&&previous.cache().metadata().sourceSha256().equals(loaded.cache().metadata().sourceSha256())&&(previous.detailsError().isEmpty()||!loaded.detailsError().isEmpty()))return;
        long used=resources.values().stream().mapToLong(LoadCoordinator.Loaded::estimatedBytes).sum()-(previous==null?0:previous.estimatedBytes());if(used+loaded.estimatedBytes()>(1L<<30))throw new IllegalStateException("已加载投影达到内存上限，请卸载不使用的投影");
        resources.put(key,loaded.retain());resourceFailures.remove(key);if(previous!=null)closeLoaded(previous);
    }
    private LoadCoordinator.Loaded canonicalResource(String source,LoadCoordinator.Loaded loaded){admitResource(source,loaded);var canonical=resources.get(sourceKey(source)).retain();closeLoaded(loaded);return canonical;}
    private void cancelResourceProject(String source){if(projectLoading!=null)cancelProjectActivation(null);else if(projectActivation!=null&&sourceKey(projectActivation.placement().source()).equals(source))cancelProjectActivation(projectActivation.id());}
    List<ResourceView> resources(){var paths=new LinkedHashSet<>(resources.keySet());paths.addAll(resourceRequests.values());paths.addAll(resourceFailures.keySet());var result=new ArrayList<ResourceView>();for(String path:paths){var value=resources.get(path);String name=value==null?Path.of(path).getFileName().toString():value.cache().metadata().name();int references=(int)entries.values().stream().filter(e->sourceKey(e.placement.source()).equals(path)).count();result.add(new ResourceView(path,name,references,value!=null?"":resourceFailures.getOrDefault(path,"加载中")));}return List.copyOf(result);}
    void loadResource(String filename){requireWorld();String key=sourceKey(filename);if(resources.containsKey(key)||resourceRequests.containsValue(key))return;UUID id=UUID.randomUUID();resourceRequests.put(id,key);resourceFailures.remove(key);queued.add(id);}
    private void cleanupTemporary(){temporarySources.cleanupAsync().whenComplete((v,e)->{if(e==null)cleanupWarning.set(false);else if(!cleanupWarning.getAndSet(true))BetterLitematicaClient.LOGGER.warn("Temporary source cleanup will be retried",e);});}
    private void removeTemporary(String key){temporarySources.remove(key);if(temporarySources.cleanupPending())cleanupTemporary();}
    void promoteTemporary(String source,String saved){
        requireWorld();if(!TemporarySources.temporary(source)||TemporarySources.temporary(saved))throw new IllegalArgumentException("投影来源无效");
        for(var entry:entries.values())if(entry.placement.source().equals(source)){var p=entry.placement;String name=p.name().endsWith(" · 临时")?p.name().substring(0,p.name().length()-5):p.name();entry.placement=p.source(saved).named(name.isBlank()?p.name():name);}
        unloadResource(source);reloadResource(saved);dirty=true;
    }
    void unloadResource(String filename){requireWorld();guardEditingSource(filename);String key=sourceKey(filename);cancelResourceProject(key);for(var entry:List.copyOf(entries.values()))if(sourceKey(entry.placement.source()).equals(key))unload(entry.placement.id());for(var request:List.copyOf(resourceRequests.entrySet()))if(request.getValue().equals(key)){if(request.getKey().equals(importing)){loader.cancel();importing=null;}queued.remove(request.getKey());resourceRequests.remove(request.getKey());}var value=resources.remove(key);if(value!=null)closeLoaded(value);resourceFailures.remove(key);removeTemporary(key);}
    void reloadResource(String filename){requireWorld();guardEditingSource(filename);String key=sourceKey(filename);cancelResourceProject(key);for(var request:List.copyOf(resourceRequests.entrySet()))if(request.getValue().equals(key)){if(request.getKey().equals(importing)){loader.cancel();importing=null;}queued.remove(request.getKey());resourceRequests.remove(request.getKey());}var value=resources.remove(key);if(value!=null)closeLoaded(value);for(var entry:entries.values())if(sourceKey(entry.placement.source()).equals(key)){cancelProjectActivation(entry.placement.id());if(entry.placement.id().equals(importing)){loader.cancel();importing=null;}queued.remove(entry.placement.id());entry.close();entry.state="Loading";queued.add(entry.placement.id());}cancelAnalysis();rebuildScene();loadResource(key);}
    void load(String filename) {
        requireWorld();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".litematic") && !lower.endsWith(".schem") && !lower.endsWith(".nbt") && !lower.endsWith(".schematic")) throw new IllegalArgumentException("请选择 .litematic、.schem、.schematic 或 .nbt 文件");
        BlockPos p = client.player.getBlockPos();
        String name = filename.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        if(TemporarySources.temporary(filename)){var resource=resources.get(sourceKey(filename));name=(resource==null?"选区":resource.cache().metadata().name())+" · 临时";}
        add(new Placement(UUID.randomUUID(), name.substring(0, Math.min(120, name.length())), filename,
            new PlacementTransform(new Vec3i(p.getX(), p.getY(), p.getZ()), 0, false, false), true, false,opacity));
    }
    private void add(Placement placement) {
        entries.put(placement.id(), new Entry(placement)); queued.add(placement.id()); selected = placement.id(); dirty = true;
    }
    void select(UUID id) { requireWorld(); if (id != null && !entries.containsKey(id)) throw new IllegalArgumentException("投影已移除"); selected = id; dirty = true; }
    void selectNumber(int number) {
        List<Placement> list = placements(); if (number < 1 || number > list.size()) throw new IllegalArgumentException("Invalid placement number"); select(list.get(number - 1).id());
    }
    void listPlacements() { int number = 0; for (Entry entry : entries.values()) report((++number) + ". " + (entry.placement.id().equals(selected) ? "* " : "") + entry.placement.name() + " | " + entry.state); }
    void duplicate() { add(require().placement.duplicate()); }
    void rename(String name) { update(p -> p.named(name)); }
    void toggleLock() { update(p -> p.locked(!p.locked())); }
    void toggle() { update(p -> p.enabled(!p.enabled())); }
    void toggle(UUID id) { requireWorld(); Entry entry=entries.get(id); if(entry==null)throw new IllegalArgumentException("投影已移除"); entry.placement=entry.placement.enabled(!entry.placement.enabled());cancelAnalysis();apply(entry);rebuildScene();dirty=true; }
    void toggleDisplay(UUID id){requireWorld();var e=entries.get(id);if(e==null)throw new IllegalStateException("投影已移除");boolean enabling=!e.placement.enabled();e.placement=enabling?e.placement.enabled(true).renderBlocks(true):e.placement.renderBlocks(!e.placement.renderBlocks());apply(e);if(enabling){cancelAnalysis();rebuildScene();}dirty=true;}
    void placementOpacity(UUID id,float value) { requireWorld(); Entry entry=entries.get(id);if(entry==null)throw new IllegalArgumentException("投影已移除");entry.placement=entry.placement.opacity(value);apply(entry);dirty=true; }
    void placementDisplayFilter(UUID id,BlockDisplayFilter filter){
        requireWorld();Entry entry=require(id);if(entry.placement.displayFilter().equals(filter))return;
        entry.placement=entry.placement.displayFilter(filter);apply(entry);rebuildScene();dirty=true;
    }
    void toggleRendering() { requireWorld(); rendering = !rendering; dirty = true; }
    void reload(){reloadResource(require().placement.source());}
    void unload() { unload(require().placement.id()); }
    void unload(UUID id) {
        guardEditing(id);
        cancelProjectActivation(id);
        requireWorld(); Entry entry=entries.get(id);if(entry==null)return;
        cancelAnalysis();
        if (id.equals(importing)) { loader.cancel(); importing = null; }
        queued.remove(id); entries.remove(id); entry.close();rebuildScene(); if(id.equals(selected))selected = entries.isEmpty() ? null : entries.keySet().iterator().next(); dirty = true;
    }
    void unloadAll() { requireWorld();ensureDraftReady();if(editor.target()!=null)guardEditing(editor.target()); release(); dirty = true; message = "All placements removed"; }
    private void update(UnaryOperator<Placement> change){cancelProjectActivation(selected);Entry entry=require();Placement next=change.apply(entry.placement);boolean geometry=!next.sameGeometry(entry.placement);if(geometry){if(entry.metadata!=null)new PlacementLayout(next,entry.metadata.regions());cancelAnalysis();}entry.placement=next;apply(entry);if(geometry)rebuildScene();dirty=true;}
    void pastePlacement(String text){Entry entry=require();if(entry.metadata==null)throw new IllegalStateException("投影加载中");var state=PlacementClipboard.decode(text).matching(entry.metadata.regions());update(state::apply);}
    List<Region> regions(UUID id){Entry e=entries.get(id);return e==null||e.metadata==null?List.of():e.metadata.regions();}
    void region(UUID id,String name,UnaryOperator<RegionPlacement> change){select(id);var region=regions(id).stream().filter(r->r.name().equals(name)).findFirst().orElseThrow(()->new IllegalStateException("子区域不存在"));update(p->p.region(region,change.apply(p.region(region))));}
    void resetRegion(UUID id,String name){var region=regions(id).stream().filter(r->r.name().equals(name)).findFirst().orElseThrow();region(id,name,p->RegionPlacement.original(region));}
    void axisLock(int axis){update(p->p.axes(p.lockedAxes()^(1<<axis)));}
    void toggleBlocks(){update(p->p.renderBlocks(!p.renderBlocks()));}
    void overlapNext(){update(p->p.overlap(ReplaceRule.values()[(p.overlapRule().ordinal()+1)%3]));}
    void here(){var placement=require().placement;var current=placement.transform().origin();var p=client.player.getBlockPos();int axes=placement.lockedAxes();move((axes&1)!=0?current.x():p.getX(),(axes&2)!=0?current.y():p.getY(),(axes&4)!=0?current.z():p.getZ());}
    void move(int x, int y, int z) { update(p -> p.placed(new PlacementTransform(new Vec3i(x, y, z), p.transform().quarterTurns(), p.transform().mirrorX(), p.transform().mirrorZ()))); }
    void rotate(int degrees) {
        if (Math.floorMod(degrees, 90) != 0) throw new IllegalArgumentException("旋转角度须为 90° 的倍数");
        update(p -> p.placed(new PlacementTransform(p.transform().origin(), degrees / 90, p.transform().mirrorX(), p.transform().mirrorZ())));
    }
    void rotateNext() { rotate((require().placement.transform().quarterTurns() + 1) * 90); }
    void mirror(String axis) {
        if (!List.of("none", "x", "z", "xz").contains(axis)) throw new IllegalArgumentException("Mirror must be none, x, z or xz");
        update(p -> p.placed(new PlacementTransform(p.transform().origin(), p.transform().quarterTurns(), axis.contains("x"), axis.contains("z"))));
    }
    WheelRenderMode wheelRenderMode(){return WheelRenderMode.selected(layer,options.followLayer,options.wheelRenderMode);}
    void explicitLayerMode(LayerRange.Mode mode){var next=WheelRenderMode.valueOf(mode.name());if(options.wheelRenderMode!=next){options.wheelRenderMode=next;saveOptions();}}
    void wheelRendering(WheelRenderMode mode,int first,int second){
        requireWorld();var next=mode.range(first,second,playerPosition().y());
        boolean settingsChanged=options.followLayer!=mode.follows()||options.wheelRenderMode!=mode;
        layer(next.axis(),next.min(),next.max());options.followLayer=mode.follows();options.wheelRenderMode=mode;
        if(settingsChanged)saveOptions();
    }
    void layer(LayerRange.Axis axis, int min, int max) { requireWorld();var next=new LayerRange(axis,min,max);if(next.equals(layer))return;cancelAnalysis();layer=next;for (Entry entry : entries.values()) apply(entry); dirty = true; }
    void cycleLayer(){cycleLayer(1);}
    void cycleLayer(int step){var mode=LayerRange.Mode.values()[Math.floorMod(layer.mode().ordinal()+step,LayerRange.Mode.values().length)];var p=playerPosition();int value=layer.min()==Integer.MIN_VALUE?layer.max()==Integer.MAX_VALUE?switch(layer.axis()){case X->p.x();case Y->p.y();case Z->p.z();}:layer.max():layer.min();var next=LayerRange.of(layer.axis(),mode,value,value);layer(next.axis(),next.min(),next.max());explicitLayerMode(mode);}
    void layerAtPlayer(){var p=playerPosition();int value=switch(layer.axis()){case X->p.x();case Y->p.y();case Z->p.z();};int last=layer.mode()==LayerRange.Mode.RANGE?Math.addExact(value,Math.subtractExact(layer.max(),layer.min())):value;var next=LayerRange.of(layer.axis(),layer.mode()==LayerRange.Mode.ALL?LayerRange.Mode.SINGLE:layer.mode(),value,last);layer(next.axis(),next.min(),next.max());}
    void allLayers() { layer(LayerRange.ALL.axis(), LayerRange.ALL.min(), LayerRange.ALL.max()); }
    void shiftLayer(int amount) {
        requireWorld();if(layer.mode()==LayerRange.Mode.ALL)layerAtPlayer();var next=layer.shifted(amount);layer(next.axis(),next.min(),next.max());
    }
    void opacity(float value) { requireWorld(); if (!Float.isFinite(value) || value < 0.05f || value > 1) throw new IllegalArgumentException("Opacity must be 0.05-1"); opacity = value; for (Entry entry : entries.values()) { entry.placement=entry.placement.opacity(value);apply(entry); } dirty = true; }
    void info() {
        Entry entry = require(); report(entry.placement.toString()); report(status());
        if (entry.metadata != null) for (String warning : entry.metadata.warnings()) report(warning);
    }
    void blockCounts() {
        Entry entry = require(); if (entry.counts == null) throw new IllegalStateException("Blueprint is not ready");
        List<Integer> ids = new ArrayList<>(); for (int i = 0; i < entry.counts.length; i++) if (entry.counts[i] > 0 && !entry.metadata.palette().get(i).isAir()) ids.add(i);
        ids.sort(Comparator.comparingLong((Integer id) -> entry.counts[id]).reversed());
        report("Top block-state counts (NOT item requirements):");
        for (int id : ids.subList(0, Math.min(10, ids.size()))) report(entry.counts[id] + " x " + entry.metadata.palette().get(id));
    }
    private void save() {
        editor.checkpoint();
        if(selectionDirty&&selectionRestoring==null&&selectionFile!=null&&selectionWritable){
            AreaSelection snapshot=selection;Path file=selectionFile;submit("选区保存",()->{SelectionStore.write(file,snapshot);return "选区已保存";});selectionDirty=false;
        }
        if (!dirty || !ready || sessionFile == null || !writable || !draftAdoptions.isEmpty()) return;
        var persistent=placements().stream().map(p->draftAdoptions.getOrDefault(p.id(),p)).filter(p->!TemporarySources.temporary(p.source())).toList();UUID savedSelection=persistent.stream().anyMatch(p->p.id().equals(selected))?selected:null;io.save(sessionFile, new PlacementSession(persistent, savedSelection, layer, opacity, rendering)); dirty = false;
    }
    private void release() {
        nearbyHighlights.clear();
        renderChanges.clear();
        editor.reset();draftAdoptions.clear();draftReading=null;draftRecovering=null;draftArchiving=null;recoveringDraft=null;draftError="";draftRetrySource=false;if(draftFile!=null)draftWriter.release(draftFile);draftFile=null;checkpointRevision=-1;checkpointPlacement=null;checkpointFailure="";
        cancelProjectActivation(null);
        printer.stop();
        cancelAnalysis(); analysis = null;
        loader.cancel(); importing = null; queued.clear();resourceRequests.clear();resourceFailures.clear();for(var resource:resources.values())closeLoaded(resource);resources.clear(); for (Entry entry : entries.values()) entry.close(); entries.clear();scene=new ProjectionScene(List.of()); selected = null;
        temporarySources.clear();if(temporarySources.cleanupPending())cleanupTemporary();
    }
    void disconnect() {
        ProjectionOverlays.clearCaches();
        bedrock.disconnect();
        NativeMiner.disconnectAll();
        filePreviews.cancelAll();
        tool.clear();toolWorld.cancel();
        for(var id:List.copyOf(draftAdoptions.keySet()))draftLoadFailed(id);
        sessionEpoch++;ProjectionInfoData.clear();AccuratePlacement.disconnect();InventoryReceipts.clear();inventoryTransfers.clear();
        if(previewCapture!=null)previewCapture.cancel(false);previewCapture=null;
        composite.close();
        if(projectLoading!=null)projectLoading.cancel(true);projectLoading=null;
        if(commands!=null)commands.cancel();if(commandsLoading!=null)commandsLoading.cancel(true);commands=null;commandsLoading=null;
        cancelPaste();
        creativeJob=null;creativeResult="";creativePauseRequested=false;
        commandPlacement=null;commandLayer=null;commandRule=null;commandOutput=null;commandSettings=null;commandNbt=false;commandEntities=false;
        var writing=serverFill.getAndSet(null);if(writing!=null)writing.cancel();fillStarting=null;fill=null;
        cancelCapture();
        save(); restoring = null; release(); ready = false; writable = true; dirty = false; sessionFile = null; lastWorld = null;
        editingJobs.clear();selection=AreaSelection.EMPTY;selectionTarget=null;selectionFile=null;selectionRestoring=null;selectionDirty=false;selectionWritable=true;
        layer = LayerRange.ALL; opacity = 0.45f; rendering = true; actionError = ""; cachedHud = "";
    }
    void resourcesReloaded() { for (Entry entry : entries.values()) if (entry.renderer != null) entry.renderer.invalidate(); }
    void report(String text) { message = text == null ? "Unknown error" : text; BetterLitematicaClient.LOGGER.info(message); if (client.player != null) client.player.sendMessage(Text.literal("[BetterLitematica] " + message), false); }
    String actionError(){return actionError;}
    long sessionEpoch(){return sessionEpoch;}
    private void fail(String text){actionError=text;report(text);}
    int action(Runnable task) { try { actionError = "";task.run();return 1; } catch (RuntimeException e) {fail("操作失败：" + e.getMessage());return 0; } }
    @Override public void close() {toolWorld.close();printer.close(); composite.close();disconnect(); releaseGpu(Integer.MAX_VALUE,Long.MAX_VALUE);loader.close();cleanupEditorBaselines();filePreviews.close();io.close();for(var job:editorBaselines)if(job.abandoned&&job.result.isDone()&&!job.result.isCompletedExceptionally())try{Files.deleteIfExists(job.path);}catch(IOException failure){BetterLitematicaClient.LOGGER.error("临时底本清理失败",failure);}try{draftWriter.close();}catch(IOException failure){BetterLitematicaClient.LOGGER.error("草稿保存未完成",failure);}temporarySources.close(); String error = io.takeError(); if (error != null) BetterLitematicaClient.LOGGER.error(error); }
}
