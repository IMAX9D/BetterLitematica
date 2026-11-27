package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.*;

final class PrinterEngine implements AutoCloseable {
    enum State { STOPPED,RUNNING,PAUSED }
    private record Context(ClientWorld world,List<ProjectionController.PrinterSource> sources,LayerRange layer,AreaSelection selection,long revision) {}
    private final MinecraftClient client;private final ProjectionController controller;private final PrinterActions actions;
    private final java.util.function.IntPredicate readyMaterial;
    private final PrinterContainers containers;
    private final PrinterSigns signs;
    private final NearbyBuildHud nearby;
    private final PrinterQueue queue=new PrinterQueue(4096);
    private final PrinterDiscovery.Buffer discovery=new PrinterDiscovery.Buffer();
    private final PrinterMaterialBatch materialBatch=new PrinterMaterialBatch();
    boolean materialBatchEnabled(){return PrinterMaterialBatch.enabled(settings());}
    boolean materialBatchPending(){return materialBatchEnabled()&&materialBatch.pending();}
    long materialBatchConfirmed(int material){return materialBatch.confirmedUnits(material);}
    private final ArrayDeque<CompletableFuture<PrinterDiscovery.Result>> searches=new ArrayDeque<>();
    private final PrinterPacing pacing=new PrinterPacing();
    private final ShortageTracker<Item> missing=new ShortageTracker<>(128);
    private int feedbackTick;
    record Missing(Item item,int required){}
    private int materialNeed(Item item){
        if(item==net.minecraft.item.Items.ICE&&settings().waterToIce){int total=nearby.materialNeeds().getOrDefault(item,0);for(var entry:nearby.snapshot().entries())if(entry.block()==Blocks.WATER)total+=entry.count();return total;}
        return nearby.materialNeeds().getOrDefault(item,0);
    }
    List<Missing> missingMaterials(){return missing.items().stream().map(item->new Missing(item,materialNeed(item))).toList();}
    int progressPercent(){if(!settings().print&&!mining(settings()))return -1;int compared=nearby.compared();return compared==0?-1:(int)Math.min(nearby.completeScan()?100:99,100L*nearby.matched()/compared);}
    boolean progressEstimated(){return !nearby.completeScan();}
    void feedbackChanged(BlockPos pos){nearby.changed(pos);}
    void feedbackChunkChanged(int x,int z){nearby.chunkChanged(x,z);}
    MaterialStock stock(){return actions.stock();}
    private void recordMissing(Item item){if(item!=null&&item!=net.minecraft.item.Items.AIR)missing.failed(item);}
    private void refreshMissing(){
        feedbackTick++;stock().refresh(feedbackTick,settings().supply);
        if(feedbackTick%4==0)missing.refresh(item->stock().available(item)>0,item->materialNeed(item)>0,nearby.completeScan());
    }
    private final ActionHighlights highlights=new ActionHighlights(256);
    private ThreadPoolExecutor workers;private int workerCount;
    private State state=State.STOPPED;private Context context;private long generation;private boolean startupBurst,coldBurstUsed;
    private final PrinterRetryWindow retryWindow=new PrinterRetryWindow();
    private PrinterMovementScan scan;private Vec3i center;private PrinterQueue.Job waiting;private long completedRounds;
    private java.util.function.Function<Vec3i,ProjectionController.PrinterSample> sampler;
    private int ticks,roundCompared,roundMatched,roundUnknown,lastCompared,lastMatched,lastUnknown;
    private BlockState fillState;
    private long operations;private PrinterReason status=PrinterReason.STOPPED;
    private PrinterQueue.Job deferredJob;private PrinterReason deferredReason=PrinterReason.NONE;private int deferredUntil;private boolean deferredMissing;
    PrinterEngine(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;nearby=new NearbyBuildHud(client,controller);containers=new PrinterContainers(client,controller);signs=new PrinterSigns(client,controller);actions=new PrinterActions(client,controller.inventoryTransfers(),()->controller.options().accurate,this::externalAllowed,containers,signs,controller);readyMaterial=actions::materialAvailable;BackgroundPrinting.bind(this::running);}
    private boolean externalAllowed(){return running()&&client.world!=null&&client.player!=null&&!client.player.isDead()&&!controller.worldWriteBusy()&&Objects.equals(context,current());}
    State state(){return state;}boolean acting(){return actions.acting()||containers.acting();}boolean running(){return state==State.RUNNING;}
    boolean ownsBreaking(){return running()&&actions.breaking();}
    long operations(){return operations;}int queued(){return queue.size()+(waiting==null?0:1);}
    String status(){return status.description();} PrinterReason reason(){return status;}
    String progress(){return nearby.compared()==0?"":nearby.matched()+" / "+nearby.compared()+(nearby.snapshot().unknown()>0?" · 待加载 "+nearby.snapshot().unknown():"");}
    List<String> hud(){
        if(state==State.STOPPED||!settings().hud)return List.of();var lines=new ArrayList<String>();lines.add("打印机 · "+(context==null?0:context.sources().size())+" 个投影 · "+status.description()+" · 操作 "+operations);
        if(settings().missingHud)missing.items().stream().limit(4).forEach(item->lines.add("缺少 "+item.getName().getString()));return lines;
    }
    NearbyBuildHud.Snapshot nearbyHud(){return settings().missingHud?nearby.snapshot():NearbyBuildHud.Snapshot.EMPTY;}
    private PrinterSettings settings(){return controller.options().printer;}
    private Context current(){return new Context(client.world,controller.printerSources(),controller.layerRange(),controller.selection(),settings().revision);}
    static boolean mining(PrinterSettings s){return s.breakWrong||s.breakExtra||s.breakState;}
    private boolean selectionWork(PrinterSettings s){return selectionWork(s,!controller.selection().boxes().isEmpty());}
    static boolean selectionWork(PrinterSettings s,boolean selected){return selected&&(s.bedrock&&s.bedrockScope!=PrinterSettings.Scope.PROJECTION||s.fill&&s.fillScope!=PrinterSettings.Scope.PROJECTION||s.fluid&&s.fluidScope!=PrinterSettings.Scope.PROJECTION); }
    boolean hasWorkArea(PrinterSettings s){return !controller.printerPlacements().isEmpty()||selectionWork(s);}
    void start(){
        if(controller.editor().active()||controller.editor().busy())throw new IllegalStateException("请先暂停投影编辑");
        if(client.world==null||client.player==null||client.interactionManager==null)throw new IllegalStateException("请先进入世界");
        if(client.player.isSpectator())throw new IllegalStateException("旁观模式不能施工");
        if(settings().bedrock&&settings().bedrockScope!=PrinterSettings.Scope.PROJECTION&&controller.selection().boxes().isEmpty())throw new IllegalStateException("请先创建破基岩选区，或将破基岩范围改为投影");
        if(!hasWorkArea(settings()))throw new IllegalStateException("请先启用投影，或为选区施工创建选区");
        if(controller.worldWriteBusy())throw new IllegalStateException("请先结束创造粘贴或填充任务");
        settings().validate();if(!settings().print&&!mining(settings())&&!settings().fill&&!settings().fluid&&!settings().bedrock)throw new IllegalStateException("请选择工作模式");
        fillState=checkedFill(settings());
        if(settings().bedrock)actions.checkMiner();
        if(controller.bedrock().enabled())controller.bedrock().pause();
        if(state==State.STOPPED)operations=0;
        Context next=current();if(context==null||!context.equals(next)){clearWork();context=next;}
        startupBurst=state!=State.RUNNING&&!coldBurstUsed;state=State.RUNNING;status=PrinterReason.of(PrinterReason.Id.RUNNING,"打印中");
    }
    void pause(String description){pause(PrinterReason.of(PrinterReason.Id.PAUSED,description==null?"已暂停":description));}
    private void pause(RuntimeException failure){pause(PrinterReason.failure(failure));}
    void pause(PrinterReason reason){containers.pause();if(state==State.RUNNING){state=State.PAUSED;status=reason;actions.diagnostics.reset();actions.reset();if(!actions.cleanupError().isEmpty())status=PrinterReason.of(PrinterReason.Id.ERROR,actions.cleanupError());waiting=null;}}
    void sourceChanged(){pause("投影编辑");clearWork();context=null;nearby.clear();}
    void stop(){actions.interactions.clear();state=State.STOPPED;startupBurst=false;status=PrinterReason.of(PrinterReason.Id.STOPPED,"已停止");clearWork();if(!actions.cleanupError().isEmpty())status=PrinterReason.of(PrinterReason.Id.ERROR,actions.cleanupError());highlights.clear();context=null;}
    static BlockState checkedFill(PrinterSettings settings){var resolver=new StateResolver1201(List.of(BlockStateSpec.parse(settings.fillState)),new PlacementTransform(Vec3i.ZERO,0,false,false));if(resolver.unresolved(0))throw new IllegalArgumentException("填充方块或状态不存在");var state=resolver.resolve(0);if((settings.fill||settings.fluid)&&(state.isAir()||!(state.getBlock().asItem() instanceof net.minecraft.item.BlockItem)))throw new IllegalArgumentException("请选择可放置的填充方块");return state;}
    void configure(PrinterSettings next){
        next.validate();var fill=checkedFill(next);if(settings().snapshot().equals(next.snapshot()))return;
        next.revision=settings().revision+1;controller.options().printer=next;fillState=fill;pause("设置已更改");clearWork();context=state==State.STOPPED?null:current();controller.saveOptions();
    }
    void toggle(){if(running())pause("已暂停");else start();}
    void cycle(){var s=PrinterSettings.read(settings().snapshot());var modes=WheelModes.values();int current=-1;for(int i=0;i<modes.length;i++)if(modes[i].enabled(s)){current=i;break;}s.print=s.fill=s.fluid=s.bedrock=s.breakWrong=s.breakExtra=s.breakState=false;configure(WheelModes.toggled(s,modes[(current+1)%modes.length]));announceModes();}
    void allModesOff(){var s=PrinterSettings.read(settings().snapshot());s.print=s.fill=s.fluid=s.bedrock=s.breakWrong=s.breakExtra=s.breakState=false;configure(s);stop();controller.bedrock().pause();announceModes();}
    void announceModes(){if(client.player!=null){String labels=java.util.Arrays.stream(WheelModes.values()).filter(m->m.enabled(settings())).map(WheelModes::label).collect(java.util.stream.Collectors.joining("、"));client.player.sendMessage(net.minecraft.text.Text.literal(labels.isEmpty()?"全部施工模式已关闭":"施工模式："+labels+" · 按施工开关键继续"),true);}}
    void supplyOpened(int sync,net.minecraft.screen.ScreenHandlerType<?> type){try{containers.opened(sync,type);actions.supplyOpened(sync,type);}catch(RuntimeException e){pause(e);}}
    void containerOpening(net.minecraft.screen.ScreenHandlerType<?> type){containers.opening(type);}
    void supplyInventory(int sync){actions.supplyInventory(sync);}
    void manualInventory(){if(acting())return;try{containers.manual();actions.manualInventory();controller.inventoryTransfers().reset();}catch(RuntimeException e){pause(e);}}
    void confirmed(BlockPos pos,BlockState value){
        if(materialBatchEnabled())materialBatch.confirmed(pos,value,ticks);
        if(!retryWindow.empty()){
            int x=pos.getX(),y=pos.getY(),z=pos.getZ();releaseRetry(pos.asLong());
            releaseRetry(BlockPos.asLong(x+1,y,z));releaseRetry(BlockPos.asLong(x-1,y,z));
            releaseRetry(BlockPos.asLong(x,y+1,z));releaseRetry(BlockPos.asLong(x,y-1,z));
            releaseRetry(BlockPos.asLong(x,y,z+1));releaseRetry(BlockPos.asLong(x,y,z-1));
        }
        actions.confirmed(pos,value);containers.changed(pos,value);}
    private void releaseRetry(long pos){if(retryWindow.forget(pos))pacing.defer(pos,0);}
    void containerInventory(net.minecraft.network.packet.s2c.play.InventoryS2CPacket packet){containers.inventory(packet);}
    void containerSlot(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket packet){containers.slot(packet);}
    void containerCursor(net.minecraft.item.ItemStack stack){containers.cursor(stack);}
    void containerPlayer(int slot,net.minecraft.item.ItemStack stack){containers.player(slot,stack);}
    void manualContainerInteraction(){if(containers.active()&&!acting()){containers.manualRequest();}}
    boolean signOpened(net.minecraft.block.entity.SignBlockEntity sign,boolean front){return signs.opened(sign,front);}
    void manualSignInteraction(BlockPos pos){if(!acting())signs.manual(pos);}
    Collection<ActionHighlights.Mark> actionMarks(long now){return highlights.live(now);}
    void render(ProjectionRenderContext context){if(settings().highlights)ProjectionOverlays.actions(client,context,highlights.live(System.nanoTime()),settings());}
    private void clearWork(){materialBatch.reset();retryWindow.clear();deferredJob=null;deferredReason=PrinterReason.NONE;containers.clear();generation++;for(var future:searches)future.cancel(true);searches.clear();if(workers!=null)workers.getQueue().clear();queue.clear();pacing.reset();completedRounds=0;sampler=null;missing.clear();scan=null;center=null;waiting=null;actions.diagnostics.reset();roundCompared=roundMatched=roundUnknown=lastCompared=lastMatched=lastUnknown=0;actions.reset();}
    private void pool(){int count=settings().threads;if(workers!=null&&count==workerCount)return;if(workers!=null)workers.shutdownNow();workerCount=count;workers=new ThreadPoolExecutor(count,count,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(count),r->{Thread t=new Thread(r,"betterlitematica-printer-search");t.setDaemon(true);return t;});}
    void tickHud(){nearby.tick();refreshMissing();}
    void tick(){
        ticks++;signs.tick();
        if(client.world==null||client.player==null||client.interactionManager==null){if(state!=State.STOPPED)stop();containers.tick(ticks,false);return;}
        if(state==State.STOPPED){containers.tick(ticks,false);return;}
        if(state==State.PAUSED&&status.id()==PrinterReason.Id.RETURN_TO_GAME)status=PrinterReason.PAUSED;
        if(context!=null&&context.world()!=client.world){stop();containers.tick(ticks,false);return;}
        Context next=current();
        if(!Objects.equals(context,next)){clearWork();context=next;}
        boolean canFill=running()&&settings().containerFill&&settings().print&&client.currentScreen==null
            &&!client.player.isSpectator()&&!client.player.isDead()&&!controller.worldWriteBusy();
        if(containers.tick(ticks,canFill)){if(running())status=containers.typedReason();return;}
        if(!running())return;
        if(actions.interactions.waiting()){status=PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待异常交互确认");prefetchWhileWaiting();return;}
        try{if(actions.supplyTick(ticks)){status=actions.typedReason();prefetchWhileWaiting();return;}}catch(RuntimeException e){pause(e);return;}
        if(client.player.isSpectator()||client.player.isDead()||controller.worldWriteBusy()){pause("已暂停");return;}
        if(context.sources().isEmpty()&&!selectionWork(settings())){status=PrinterReason.of(PrinterReason.Id.NO_PROJECTION,"等待启用投影");return;}
        if(client.player.isUsingItem()){status=PrinterReason.of(PrinterReason.Id.USING_ITEM,"等待物品使用结束");return;}
        try{
            actions.diagnostics.begin();
            var settings=settings();pacing.begin(ticks,settings.interval,settings.perTick,settings.cooldown,settings.breakInterval,settings.breakPerTick);
            int budgetMillis=startupBurst&&PrinterColdWarmup.ready()&&settings.workBudgetMillis==16&&settings.interval==0&&settings.perTick==0&&settings.cooldown==0?32:settings.workBudgetMillis;
            if(startupBurst)coldBurstUsed=true;
            startupBurst=false;
            var budget=new PrinterWorkBudget(System.nanoTime(),budgetMillis);actions.prepare(ticks);pool();sampler=controller.printerSampler();
            updateCenter();collect();
            while(budget.hasTime(System.nanoTime())&&pacing.canRun(false)){
                if(waiting==null&&queue.size()==0&&!actions.hasIceWork()){collect();if(queue.size()==0&&!snapshot(budget,true))break;}
                if(consume(budget))break;
            }

            if(waiting!=null||queue.size()<512||!pacing.canRun(false))snapshot(budget,false);
            if(!budget.hasTime(System.nanoTime()))actions.diagnostics.budgetStops++;
            if(ticks%20==0)pacing.expire();
            if(settings.containerFill&&settings.print&&waiting==null&&queue.size()==0&&!controller.inventoryTransfers().inFlight()
                &&budget.hasTime(System.nanoTime())&&containers.startNext(ticks))status=containers.typedReason();
            var containerMissing=containers.takeMissing();if(!containerMissing.isEmpty())recordMissing(containerMissing.getItem());
        }catch(RuntimeException e){pause(e);BetterLitematicaClient.LOGGER.error("Printer paused",e);}finally{sampler=null;actions.diagnostics.finish(settings(),queued(),actions.reason());}
    }
    private void collect(){
        for(var it=searches.iterator();it.hasNext();){var future=it.next();if(!future.isDone())continue;it.remove();if(future.isCancelled())continue;var result=future.join();if(result.generation()!=generation)continue;
            accept(result);
        }
        finishRound();
    }
    private void accept(PrinterDiscovery.Result result){
        if(result.generation()!=generation)return;
        roundCompared+=result.compared();roundMatched+=result.matched();roundUnknown+=result.unknown();actions.diagnostics.discovered+=result.jobs().size();actions.diagnostics.unknown+=result.unknown();
        var s=settings();for(var job:result.jobs())if(!pacing.cooling(job.position(),ticks)&&withinRange(BlockPos.fromLong(job.position()),s))queue.offer(job,actions.material(job,s));
    }
    private void finishRound(){
        if(scan!=null&&scan.completedRounds()!=completedRounds&&searches.isEmpty()){lastCompared=roundCompared;lastMatched=roundMatched;lastUnknown=roundUnknown;roundCompared=roundMatched=roundUnknown=0;completedRounds=scan.completedRounds();}
    }
    private boolean scope(Vec3i pos,PrinterSettings.Scope scope,boolean inProjection){
        if(!controller.layerRange().contains(pos))return false;
        if(scope==PrinterSettings.Scope.PROJECTION)return inProjection;
        boolean inSelection=false;for(var box:controller.selection().boxes()){var a=box.first();var b=box.second();if(pos.x()>=Math.min(a.x(),b.x())&&pos.x()<=Math.max(a.x(),b.x())&&pos.y()>=Math.min(a.y(),b.y())&&pos.y()<=Math.max(a.y(),b.y())&&pos.z()>=Math.min(a.z(),b.z())&&pos.z()<=Math.max(a.z(),b.z())){inSelection=true;break;}}
        return inSelection&&(scope!=PrinterSettings.Scope.BELOW||pos.y()<client.player.getY())&&(scope!=PrinterSettings.Scope.ABOVE||pos.y()>client.player.getY());
    }

    private void prefetchWhileWaiting(){
        if(!running()||client.player.isSpectator()||client.player.isDead()||controller.worldWriteBusy()||queue.size()>=512)return;
        var budget=new PrinterWorkBudget(System.nanoTime(),2);pool();sampler=controller.printerSampler();updateCenter();collect();
        while(queue.size()<512&&budget.hasTime(System.nanoTime())&&snapshot(budget,true)){}
    }
    private Vec3i eyeCenter(){var eye=client.player.getEyePos();return new Vec3i((int)Math.round(eye.x),(int)Math.round(eye.y),(int)Math.round(eye.z));}
    private PlacementBounds scanBounds(List<ProjectionController.PrinterSource> sources,LayerRange layer){
        PlacementBounds envelope=null;
        for(var source:sources){
            if(source.renderer()==null)return null;
            var bounds=source.renderer().layout().enabledBounds();
            if(bounds!=null)envelope=envelope==null?bounds:PlacementLayout.union(envelope,bounds);
        }
        if(envelope==null)return null;
        var min=envelope.min();var max=envelope.max();
        int[] lo={min.x(),min.y(),min.z()},hi={max.x(),max.y(),max.z()};
        int axis=layer.axis().ordinal();
        lo[axis]=Math.max(lo[axis],layer.min());
        hi[axis]=Math.min(hi[axis],layer.max());
        return lo[axis]>hi[axis]?null:new PlacementBounds(new Vec3i(lo[0],lo[1],lo[2]),new Vec3i(hi[0],hi[1],hi[2]));
    }
    private void updateCenter(){
        center=eyeCenter();var s=settings();double radius=s.shape==PrinterRange.Shape.SPHERE?PrinterReach.candidateRadius(range(s)):range(s);
        if(scan==null||scan.radius()!=radius){
            boolean projectionOnly=!s.fill&&!s.fluid&&!s.bedrock&&s.printScope==PrinterSettings.Scope.PROJECTION;
            var sources=controller.printerSources();var layer=controller.layerRange();
            scan=new PrinterMovementScan(center,radius,s.shape,s.order,s.reverseX,s.reverseY,s.reverseZ,projectionOnly?scanBounds(sources,layer):null);completedRounds=0;
            if(projectionOnly){
                scan.domain(box->{
                    int axis=layer.axis().ordinal();int min=axis==0?box.min().x():axis==1?box.min().y():box.min().z(),max=axis==0?box.max().x():axis==1?box.max().y():box.max().z();
                    if(max<layer.min()||min>layer.max())return false;
                    for(var source:sources)if(source.renderer()==null||source.renderer().layout().intersects(box))return true;
                    return false;
                });
            }
        }
        scan.beginTick(center,ticks);
    }
    private double range(PrinterSettings s){double reach=client.player.getBlockInteractionRange();return s.range==0?reach:Math.min(reach,s.range);}
    private boolean withinRange(BlockPos pos,PrinterSettings s){
        if(s.shape==PrinterRange.Shape.SPHERE)return PrinterReach.distanceSquared(client.player.getEyePos(),pos)<=range(s)*range(s);
        return PrinterRange.contains(new Vec3i(pos.getX(),pos.getY(),pos.getZ()),center==null?eyeCenter():center,range(s),s.shape);
    }
    private boolean snapshot(PrinterWorkBudget budget,boolean urgent){
        long started=System.nanoTime();try{return snapshotInternal(budget,urgent);}finally{actions.diagnostics.scanNanos+=System.nanoTime()-started;}
    }
    private boolean snapshotInternal(PrinterWorkBudget budget,boolean urgent){
        var s=settings();
        if((!urgent&&searches.size()>=workerCount*2)||queue.remaining()<2048||!budget.scan(System.nanoTime()))return false;
        boolean batching=materialBatchEnabled();
        if(batching){
            materialBatch.ensure(context.sources(),center,range(s),context.layer(),actions,s,ticks);
            if(!materialBatch.ready(ticks)){
                if(waiting==null&&queue.size()==0&&searches.isEmpty()&&!actions.hasIceWork()&&!controller.inventoryTransfers().inFlight())materialBatch.drained(ticks);
                if(waiting==null&&queue.size()==0)status=materialBatch.pending()?PrinterReason.of(PrinterReason.Id.LOADING,materialBatch.status()):idleReason(s);
                return false;
            }
        }else if(!scan.hasNext())return false;
        long[] positions=discovery.positions;
        int[] expected=discovery.expected,actual=discovery.actual,flags=discovery.flags,scopes=discovery.scopes,fill=discovery.fill;
        int n=0,visited=0,lastChunkX=Integer.MIN_VALUE,lastChunkZ=Integer.MIN_VALUE;
        boolean chunkLoaded=false;
        long started=System.nanoTime();
        var eye=client.player.getEyePos();double rangeSquared=range(s)*range(s);
        int fillId=PrinterRules.forbiddenPrint(fillState)?-1:Block.getRawIdFromState(fillState);
        boolean accurateOpen=AccuratePlacement.resolve(client,controller.options().accurate)==AccuratePlacement.Mode.V3;
        while((batching?materialBatch.ready(ticks):scan.hasNext())&&n<512&&visited++<2048&&budget.hasTime(System.nanoTime())&&System.nanoTime()-started<1_000_000L){
            var at=batching?materialBatch.next():scan.next();if(at==null)continue;var pos=new BlockPos(at.x(),at.y(),at.z());if(s.shape==PrinterRange.Shape.SPHERE&&PrinterReach.distanceSquared(eye,pos)>rangeSquared||client.world.isOutOfHeightLimit(pos)||!client.world.getWorldBorder().contains(pos))continue;
            var sample=sampler.apply(at);if(sample==null)continue;boolean in=sample.inside();int scope=0;
            if((s.print||mining(s))&&scope(at,s.printScope,in)&&in)scope|=1;if(s.fill&&scope(at,s.fillScope,in))scope|=2;if(s.fluid&&scope(at,s.fluidScope,in))scope|=4;if(s.bedrock&&scope(at,s.bedrockScope,in))scope|=8;
            if(scope==0)continue;int i=n++;positions[i]=pos.asLong();scopes[i]=scope;fill[i]=fillId;
            boolean needsProjection=(scope&1)!=0||((scope&8)!=0&&s.bedrockScope==PrinterSettings.Scope.PROJECTION);
            int chunkX=at.x()>>4,chunkZ=at.z()>>4;
            if(chunkX!=lastChunkX||chunkZ!=lastChunkZ){
                chunkLoaded=WorldChunks.loaded(client.world,chunkX,chunkZ);
                lastChunkX=chunkX;lastChunkZ=chunkZ;
            }
            if(!chunkLoaded||needsProjection&&sample!=null&&in&&sample.state()==null){flags[i]=0;continue;}
            BlockState current=client.world.getBlockState(pos),wanted=in&&sample.state()!=null?sample.state():Blocks.AIR.getDefaultState();

            if(batching&&PrinterMaterialBatch.key(wanted,s)!=materialBatch.material()){n--;continue;}
            if(s.containerFill&&s.print&&(scope&1)!=0&&!PrinterRules.forbiddenPrint(wanted)&&!PrinterRules.filtered(wanted,s.skip)
                &&!(s.skipWaterlogged&&wanted.contains(net.minecraft.state.property.Properties.WATERLOGGED)&&wanted.get(net.minecraft.state.property.Properties.WATERLOGGED)))containers.observe(pos,wanted,current,ticks);
            boolean matches=PrinterRules.constructionMatches(current,wanted);
            if((s.print||mining(s))&&!s.fill&&!s.fluid&&!s.bedrock&&matches){

                n--;
                if((scope&1)!=0){roundCompared++;roundMatched++;}
                continue;
            }
            actual[i]=Block.getRawIdFromState(current);expected[i]=matches?actual[i]:Block.getRawIdFromState(wanted);
            int f=PrinterDiscovery.KNOWN;if(wanted.isAir())f|=PrinterDiscovery.WANTED_AIR;if(current.isAir())f|=PrinterDiscovery.ACTUAL_AIR;
            if(current.isAir()||current.isReplaceable()&&s.replace&&PrinterRules.filtered(current,s.replaceable))f|=PrinterDiscovery.REPLACEABLE;
            if(PrinterRules.sameBlock(current,wanted))f|=PrinterDiscovery.SAME_BLOCK;
            if(PrinterRules.adjustable(current,wanted,s))f|=PrinterDiscovery.ADJUSTABLE;
            if(s.fluid){var fluid=current.getFluidState();if(!fluid.isEmpty()&&PrinterRules.fluidMatches(Registries.FLUID.getId(fluid.getFluid()).toString(),s.fluids))f|=PrinterDiscovery.FLUID;if(fluid.isStill())f|=PrinterDiscovery.FLUID_SOURCE;}
            if(s.bedrock&&(s.bedrockScope!=PrinterSettings.Scope.PROJECTION||current!=wanted)&&controller.bedrock().accepts(pos))f|=PrinterDiscovery.BEDROCK;
            if(PrinterRules.forbiddenPrint(wanted)||PrinterRules.filtered(wanted,s.skip)||s.skipWaterlogged&&wanted.contains(net.minecraft.state.property.Properties.WATERLOGGED)&&wanted.get(net.minecraft.state.property.Properties.WATERLOGGED))f|=PrinterDiscovery.SKIP;
            if(s.coralSubstitute&&actions.waitingCoral(pos,current,wanted,ticks))f|=PrinterDiscovery.SKIP;
            if(PrinterRules.redstoneOnlyDifference(current,wanted,accurateOpen))f|=PrinterDiscovery.SKIP;
            flags[i]=f;
        }
        if(n==0){finishRound();if(waiting==null&&queue.size()==0&&searches.isEmpty())status=idleReason(s);return true;}
        var policy=new PrinterDiscovery.Policy(s.print,s.fill,s.fluid,s.bedrock,s.breakWrong,s.breakExtra,s.breakState,s.flowing);
        if(urgent){accept(discovery.searchNow(generation,n,policy));finishRound();}
        else {
            var page=discovery.freeze(generation,n);
            searches.add(CompletableFuture.supplyAsync(()->PrinterDiscovery.search(page,policy),workers));
        }
        return true;
    }
    private boolean valid(PrinterQueue.Job job){
        if(job.generation()!=generation||actions.interactions.blocked(Block.getStateFromRawId(job.expected()).getBlock().asItem()))return false;var pos=BlockPos.fromLong(job.position());var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());
        var s=settings();if(!withinRange(pos,s))return false;
        if(!WorldChunks.loaded(client.world,pos)||client.world.isOutOfHeightLimit(pos)||!client.world.getWorldBorder().contains(pos))return false;
        if(Block.getRawIdFromState(client.world.getBlockState(pos))!=job.observed()&&!actions.owns(job))return false;
        if(actions.ownsIce(job))return true;
        var sample=sampler.apply(at);if(sample==null)return false;boolean in=sample.inside();
        return switch(job.kind()){
            case BREAK->mining(s)&&scope(at,s.printScope,in)&&sample!=null&&sample.state()!=null&&Block.getRawIdFromState(sample.state())==job.expected();
            case PLACE,ADJUST->s.print&&scope(at,s.printScope,in)&&sample!=null&&sample.state()!=null&&Block.getRawIdFromState(sample.state())==job.expected();
            case FILL->s.fill&&scope(at,s.fillScope,in);
            case FLUID->s.fluid&&scope(at,s.fluidScope,in);
            case BEDROCK->s.bedrock&&scope(at,s.bedrockScope,in);
        };
    }
    List<PrinterQueue.Job> iceCandidates(PrinterQueue.Job first,int limit){
        var result=new ArrayList<PrinterQueue.Job>();result.add(first);var s=settings();
        var candidates=new ArrayList<PrinterQueue.Job>(queue.snapshot(Registries.ITEM.getRawId(net.minecraft.item.Items.ICE),128));candidates.addAll(queue.snapshot(-1,128));
        for(var job:candidates){
            if(result.size()>=limit)break;if(job.position()==first.position()||(job.kind()!=PrinterQueue.Kind.PLACE&&job.kind()!=PrinterQueue.Kind.ADJUST)||pacing.cooling(job.position())||!valid(job))continue;
            var pos=BlockPos.fromLong(job.position());if(actions.iceTarget(client.world.getBlockState(pos),Block.getStateFromRawId(job.expected()),s))result.add(job);
        }return result;
    }

