package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.state.property.Property;
import java.util.*;
import java.util.concurrent.*;

/** One fixed placement draft. Input never falls through to real-world interactions while armed. */
final class SchematicEditor {
    private final MinecraftClient client;private final ProjectionController controller;
    private UUID target;private ProjectionRenderer1201 renderer;private SchematicEdits draft;
    private boolean active,usePressed,attackPressed,pickPressed,cancelAdoption;
    private final dev.betterlitematica.runtime.InputReleaseGuard input=new dev.betterlitematica.runtime.InputReleaseGuard();
    boolean straight,useHeld=true;int region=-1,direction;
    String block="minecraft:stone",filename="",status="";
    private ProjectionController.EditorBaseline baseline;
    private Vec3i marker;private Plan plan;private ToolPlan toolPlan;private CompletableFuture<String> save;private String saved;private dev.betterlitematica.runtime.NewFileCommit saveCommit;
    private record Hit(PlacementLayout.Part part,Vec3i world,Vec3i local,Vec3i face,Vec3d point,BlockState state){}
    private static final class Plan {
        final Placement placement;final LayerRange layer;final PlacementLayout.Part part;final Vec3i start,step;final BlockState expected,replacement;
        final int limit;final boolean composed;final BlockStateSpec stored;final List<ProjectionScene.Part> sceneParts;int visited;boolean waitUnknown;final List<SchematicEdits.Request> changes=new ArrayList<>();
        Plan(Placement placement,LayerRange layer,Hit hit,Vec3i start,Vec3i step,BlockState expected,BlockState replacement,int limit,boolean composed,List<ProjectionScene.Part> sceneParts){this.placement=placement;this.layer=layer;part=hit.part();this.start=start;this.step=step;this.expected=expected;this.replacement=replacement;this.limit=limit;this.composed=composed;this.sceneParts=sceneParts;stored=StateResolver1201.spec(StateResolver1201.unplace(replacement,part.transform()));}
    }
    /** Source-domain traversal: absent render residency stalls a cell instead of discarding it. */
    static final class ToolScan {
        interface Reader {SchematicEdits.Request read(PlacementLayout.Part part,Vec3i local);}
        final List<PlacementLayout.Part> parts;final LayerRange layer;final RegionCursor cursor;final List<SchematicEdits.Request> changes=new ArrayList<>();
        ToolScan(List<PlacementLayout.Part> parts,LayerRange layer){this.parts=List.copyOf(parts);this.layer=layer;cursor=new RegionCursor(parts.stream().map(PlacementLayout.Part::region).toList());}
        boolean advance(Reader reader,int budget,long deadline){
            for(int n=0;n<budget&&!cursor.done()&&System.nanoTime()<deadline;n++){
                var part=parts.get(cursor.region());var local=cursor.local();
                if(layer.contains(part.transform().apply(local))){var request=reader.read(part,local);if(request!=null){if(changes.size()==SchematicEdits.MAX_TRANSACTION)throw new IllegalArgumentException("本次编辑超过 16384 格，草稿未改变");changes.add(request);}}
                cursor.advance();
            }
            return cursor.done();
        }
    }
    private record ToolPlan(Placement placement,LayerRange layer,BlockState expected,BlockState replacement,boolean byType,boolean exceptType,boolean fillAir,ToolScan scan){}
    SchematicEditor(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    UUID target(){return target;}boolean active(){return active;}boolean claimsInput(){return input.owns();}boolean owns(UUID id){return draft!=null&&Objects.equals(target,id);}
    boolean dirty(){return draft!=null&&draft.dirty();}boolean busy(){return baseline!=null||plan!=null||toolPlan!=null||save!=null||saved!=null;}
    boolean canUndo(){return draft!=null&&!busy()&&draft.canUndo();}boolean canRedo(){return draft!=null&&!busy()&&draft.canRedo();}
    SchematicEdits.Snapshot snapshot(UUID id){return owns(id)&&draft.dirty()?draft.snapshot():null;}
    Vec3i marker(){return active?marker:null;}
    String hud(){var placement=controller.placement(target);return active&&placement!=null?"编辑 · "+placement.name()+(status.isEmpty()?"":" · "+status):"";}
    void open(UUID id){
        if(owns(id)){prepareBaseline();return;}if(draft!=null&&!dirty()&&!busy())discard();if(draft!=null)throw new IllegalStateException("请先另存或放弃当前编辑");
        renderer=controller.editorRenderer(id);target=id;draft=new SchematicEdits(renderer.metadata(),renderer.sourceCounts());renderer.draft(draft);
        filename="edited-"+System.currentTimeMillis();status="";region=-1;prepareBaseline();
    }
    void restore(UUID id,ProjectionRenderer1201 source,SchematicEdits value){if(draft!=null)throw new IllegalStateException("已有编辑草稿");target=id;renderer=source;draft=value;renderer.draft(draft);filename="edited-"+System.currentTimeMillis();status="已恢复草稿";region=-1;pause();}
    private void prepareBaseline(){if(baseline==null&&target!=null){baseline=controller.editorBaseline(target);if(baseline!=null)status="正在保存临时投影";}}
    void resume(UUID id){
        open(id);if(busy())throw new IllegalStateException("编辑任务尚未完成");
        if(controller.worldWriteBusy())throw new IllegalStateException("请先结束施工任务");
        var placement=controller.placement(target);if(placement==null||!placement.enabled()||!placement.renderBlocks()||!controller.projectionRenderingEnabled())throw new IllegalStateException("请先显示此投影");
        if(!useHeld)StateResolver1201.checked(BlockStateSpec.parse(block));
        controller.printer().sourceChanged();if(BetterLitematicaClient.interactions!=null)BetterLitematicaClient.interactions.editingEntered();
        if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();active=true;input.arm();attackPressed=usePressed=pickPressed=false;client.setScreen(null);
    }
    void pause(){input.pause();active=false;marker=null;plan=null;toolPlan=null;}
    void reset(){pause();if(saveCommit!=null)saveCommit.cancel();saveCommit=null;target=null;renderer=null;draft=null;save=null;saved=null;if(baseline!=null)baseline.cancel();baseline=null;status="";}
    void cancel(){pause();cancelAdoption=true;if(baseline!=null)baseline.cancel();if(saveCommit!=null&&saveCommit.cancel())status="正在取消另存";}
    void discard(){
        if(baseline!=null||save!=null||saved!=null)throw new IllegalStateException("投影正在另存");pause();
        if(draft!=null){var changed=draft.snapshot().sections().keySet();renderer.draft(null);controller.editorChanged(target,changed,null);}reset();
    }
    void undo(){if(!canUndo())return;if(controller.worldWriteBusy())throw new IllegalStateException("请先结束施工任务");pause();var next=draft.fork();var changed=next.undo();publish(next,changed);status="";}
    void redo(){if(!canRedo())return;if(controller.worldWriteBusy())throw new IllegalStateException("请先结束施工任务");pause();var next=draft.fork();var changed=next.redo();publish(next,changed);status="";}
    void undoInWorld(boolean redo){boolean wasActive=active;try{if(redo)redo();else undo();if(wasActive){active=true;input.arm();}}catch(RuntimeException failure){if(wasActive)failed(failure.getMessage());else throw failure;}}
    void save(String name){
        if(draft==null||busy())throw new IllegalStateException("请等待当前编辑完成");pause();filename=name;
        cancelAdoption=false;saveCommit=new dev.betterlitematica.runtime.NewFileCommit();save=controller.saveDraft(target,draft.snapshot(),name,saveCommit);status="另存中";
    }
    void sourceLoaded(UUID id,boolean success){
        if(saved==null||!Objects.equals(target,id))return;
        if(success){String name=saved;reset();status="已另存："+name;}
        else {saved=null;status="已保存文件，重新加载失败；草稿已保留";}
    }
    void tick(){
        if(baseline!=null&&baseline.result.isDone()){var result=baseline;baseline=null;if(result.abandoned)status="已取消";else try{controller.adoptEditorBaseline(target,result);status="";}catch(RuntimeException failure){result.cancel();status="临时投影保存失败："+(failure.getCause()==null?failure.getMessage():failure.getCause().getMessage());}}

        if(save!=null&&save.isDone()){
            var result=save;save=null;
            try{String file=result.join();if(cancelAdoption){status="已保存："+file;saved=null;}else{saved=file;controller.adoptDraft(target,saved);status="加载中";}}
            catch(RuntimeException e){saved=null;status=saveCommit!=null&&saveCommit.cancelled()?"已取消":"另存失败："+(e.getCause()==null?e.getMessage():e.getCause().getMessage());}
        }
        boolean attack=EditorInput.down(client,client.options.attackKey),use=EditorInput.down(client,client.options.useKey),pick=EditorInput.down(client,client.options.pickItemKey);
        input.poll(client.isWindowFocused(),client.currentScreen!=null,attack||use||pick);
        if(!active)return;
        if(client.world==null||client.player==null||client.currentScreen!=null||!client.isWindowFocused()){pause();return;}
        var placement=controller.placement(target);
        if(controller.worldWriteBusy()||placement==null||!placement.enabled()||!placement.renderBlocks()||!controller.projectionRenderingEnabled()){failed("编辑已暂停");return;}
        if(!attack)attackPressed=false;if(!use)usePressed=false;if(!pick)pickPressed=false;
        if(!input.ready())return;
        if(toolPlan!=null){invoke(this::advanceTool);return;}
        if(plan!=null){invoke(this::advance);return;}
        try{var hit=hit();marker=hit==null?null:Screen.hasShiftDown()||attack?hit.world():hit.world().add(hit.face());if(marker!=null&&!hit.part().contains(marker))marker=null;}
        catch(ProjectionBlockView.Pending pending){marker=null;}catch(RuntimeException e){failed(e.getMessage());}
    }
    boolean attack(){if(!claimsInput())return false;if(!active)return true;boolean old=attackPressed;attackPressed=true;if(input.ready()&&!old&&!busy())invoke(()->start(false,false));return true;}
    boolean use(){if(!claimsInput())return false;if(!active)return true;boolean old=usePressed;usePressed=true;if(input.ready()&&!old&&!busy())invoke(()->start(true,Screen.hasShiftDown()));return true;}
    boolean toolEdit(boolean place,boolean directional,boolean all,boolean byType,boolean exceptType,boolean fillAir){
        if(!directional&&!all&&!byType&&!exceptType&&!fillAir)return false;
        if(!claimsInput())return false;if(!active)return true;
        boolean old=place?usePressed:attackPressed;if(place)usePressed=true;else attackPressed=true;
        if(input.ready()&&!old&&!busy())invoke(()->{
            if(exceptType&&place)throw new IllegalArgumentException("排除类型仅用于删除");
            if(fillAir&&!place)throw new IllegalArgumentException("填充空气仅用于放置");
            if(!all&&!byType&&!exceptType&&!fillAir){start(place,place&&Screen.hasShiftDown(),true,true);return;}
            var hit=hit();if(hit==null)return;
            var wanted=place?replacement(hit,hit.world()):Blocks.AIR.getDefaultState();
            var layout=renderer.layout();var parts=new ArrayList<PlacementLayout.Part>();for(int i=0;i<layout.size();i++)if(layout.enabled(i))parts.add(layout.part(i));
            var layer=controller.layerRange();toolPlan=new ToolPlan(controller.placement(target),layer,hit.state(),wanted,byType,exceptType,fillAir,new ToolScan(parts,layer));status="编辑中";advanceTool();
        });return true;
    }
    boolean pick(){
        if(!claimsInput())return false;if(!active)return true;boolean old=pickPressed;pickPressed=true;if(input.ready()&&!old&&!busy()){invoke(()->{var hit=hit();if(hit!=null){block=StateResolver1201.spec(hit.state()).toString();useHeld=false;status="";}});}return true;
    }
    private void invoke(Runnable action){try{action.run();}catch(ProjectionBlockView.Pending pending){status="投影正在加载";}catch(RuntimeException e){failed(e.getMessage());}}
    private void failed(String reason){pause();status=reason==null?"编辑失败":reason;if(client.world!=null&&client.player!=null&&target!=null)client.setScreen(new EditingScreen(null,controller,target));}
    boolean escape(){if(!active)return false;pause();client.setScreen(new EditingScreen(null,controller,target));return true;}
    private Hit hit(){
        if(renderer==null||client.player==null)return null;renderer.drain();var eye=client.player.getEyePos();var look=client.player.getRotationVec(1);var end=eye.add(look.multiply(200));
        for(var voxel:VoxelRay.trace(eye.x,eye.y,eye.z,look.x,look.y,look.z,200,1024)){
            var at=voxel.position();if(!controller.layerRange().contains(at))continue;
            var visible=controller.editorScene().sample(at);if(visible!=null&&visible.unknown())throw new ProjectionBlockView.Pending();
            if(visible!=null&&!visible.renderer().metadata().palette().get(visible.id()).isAir()&&visible.renderer()!=renderer)return null;
            PlacementLayout.Cell cell;
            if(region>=0){var part=renderer.layout().part(region);if(!renderer.layout().enabled(region)||!part.contains(at))continue;var local=part.local(at);cell=new PlacementLayout.Cell(part,local,renderer.sampleRaw(part.section(local),part.cell(local)));}
            else cell=renderer.layout().sample(at,new PlacementLayout.Source(){public int state(PlacementLayout.Part part,Vec3i local){return renderer.sampleRaw(part.section(local),part.cell(local));}public BlockStateSpec spec(int index,int id){return renderer.metadata().palette().get(id);}});
            if(cell==null)continue;if(cell.unknown())throw new ProjectionBlockView.Pending();if(renderer.metadata().palette().get(cell.state()).isAir())continue;
            if(renderer.resolver(cell.part().index()).unresolvedState(cell.state()))return null;var state=renderer.resolve(cell.part().index(),cell.state());var position=new BlockPos(at.x(),at.y(),at.z());var view=view(at);var shape=state.getOutlineShape(view,position);if(shape.isEmpty())shape=net.minecraft.util.shape.VoxelShapes.fullCube();var ray=shape.raycast(eye,end,position);if(ray==null)continue;
            var side=ray.getSide();return new Hit(cell.part(),at,cell.local(),new Vec3i(side.getOffsetX(),side.getOffsetY(),side.getOffsetZ()),ray.getPos(),state);
        }return null;
    }
    private ProjectionBlockView view(Vec3i pos){var box=new PlacementBounds(pos.add(new Vec3i(-2,-2,-2)),pos.add(new Vec3i(2,2,2)));return new ProjectionBlockView(client.world,controller.editorScene(),controller.editorScene().overlapping(box),controller.layerRange());}
    private BlockState replacement(Hit hit,Vec3i destination){
        if(!useHeld)return StateResolver1201.checked(BlockStateSpec.parse(block));
        var stack=client.player.getMainHandStack();if(!(stack.getItem() instanceof BlockItem item))throw new IllegalStateException("请手持方块或选择编辑方块");
        var world=new ProjectionWorld(client.world);world.view(view(destination),pos->null);
        var pos=new BlockPos(destination.x(),destination.y(),destination.z());var side=Direction.getFacing(hit.face().x(),hit.face().y(),hit.face().z());
        var context=new ItemPlacementContext(world,client.player,Hand.MAIN_HAND,stack,new BlockHitResult(hit.point(),side,pos,false)){
            @Override public BlockPos getBlockPos(){return pos;}@Override public boolean canPlace(){return true;}@Override public boolean canReplaceExisting(){return true;}
        };
        var adjusted=item.getPlacementContext(context);BlockState state=adjusted==null||!adjusted.getBlockPos().equals(pos)?null:item.getPlacementState(adjusted);if(state==null)throw new IllegalStateException("此位置无法生成方块状态");return StateResolver1201.itemState(state,stack);
    }
    private void start(boolean place,boolean replace){
        start(place,replace,straight,false);
    }
    private void start(boolean place,boolean replace,boolean line,boolean waitUnknown){
        Hit hit=hit();if(hit==null)return;Vec3i start=place&&!replace?hit.world().add(hit.face()):hit.world();
        if(!hit.part().contains(start))throw new IllegalStateException("编辑位置超出子区域");
        BlockState wanted=place?replacement(hit,start):Blocks.AIR.getDefaultState();
        Vec3i step=hit.face();
        if(direction==0){var heading=client.player.getHorizontalFacing();var p=hit.point();step=EditDirection.choose(hit.face(),p.x-hit.world().x(),p.y-hit.world().y(),p.z-hit.world().z(),new Vec3i(heading.getOffsetX(),0,heading.getOffsetZ()),place&&!replace);}
        else step=switch(direction){case 1->new Vec3i(1,0,0);case 2->new Vec3i(-1,0,0);case 3->new Vec3i(0,1,0);case 4->new Vec3i(0,-1,0);case 5->new Vec3i(0,0,1);default->new Vec3i(0,0,-1);};
        var local=hit.part().local(start);int first=renderer.sampleRaw(hit.part().section(local),hit.part().cell(local));if(first<0)throw new IllegalStateException("目标正在加载");
        if(renderer.resolver(hit.part().index()).unresolvedState(first)){status="已跳过无法识别的方块";return;}var expected=renderer.resolve(hit.part().index(),first);if(place&&!replace&&!expected.isAir())throw new IllegalStateException("目标位置已有投影方块");
        int limit=line?(waitUnknown?SchematicEdits.MAX_TRANSACTION+1:10001):1;var last=start.add(new Vec3i(step.x()*(limit-1),step.y()*(limit-1),step.z()*(limit-1)));var bounds=new PlacementBounds(new Vec3i(Math.min(start.x(),last.x()),Math.min(start.y(),last.y()),Math.min(start.z(),last.z())),new Vec3i(Math.max(start.x(),last.x()),Math.max(start.y(),last.y()),Math.max(start.z(),last.z())));
        plan=new Plan(controller.placement(target),controller.layerRange(),hit,start,step,expected,wanted,limit,region<0,controller.editorScene().overlapping(bounds));plan.waitUnknown=waitUnknown;status="";advance();
    }
    /** Returns null for an unmatched/no-op cell. Properties are copied only when the destination accepts them. */
    static BlockState toolReplacement(BlockState before,BlockState expected,BlockState replacement,boolean byType,boolean exceptType,boolean fillAir){
        boolean match=fillAir?before.isAir():exceptType?!before.isAir()&&before.getBlock()!=expected.getBlock():byType?before.getBlock()==expected.getBlock():before.equals(expected);
        if(!match)return null;var after=replacement;
        if(byType&&!exceptType&&!fillAir)for(var property:before.getProperties()){var next=after.getBlock().getStateManager().getProperty(property.getName());if(next!=null)after=copyProperty(after,next,propertyValue(before,property));}
        if(before.equals(after))return null;
        if(before.isOf(Blocks.MOVING_PISTON)&&after.isOf(Blocks.MOVING_PISTON))throw new IllegalArgumentException("运动活塞请先替换为普通方块");
        return after;
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state,Property<T> property){return property.name(state.get(property));}
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState state,Property<T> property,String value){return property.parse(value).map(v->state.with(property,v)).orElse(state);}
    private void advanceTool(){
        var current=toolPlan;if(current==null)return;
        if(!current.placement().equals(controller.placement(target))||!current.layer().equals(controller.layerRange())){toolPlan=null;throw new IllegalStateException("投影或分层已改变，草稿未改变");}
        renderer.drain();
        try{
            boolean done=current.scan().advance((part,local)->{
                var key=part.section(local);int cell=part.cell(local),base=renderer.baseState(key,cell),state=renderer.sampleRaw(key,cell);
                if(base<0||state<0)throw new ProjectionBlockView.Pending();
                if(renderer.resolver(part.index()).unresolvedState(state))return null;var before=renderer.resolve(part.index(),state);
                var after=toolReplacement(before,current.expected(),current.replacement(),current.byType(),current.exceptType(),current.fillAir());if(after==null)return null;
                var stored=StateResolver1201.spec(StateResolver1201.unplace(after,part.transform()));boolean same=before.getBlock()==after.getBlock();
                return new SchematicEdits.Request(key,cell,base,stored,same,same,before.getFluidState().getFluid().matchesType(after.getFluidState().getFluid()));
            },2048,System.nanoTime()+2_000_000L);
            if(!done){status="编辑中 · "+current.scan().cursor.processed();return;}
            var next=draft.fork();var changed=next.apply(current.scan().changes);publish(next,changed);toolPlan=null;status="";
        }catch(ProjectionBlockView.Pending pending){status="投影正在加载";}catch(RuntimeException failure){toolPlan=null;throw failure;}
    }
    void checkpoint(){if(draft!=null&&baseline==null&&(draft.dirty()||draft.canUndo()||draft.canRedo()))controller.checkpointEditor(target,draft);}
    private void publish(SchematicEdits next,Set<SectionKey> changed){
        if(changed.isEmpty())return;controller.admitEditorCheckpoint(target,next);controller.printer().sourceChanged();draft=next;renderer.draft(next);controller.editorChanged(target,changed,next);
    }
    private void advance(){
        var current=plan;if(current==null)return;
        if(current.waitUnknown)renderer.drain();
        if(!current.placement.equals(controller.placement(target))||!current.layer.equals(controller.layerRange())){plan=null;throw new IllegalStateException("投影或分层已改变");}
        long deadline=System.nanoTime()+2_000_000L;int work=0;boolean stopped=false;
        try{
            while(current.visited<current.limit&&work++<2048&&System.nanoTime()<deadline){
                int i=current.visited;var at=current.start.add(new Vec3i(current.step.x()*i,current.step.y()*i,current.step.z()*i));
                if(!current.part.contains(at)||!current.layer.contains(at)){stopped=true;break;}
                var local=current.part.local(at);var key=current.part.section(local);int cell=current.part.cell(local),base=renderer.baseState(key,cell),state=renderer.sampleRaw(key,cell);
                if(base<0||state<0){if(current.waitUnknown){status="投影正在加载";return;}status="已停止于未加载区域";stopped=true;break;}
                if(current.composed){var visible=controller.editorScene().sample(current.sceneParts,at);if(visible!=null&&visible.unknown()){if(current.waitUnknown){status="投影正在加载";return;}status="已停止于未加载区域";stopped=true;break;}
                    boolean visibleAir=visible==null||visible.renderer().metadata().palette().get(visible.id()).isAir();
                    if(current.expected.isAir()?!visibleAir:visibleAir||visible.renderer()!=renderer||visible.region().index()!=current.part.index()){stopped=true;break;}}
                if(renderer.resolver(current.part.index()).unresolvedState(state)){status="已停止于未知方块";stopped=true;break;}var before=renderer.resolve(current.part.index(),state);if(!before.equals(current.expected)){stopped=true;break;}
                var after=current.replacement;if(before.isOf(Blocks.MOVING_PISTON)&&after.isOf(Blocks.MOVING_PISTON)&&before!=after)throw new IllegalArgumentException("运动活塞请先替换为普通方块");
                boolean same=before.getBlock()==after.getBlock();
                if(current.waitUnknown&&current.changes.size()==SchematicEdits.MAX_TRANSACTION)throw new IllegalArgumentException("本次编辑超过 16384 格，草稿未改变");
                current.changes.add(new SchematicEdits.Request(key,cell,base,current.stored,same,same,before.getFluidState().getFluid().matchesType(after.getFluidState().getFluid())));current.visited++;
            }
            if(!stopped&&current.visited<current.limit)return;
            plan=null;var next=draft.fork();var changed=next.apply(current.changes);publish(next,changed);
        }catch(RuntimeException e){plan=null;throw e;}
    }
}
