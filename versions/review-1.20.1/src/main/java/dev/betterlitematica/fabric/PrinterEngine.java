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

/** Client-thread snapshots and interactions, bounded pure-data discovery workers. */
final class PrinterEngine implements AutoCloseable {
    enum State { STOPPED,RUNNING,PAUSED }
    private record Context(ClientWorld world,List<ProjectionController.PrinterSource> sources,LayerRange layer,AreaSelection selection,long revision) {}
    private final MinecraftClient client;private final ProjectionController controller;private final PrinterActions actions;
    private final PrinterContainers containers;
    private final PrinterSigns signs;
    private final NearbyBuildHud nearby;
    private final PrinterQueue queue=new PrinterQueue(4096);
    private final ArrayDeque<CompletableFuture<PrinterDiscovery.Result>> searches=new ArrayDeque<>();
    private final PrinterPacing pacing=new PrinterPacing();
    private final LinkedHashMap<Item,Integer> missing=new LinkedHashMap<>();
    private final ActionHighlights highlights=new ActionHighlights(256);
    private ThreadPoolExecutor workers;private int workerCount;
    private State state=State.STOPPED;private Context context;private long generation;private boolean startupBurst,coldBurstUsed;
    private PrinterScan scan;private Vec3i center;private PrinterQueue.Job waiting;private long completedRounds;
    private java.util.function.Function<Vec3i,ProjectionController.PrinterSample> sampler;
    private int ticks,roundCompared,roundMatched,roundUnknown,lastCompared,lastMatched,lastUnknown;
    private BlockState fillState;
    private long operations;private String status="已停止";
    PrinterEngine(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;nearby=new NearbyBuildHud(client,controller);containers=new PrinterContainers(client,controller);signs=new PrinterSigns(client,controller);actions=new PrinterActions(client,controller.inventoryTransfers(),()->controller.options().accurate,this::externalAllowed,containers,signs,controller);}
    private boolean externalAllowed(){return running()&&client.world!=null&&client.player!=null&&client.currentScreen==null&&client.isWindowFocused()&&!client.player.isDead()&&!controller.worldWriteBusy()&&Objects.equals(context,current());}
    State state(){return state;}boolean acting(){return actions.acting()||containers.acting();}boolean running(){return state==State.RUNNING;}
    boolean ownsBreaking(){return running()&&actions.breaking()&&client.currentScreen==null&&client.isWindowFocused();}
    long operations(){return operations;}int queued(){return queue.size()+(waiting==null?0:1);}
    String status(){return status;}
    String progress(){return lastCompared==0?"":lastMatched+" / "+lastCompared+(lastUnknown>0?" · 待加载 "+lastUnknown:"");}
    List<String> hud(){
        if(state==State.STOPPED||!settings().hud)return List.of();var lines=new ArrayList<String>();lines.add("打印机 · "+(context==null?0:context.sources().size())+" 个投影 · "+status+" · 操作 "+operations);
        if(settings().missingHud)missing.entrySet().stream().limit(4).forEach(e->lines.add("缺少 "+e.getKey().getName().getString()));return lines;
    }
    NearbyBuildHud.Snapshot nearbyHud(){return nearby.snapshot();}
    private PrinterSettings settings(){return controller.options().printer;}
    private Context current(){return new Context(client.world,controller.printerSources(),controller.layerRange(),controller.selection(),settings().revision);}
    static boolean mining(PrinterSettings s){return s.breakWrong||s.breakExtra||s.breakState;}
    void start(){
        if(controller.editor().active()||controller.editor().busy())throw new IllegalStateException("请先暂停投影编辑");
        if(client.world==null||client.player==null||client.interactionManager==null)throw new IllegalStateException("请先进入世界");
        if(client.player.isSpectator())throw new IllegalStateException("旁观模式不能施工");
        if(controller.printerPlacements().isEmpty())throw new IllegalStateException("请先启用投影");
        if(controller.worldWriteBusy())throw new IllegalStateException("请先结束创造粘贴或填充任务");
        settings().validate();if(!settings().print&&!mining(settings())&&!settings().fill&&!settings().fluid&&!settings().bedrock)throw new IllegalStateException("请选择工作模式");
        fillState=checkedFill(settings());
        if(settings().bedrock)actions.checkMiner();
        if(controller.bedrock().enabled())controller.bedrock().pause();
        if(state==State.STOPPED)operations=0;
        Context next=current();if(context==null||!context.equals(next)){clearWork();context=next;}
        startupBurst=state!=State.RUNNING&&!coldBurstUsed;state=State.RUNNING;status="打印中";
    }
    void pause(String reason){containers.pause();if(state==State.RUNNING){state=State.PAUSED;status=reason;actions.diagnostics.reset();actions.reset();if(!actions.cleanupError().isEmpty())status=actions.cleanupError();waiting=null;}}
    void sourceChanged(){pause("投影编辑");clearWork();context=null;nearby.clear();}
    void stop(){state=State.STOPPED;startupBurst=false;status="已停止";clearWork();if(!actions.cleanupError().isEmpty())status=actions.cleanupError();highlights.clear();context=null;}
    static BlockState checkedFill(PrinterSettings settings){var resolver=new StateResolver1201(List.of(BlockStateSpec.parse(settings.fillState)),new PlacementTransform(Vec3i.ZERO,0,false,false));if(resolver.unresolved(0))throw new IllegalArgumentException("填充方块或状态不存在");var state=resolver.resolve(0);if((settings.fill||settings.fluid)&&(state.isAir()||!(state.getBlock().asItem() instanceof net.minecraft.item.BlockItem)))throw new IllegalArgumentException("请选择可放置的填充方块");return state;}
    void configure(PrinterSettings next){
        next.validate();var fill=checkedFill(next);if(settings().snapshot().equals(next.snapshot()))return;
        next.revision=settings().revision+1;controller.options().printer=next;fillState=fill;pause("设置已更改");clearWork();context=state==State.STOPPED?null:current();controller.saveOptions();
    }
    void toggle(){if(running())pause("已暂停");else start();}
    void cycle(){var s=PrinterSettings.read(settings().snapshot());int mode=s.print?0:s.fill?1:2;s.print=mode==2;s.fill=mode==0;s.fluid=mode==1;configure(s);}
    void supplyOpened(int sync,net.minecraft.screen.ScreenHandlerType<?> type){try{containers.opened(sync,type);actions.supplyOpened(sync,type);}catch(RuntimeException e){pause(e.getMessage());}}
    void containerOpening(net.minecraft.screen.ScreenHandlerType<?> type){containers.opening(type);}
    void supplyInventory(int sync){actions.supplyInventory(sync);}
    void manualInventory(){if(acting())return;try{containers.manual();actions.manualInventory();pause("已暂停");controller.inventoryTransfers().reset();}catch(RuntimeException e){pause(e.getMessage());}}
    void confirmed(BlockPos pos,BlockState value){actions.confirmed(pos,value);containers.changed(pos,value);}
    void containerInventory(net.minecraft.network.packet.s2c.play.InventoryS2CPacket packet){containers.inventory(packet);}
    void containerSlot(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket packet){containers.slot(packet);}
    void manualContainerInteraction(){if(containers.active()&&!acting()){containers.manualRequest();pause("已暂停");}}
    boolean signOpened(net.minecraft.block.entity.SignBlockEntity sign,boolean front){return signs.opened(sign,front);}
    void manualSignInteraction(BlockPos pos){if(!acting())signs.manual(pos);}
    Collection<ActionHighlights.Mark> actionMarks(long now){return highlights.live(now);}
    void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context){if(settings().highlights)ProjectionOverlays.actions(client,context,highlights.live(System.nanoTime()),settings());}
    private void clearWork(){containers.clear();generation++;for(var future:searches)future.cancel(true);searches.clear();if(workers!=null)workers.getQueue().clear();queue.clear();pacing.reset();completedRounds=0;sampler=null;missing.clear();scan=null;center=null;waiting=null;actions.diagnostics.reset();roundCompared=roundMatched=roundUnknown=lastCompared=lastMatched=lastUnknown=0;actions.reset();}
    private void pool(){int count=settings().threads;if(workers!=null&&count==workerCount)return;if(workers!=null)workers.shutdownNow();workerCount=count;workers=new ThreadPoolExecutor(count,count,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(count),r->{Thread t=new Thread(r,"betterlitematica-printer-search");t.setDaemon(true);return t;});}
    void tickHud(){nearby.tick();}
    void tick(){
        ticks++;signs.tick();
        if(client.world==null||client.player==null||client.interactionManager==null){if(state!=State.STOPPED)stop();containers.tick(ticks,false);return;}
        if(state==State.STOPPED){containers.tick(ticks,false);return;}
        if(context!=null&&context.world()!=client.world){stop();containers.tick(ticks,false);return;}
        Context next=current();
        if(!Objects.equals(context,next)){clearWork();context=next;}
        boolean canFill=running()&&settings().containerFill&&settings().print&&client.currentScreen==null&&client.isWindowFocused()
            &&!client.player.isSpectator()&&!client.player.isDead()&&!controller.worldWriteBusy();
        if(containers.tick(ticks,canFill)){if(running())status=containers.reason();return;}
        if(!running())return;
        if(!client.isWindowFocused()){pause("已暂停");return;}
        try{if(actions.supplyTick(ticks)){status=actions.reason();return;}}catch(RuntimeException e){pause(e.getMessage());return;}
        if(client.currentScreen!=null){pause("已暂停");return;}
        if(client.player.isSpectator()||client.player.isDead()||controller.worldWriteBusy()){pause("已暂停");return;}
        if(context.sources().isEmpty()){status="等待启用投影";return;}
        if(client.player.isUsingItem()){status="等待物品使用结束";return;}
        try{
            actions.diagnostics.begin();
            var settings=settings();pacing.begin(ticks,settings.interval,settings.perTick,settings.cooldown,settings.breakInterval,settings.breakPerTick);
            int budgetMillis=startupBurst&&PrinterColdWarmup.ready()&&settings.workBudgetMillis==16&&settings.interval==0&&settings.perTick==0&&settings.cooldown==0?32:settings.workBudgetMillis;
            if(startupBurst)coldBurstUsed=true;
            startupBurst=false;
            var budget=new PrinterWorkBudget(System.nanoTime(),budgetMillis);actions.prepare(ticks);pool();sampler=controller.printerSampler();
            updateCenter();collect();
            while(budget.hasTime(System.nanoTime())&&pacing.canRun(false)){
                if(waiting==null&&queue.size()==0){collect();if(queue.size()==0&&!snapshot(budget,true))break;}
                if(consume(budget))break;
            }
            // Spend the shared deadline on ready work first; prefetch only spare/blocked time.
            if(waiting!=null||queue.size()<512||!pacing.canRun(false))snapshot(budget,false);
            if(!budget.hasTime(System.nanoTime()))actions.diagnostics.budgetStops++;
            if(ticks%20==0){missing.entrySet().removeIf(e->ticks-e.getValue()>60);pacing.expire();}
            if(settings.containerFill&&settings.print&&waiting==null&&queue.size()==0&&!controller.inventoryTransfers().inFlight()
                &&budget.hasTime(System.nanoTime())&&containers.startNext(ticks))status=containers.reason();
            var containerMissing=containers.takeMissing();if(!containerMissing.isEmpty()){if(missing.size()>=128)missing.remove(missing.keySet().iterator().next());missing.put(containerMissing.getItem(),ticks);}
        }catch(RuntimeException e){pause(e.getMessage()==null?"打印异常，已暂停":e.getMessage());BetterLitematicaClient.LOGGER.error("Printer paused",e);}finally{sampler=null;actions.diagnostics.finish(settings(),queued(),actions.reason());}
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
    private Vec3i eyeCenter(){var eye=client.player.getEyePos();return new Vec3i((int)Math.round(eye.x),(int)Math.round(eye.y),(int)Math.round(eye.z));}
    private void updateCenter(){
        center=eyeCenter();var s=settings();double radius=s.shape==PrinterRange.Shape.SPHERE?PrinterReach.candidateRadius(range(s)):range(s);
        if(scan==null||scan.radius()!=radius){scan=new PrinterScan(center,radius,s.shape,s.order,s.reverseX,s.reverseY,s.reverseZ);completedRounds=0;}
        scan.beginTick(center,ticks);
    }
    private double range(PrinterSettings s){double reach=client.interactionManager.getReachDistance();return s.range==0?reach:Math.min(reach,s.range);}
    private boolean withinRange(BlockPos pos,PrinterSettings s){
        if(s.shape==PrinterRange.Shape.SPHERE)return PrinterReach.distanceSquared(client.player.getEyePos(),pos)<=range(s)*range(s);
        return PrinterRange.contains(new Vec3i(pos.getX(),pos.getY(),pos.getZ()),center==null?eyeCenter():center,range(s),s.shape);
    }
    private boolean snapshot(PrinterWorkBudget budget,boolean urgent){
        long started=System.nanoTime();try{return snapshotInternal(budget,urgent);}finally{actions.diagnostics.scanNanos+=System.nanoTime()-started;}
    }
    private boolean snapshotInternal(PrinterWorkBudget budget,boolean urgent){
        var s=settings();
        if((!urgent&&searches.size()>=workerCount*2)||queue.remaining()<2048||!scan.hasNext()||!budget.scan(System.nanoTime()))return false;
        long[] positions=new long[512];int[] expected=new int[512],actual=new int[512],flags=new int[512],scopes=new int[512],fill=new int[512];int n=0,visited=0;long started=System.nanoTime();
        var eye=client.player.getEyePos();double rangeSquared=range(s)*range(s);
        int fillId=Block.getRawIdFromState(fillState);
        while(scan.hasNext()&&n<512&&visited++<2048&&budget.hasTime(System.nanoTime())&&System.nanoTime()-started<1_000_000L){
            var at=scan.next();if(at==null)continue;var pos=new BlockPos(at.x(),at.y(),at.z());if(s.shape==PrinterRange.Shape.SPHERE&&PrinterReach.distanceSquared(eye,pos)>rangeSquared||client.world.isOutOfHeightLimit(pos)||!client.world.getWorldBorder().contains(pos))continue;
            var sample=sampler.apply(at);boolean in=sample!=null&&sample.inside();int scope=0;
            if((s.print||mining(s))&&scope(at,s.printScope,in)&&in)scope|=1;if(s.fill&&scope(at,s.fillScope,in))scope|=2;if(s.fluid&&scope(at,s.fluidScope,in))scope|=4;if(s.bedrock&&in&&controller.layerRange().contains(at))scope|=8;
            if(scope==0)continue;int i=n++;positions[i]=pos.asLong();scopes[i]=scope;fill[i]=fillId;
            if(!WorldChunks.loaded(client.world,pos)||sample!=null&&in&&sample.state()==null){flags[i]=0;continue;}
            BlockState current=client.world.getBlockState(pos),wanted=in?sample.state():Blocks.AIR.getDefaultState();if(wanted==null)continue;
            if(s.containerFill&&s.print&&(scope&1)!=0&&!PrinterRules.filtered(wanted,s.skip)
                &&!(s.skipWaterlogged&&wanted.contains(net.minecraft.state.property.Properties.WATERLOGGED)&&wanted.get(net.minecraft.state.property.Properties.WATERLOGGED)))containers.observe(pos,wanted,current,ticks);
            actual[i]=Block.getRawIdFromState(current);expected[i]=Block.getRawIdFromState(wanted);
            if((s.print||mining(s))&&!s.fill&&!s.fluid&&!s.bedrock&&current==wanted){flags[i]=PrinterDiscovery.KNOWN;continue;}
            int f=PrinterDiscovery.KNOWN;if(wanted.isAir())f|=PrinterDiscovery.WANTED_AIR;if(current.isAir())f|=PrinterDiscovery.ACTUAL_AIR;
            if(current.isAir()||current.isReplaceable()&&s.replace&&PrinterRules.filtered(current,s.replaceable))f|=PrinterDiscovery.REPLACEABLE;
            if(current.getBlock()==wanted.getBlock())f|=PrinterDiscovery.SAME_BLOCK;
            if(PrinterRules.adjustable(current,wanted,s))f|=PrinterDiscovery.ADJUSTABLE;
            if(s.fluid){var fluid=current.getFluidState();if(!fluid.isEmpty()&&PrinterRules.fluidMatches(Registries.FLUID.getId(fluid.getFluid()).toString(),s.fluids))f|=PrinterDiscovery.FLUID;if(fluid.isStill())f|=PrinterDiscovery.FLUID_SOURCE;}
            if(s.bedrock&&current!=wanted&&controller.bedrock().accepts(pos))f|=PrinterDiscovery.BEDROCK;
            if(PrinterRules.filtered(wanted,s.skip)||s.skipWaterlogged&&wanted.contains(net.minecraft.state.property.Properties.WATERLOGGED)&&wanted.get(net.minecraft.state.property.Properties.WATERLOGGED))f|=PrinterDiscovery.SKIP;
            if(s.coralSubstitute&&actions.waitingCoral(pos,current,wanted,ticks))f|=PrinterDiscovery.SKIP;
            flags[i]=f;
        }
        if(n==0){finishRound();return true;}
        var page=new PrinterDiscovery.Page(generation,Arrays.copyOf(positions,n),Arrays.copyOf(expected,n),Arrays.copyOf(actual,n),Arrays.copyOf(flags,n),Arrays.copyOf(scopes,n),Arrays.copyOf(fill,n));
        var policy=new PrinterDiscovery.Policy(s.print,s.fill,s.fluid,s.bedrock,s.breakWrong,s.breakExtra,s.breakState,s.flowing);
        if(urgent){accept(PrinterDiscovery.search(page,policy));finishRound();}
        else searches.add(CompletableFuture.supplyAsync(()->PrinterDiscovery.search(page,policy),workers));
        return true;
    }
    private boolean valid(PrinterQueue.Job job){
        if(job.generation()!=generation)return false;var pos=BlockPos.fromLong(job.position());var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());
        var s=settings();if(!withinRange(pos,s))return false;
        if(!WorldChunks.loaded(client.world,pos)||client.world.isOutOfHeightLimit(pos)||!client.world.getWorldBorder().contains(pos))return false;
        if(Block.getRawIdFromState(client.world.getBlockState(pos))!=job.observed()&&!actions.owns(job))return false;
        var sample=sampler.apply(at);boolean in=sample!=null&&sample.inside();
        return switch(job.kind()){
            case BREAK->mining(s)&&scope(at,s.printScope,in)&&sample!=null&&sample.state()!=null&&Block.getRawIdFromState(sample.state())==job.expected();
            case PLACE,ADJUST->s.print&&scope(at,s.printScope,in)&&sample!=null&&sample.state()!=null&&Block.getRawIdFromState(sample.state())==job.expected();
            case FILL->s.fill&&scope(at,s.fillScope,in);
            case FLUID->s.fluid&&scope(at,s.fluidScope,in);
            case BEDROCK->s.bedrock&&in&&controller.layerRange().contains(at);
        };
    }
    /** True means an owned wait/quota/time boundary; false lets the caller refill an empty queue. */
    private boolean consume(PrinterWorkBudget budget){
        if(!pacing.canRun(false))return true;var s=settings();
        while(pacing.canRun(false)&&budget.attempt(System.nanoTime())){
            boolean resuming=waiting!=null;int held=PrinterActions.heldMaterial(client.player.getMainHandStack());var job=resuming?waiting:queue.pollBatch(held);waiting=null;
            if(job==null){status=!PrinterReach.intersectsBuildHeight(client.player.getEyePos(),range(s),s.shape,client.world.getBottomY(),client.world.getTopY())?"超出建造高度":lastUnknown>0?"等待加载":missing.isEmpty()?"等待可施工位置":"等待材料";return false;}
            actions.diagnostics.attempts++;
            if(!valid(job)){actions.diagnostics.stale++;if(resuming)actions.reset();continue;}
            if(!actions.inProgress(job)&&pacing.cooling(job.position()))continue;
            if(s.safeObserver&&Block.getStateFromRawId(job.expected()).isOf(Blocks.OBSERVER)&&!PrinterRules.observerReady(BlockPos.fromLong(job.position()),p->{var sample=sampler.apply(new Vec3i(p.getX(),p.getY(),p.getZ()));return sample==null?null:sample.inside()?sample.state():Blocks.AIR.getDefaultState();},p->WorldChunks.loaded(client.world,p)?client.world.getBlockState(p):null)){status="等待侦测器前方完成";pacing.defer(job.position(),1);continue;}
            boolean breaking=actions.needsBreak(job);if(!pacing.canDispatch(job.position(),breaking)){if(resuming||actions.owns(job)){waiting=job;break;}queue.offer(job,actions.material(job,s));continue;}
            var outcome=actions.execute(job,s,ticks);actions.diagnostics.outcome(outcome,actions.dispatched());
            if(actions.dispatched()){pacing.dispatched(job.position(),actions.breakDispatched());if(outcome==PrinterActions.Outcome.SENT||outcome==PrinterActions.Outcome.WAIT)operations++;}
            if(s.highlights&&(actions.dispatched()||outcome==PrinterActions.Outcome.UNSUPPORTED)){var kind=outcome==PrinterActions.Outcome.UNSUPPORTED?ActionHighlights.Kind.FAILED:switch(job.kind()){case BREAK,BEDROCK->ActionHighlights.Kind.BREAK;case ADJUST->ActionHighlights.Kind.ADJUST;default->ActionHighlights.Kind.PLACE;};highlights.add(job.position(),kind,System.nanoTime(),HighlightFades.duration(kind,s.highlightMillis*1_000_000L));}
            if(outcome==PrinterActions.Outcome.WAIT){waiting=job;status=actions.reason();break;}
            if(outcome==PrinterActions.Outcome.SENT){status="打印中";}
            else if(outcome==PrinterActions.Outcome.MISSING){if(actions.missing()!=null){if(missing.size()>=128)missing.remove(missing.keySet().iterator().next());missing.put(actions.missing(),ticks);}status=actions.reason();if(resuming)actions.reset();pacing.defer(job.position(),20);}
            else if(outcome==PrinterActions.Outcome.UNSUPPORTED||outcome==PrinterActions.Outcome.RETRY){status=actions.reason();if(resuming)actions.reset();pacing.defer(job.position(),outcome==PrinterActions.Outcome.RETRY?1:20);}
        }
        return true;
    }
    @Override public void close(){stop();nearby.clear();signs.clear();if(workers!=null){workers.shutdownNow();workers=null;}}
}