    private void deferred(PrinterQueue.Job job,PrinterReason reason,boolean material){status=reason;deferredJob=job;deferredReason=reason;deferredMissing=material;deferredUntil=ticks+20;}
    private PrinterReason idleReason(PrinterSettings settings){
        if(!PrinterReach.intersectsBuildHeight(client.player.getEyePos(),range(settings),settings.shape,client.world.getBottomY(),(client.world.getTopYInclusive()+1)))return PrinterReason.of(PrinterReason.Id.BUILD_HEIGHT,"超出建造高度");
        if(lastUnknown>0)return PrinterReason.of(PrinterReason.Id.LOADING,"等待加载");

        if(progressPercent()!=100&&deferredJob!=null&&ticks<=deferredUntil&&(!deferredMissing||!missing.isEmpty())&&valid(deferredJob))return deferredReason;
        deferredJob=null;deferredReason=PrinterReason.NONE;
        return missing.isEmpty()?PrinterReason.of(PrinterReason.Id.NO_WORK,"等待可施工位置"):PrinterReason.of(PrinterReason.Id.MISSING,"等待材料");
    }
    private boolean consume(PrinterWorkBudget budget){
        if(!pacing.canRun(false))return true;var s=settings();
        while(pacing.canRun(false)&&budget.attempt(System.nanoTime())){
            if(waiting!=null&&actions.ownsIce(waiting))waiting=null;
            boolean resuming=waiting!=null;int held=PrinterActions.heldMaterial(client.player.getMainHandStack());var owned=actions.nextIceJob(ticks);var job=resuming?waiting:owned!=null?owned:queue.pollBatch(held,readyMaterial);waiting=null;
            if(job==null){status=idleReason(s);return false;}
            actions.diagnostics.attempts++;
            if(!valid(job)){actions.diagnostics.stale++;if(actions.ownsIce(job))actions.cancelIce(job);else if(resuming)actions.reset();continue;}
            if(!actions.inProgress(job)&&pacing.cooling(job.position()))continue;
            if(s.safeObserver&&Block.getStateFromRawId(job.expected()).isOf(Blocks.OBSERVER)&&!PrinterRules.observerReady(BlockPos.fromLong(job.position()),p->{var sample=sampler.apply(new Vec3i(p.getX(),p.getY(),p.getZ()));return sample==null?null:sample.inside()?sample.state():Blocks.AIR.getDefaultState();},p->WorldChunks.loaded(client.world,p)?client.world.getBlockState(p):null)){deferred(job,PrinterReason.of(PrinterReason.Id.OBSERVER,"等待侦测器前方完成"),false);pacing.defer(job.position(),1);continue;}
            boolean breaking=actions.needsBreak(job);if(!pacing.canDispatch(job.position(),breaking)){if(resuming||actions.owns(job)){waiting=job;break;}queue.offer(job,actions.material(job,s));continue;}
            var outcome=actions.execute(job,s,ticks);actions.diagnostics.outcome(outcome,actions.dispatched());
            if(actions.dispatched()){if(materialBatchEnabled())materialBatch.dispatched(job,ticks,actions.material(job,s));pacing.dispatched(job.position(),actions.breakDispatched());if(outcome==PrinterActions.Outcome.SENT||outcome==PrinterActions.Outcome.WAIT)operations++;}
            if(s.highlights&&(actions.dispatched()||outcome==PrinterActions.Outcome.UNSUPPORTED)){var kind=outcome==PrinterActions.Outcome.UNSUPPORTED?ActionHighlights.Kind.FAILED:switch(job.kind()){case BREAK,BEDROCK->ActionHighlights.Kind.BREAK;case ADJUST->ActionHighlights.Kind.ADJUST;default->ActionHighlights.Kind.PLACE;};highlights.add(job.position(),kind,System.nanoTime(),HighlightFades.duration(kind,s.highlightMillis*1_000_000L));}
            if(outcome==PrinterActions.Outcome.WAIT){waiting=job;status=actions.typedReason();break;}
            if(outcome==PrinterActions.Outcome.SENT){retryWindow.forget(job.position());deferredJob=null;status=PrinterReason.RUNNING;}
            else if(outcome==PrinterActions.Outcome.MISSING){recordMissing(actions.missing());deferred(job,actions.typedReason(),true);if(resuming)actions.reset();pacing.defer(job.position(),20);}
            else if(outcome==PrinterActions.Outcome.UNSUPPORTED||outcome==PrinterActions.Outcome.RETRY){deferred(job,actions.typedReason(),false);if(resuming)actions.reset();int delay=outcome==PrinterActions.Outcome.RETRY?1:20;
                var why=actions.typedReason().id();
                if(outcome==PrinterActions.Outcome.RETRY&&(why==PrinterReason.Id.NO_FACE||why==PrinterReason.Id.NO_SUPPORT))delay=retryWindow.failed(job.position(),job.expected(),job.observed(),job.generation(),why.ordinal(),ticks);
                pacing.defer(job.position(),delay);}
        }
        return true;
    }
    @Override public void close(){stop();materialBatch.close();nearby.clear();signs.clear();if(workers!=null){workers.shutdownNow();workers=null;}}
}
