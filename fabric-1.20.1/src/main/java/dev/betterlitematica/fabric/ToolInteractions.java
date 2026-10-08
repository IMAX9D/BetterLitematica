package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Tool-only actions. Never delegates a consumed tool click to a real attack or item use. */
final class ToolInteractions {
    private final MinecraftClient client;private final ProjectionController controller;
    private final Set<Integer> consumed=new HashSet<>();
    private String itemText;private ToolItemSpec item;
    private UUID subPlacement;private String subRegion="";
    private AreaSelection grabbed,grabLatest;private SelectionTarget grabTarget;private Vec3d grabAnchor;private double grabDistance;
    private Object world;private boolean clonePending;private String cloneName="";
    private ToolSelectionResize resizing;private AreaSelection resizeOriginal;private Object resizeWorld;
    private long lastMove;private String status="";
    private ProjectionController.CreativeJob pasteJob;
    private final TransformGizmo gizmo;
    ToolInteractions(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;gizmo=new TransformGizmo(client,controller,this);}
    TransformGizmo gizmo(){return gizmo;}
    boolean legacyGrabActive(){return grabbed!=null||resizing!=null;}
    boolean selectModifierActive(){return modifier("toolGrabModifier")||mode().primary()&&modifier("toolPrimaryModifier")||mode().secondary()&&modifier("toolSecondaryModifier");}
    ToolSettings settings(){return controller.options().tools;}
    ToolMode mode(){
        var stored=ToolMode.valueOf(controller.options().mode);
        var available=stored.usable(client.player==null||client.player.isCreative());
        // Migrate preferences only. Reading a mode must never start a paste or another world task.
        if(stored!=available)controller.options().mode=available.name();
        return available;
    }
    void mode(ToolMode value){
        value=value.replacement();
        if(value.creativeOnly()&&(client.player==null||!client.player.isCreative()))throw new IllegalStateException("此工具模式需要创造模式");
        if(mode()==ToolMode.REBUILD&&value!=ToolMode.REBUILD)controller.editor().pause();
        gizmo.cancel();releaseGrab();resizing=null;controller.options().mode=value.name();controller.saveOptions();status="";
    }
    void cycle(int step){mode(mode().cycle(step,client.player!=null&&client.player.isCreative()));}
    boolean held(){
        if(client.player==null||!controller.options().tool)return false;
        return configuredItem().held(client.player.getMainHandStack(),client.player.getOffHandStack());
    }
    private ToolItemSpec configuredItem(){String text=controller.options().toolItem;if(!Objects.equals(text,itemText)){item=ToolItemSpec.parse(text);itemText=text;}return item;}
    private boolean active(){return held()&&controller.toolRenderingEnabled()&&client.world!=null&&client.currentScreen==null&&client.isWindowFocused()&&!client.player.isSpectator();}
    private boolean modifier(String name){return InputBindings.pressedChord(client,controller.options().keys.getOrDefault(name,""));}
    private boolean matches(String name,int key){return InputBindings.matches(client,controller.options().keys.getOrDefault(name,""),key);}
    boolean event(long window,int key,int action){
        if(window!=client.getWindow().getHandle())return false;
        if(gizmo.event(key,action))return true;
        if(action==GLFW.GLFW_RELEASE)return consumed.remove(key);
        if(action!=GLFW.GLFW_PRESS)return consumed.contains(key);
        if(client.player==null||client.world==null||client.currentScreen!=null||!client.isWindowFocused()||!controller.options().tool||!controller.toolRenderingEnabled())return false;
        if(matches("toolSelect",key)&&((mode().primary()&&modifier("toolPrimaryModifier"))||(mode().secondary()&&modifier("toolSecondaryModifier")))){consumed.add(key);controller.action(this::sampleState);return true;}
        if(!active())return false;
        boolean select=matches("toolSelect",key),first=matches("toolPrimary",key),second=matches("toolSecondary",key);
        if(!select&&!first&&!second)return false;
        // The editor owns its complete click/hold lifecycle while armed.
        if(mode()==ToolMode.REBUILD&&controller.editor().claimsInput())return false;
        consumed.add(key);controller.printer().pause("工具操作");
        controller.action(()->{if(select)select();else click(first);});return true;
    }
    boolean blocksVanilla(){return gizmo.claimsInput()||active()&&mode()!=ToolMode.REBUILD;}
    void tick(){try{tickSafe();}catch(RuntimeException failure){gizmo.cancel();resizing=null;releaseGrab();controller.action(()->{throw failure;});}}
    private void tickSafe(){
        if(world!=client.world){world=client.world;clear();}
        gizmo.tick();
        if(!Objects.equals(subPlacement,controller.selectedId())){subPlacement=null;subRegion="";}
        if(clonePending){var next=controller.placements().stream().filter(p->p.name().equals(cloneName+" · 临时")).findFirst();if(next.isPresent()){controller.select(next.get().id());controller.options().mode=ToolMode.PASTE.name();controller.saveOptions();clonePending=false;}else if(controller.captureStatus().equals("没有捕获任务"))clonePending=false;}
        if(resizing!=null){
            if(client.world!=resizeWorld||controller.selection()!=resizeOriginal){resizing=null;status="选区已改变，调整已取消";}
            else{var task=resizing;boolean done=task.tick(at->{var p=new BlockPos(at.x(),at.y(),at.z());return p.getY()<client.world.getBottomY()||p.getY()>=client.world.getTopY()?false:!WorldChunks.loaded(client.world,p)?null:!client.world.getBlockState(p).isAir();},2048,System.nanoTime()+2_000_000L);status=task.status();if(done){controller.selectionApply(task.result());resizing=null;}}
        }
        if(grabbed==null)return;
        if(!active()||!usesSelection()||controller.selection()!=grabLatest){releaseGrab();return;}
        Vec3d at=client.player.getEyePos().add(client.player.getRotationVec(1).multiply(grabDistance));Vec3d d=at.subtract(grabAnchor);
        Vec3i offset=new Vec3i((int)Math.floor(d.x),(int)Math.floor(d.y),(int)Math.floor(d.z));
        AreaSelection next=grabTarget==null?grabbed.translated(offset):grabTarget.translate(grabbed,offset);
        if(!next.equals(grabLatest)){controller.selectionApply(next);controller.selectionTarget(grabTarget);grabLatest=controller.selection();}
    }
    void clear(){gizmo.cancel();resizing=null;consumed.clear();releaseGrab();subPlacement=null;subRegion="";clonePending=false;status="";pasteJob=null;}
    private void releaseGrab(){grabbed=grabLatest=null;grabTarget=null;}
    private boolean usesSelection(){return mode().selection()&&!(mode()==ToolMode.DELETE&&settings().deletePlacement);}
    private BlockHitResult ray(){var hit=client.player.raycast(settings().distance,1,false);return hit.getType()==HitResult.Type.BLOCK?(BlockHitResult)hit:null;}
    private Vec3i point(BlockHitResult hit,boolean placement){return point(hit,placement,client.player.isSneaking());}
    static Vec3i point(BlockHitResult hit,boolean placement,boolean sneak){BlockPos p=hit.getBlockPos();if(placement?!sneak:sneak)p=p.offset(hit.getSide());return vec(p); }
    static Vec3i vec(BlockPos p){return new Vec3i(p.getX(),p.getY(),p.getZ());}
    private void ensureBox(Vec3i at){if(controller.selection().selected().isEmpty()){String name="区域 1";int n=1;var names=controller.selection().boxes().stream().map(SelectionBox::name).toList();while(names.contains(name))name="区域 "+(++n);controller.selectionApply(controller.selection().add(new SelectionBox(name,at,at)));}}
    private void click(boolean first){
        if(mode()==ToolMode.REBUILD){execute();return;}
        var hit=ray();if(hit==null)return;
        if(mode()==ToolMode.MOVE&&modifier("toolGrabModifier")){moveWorld(point(hit,true));return;}
        if(usesSelection()){
            Vec3i at=point(hit,false);var selection=controller.selection();
            if(controller.selectionTarget()!=null&&controller.selectionTarget().part()==SelectionTarget.Part.ORIGIN){applyKeepingTarget(modifier("toolGrabModifier")?selection.translated(at.subtract(selection.origin())):selection.origin(at));return;}
            ensureBox(at);selection=controller.selection();
            if(settings().expandSelection){var b=selection.current();if(first){var r=b.region();Vec3i min=r.min(),max=r.min().add(r.size()).add(new Vec3i(-1,-1,-1));controller.selectionApply(selection.put(new SelectionBox(b.name(),new Vec3i(Math.min(min.x(),at.x()),Math.min(min.y(),at.y()),Math.min(min.z(),at.z())),new Vec3i(Math.max(max.x(),at.x()),Math.max(max.y(),at.y()),Math.max(max.z(),at.z())))));}else controller.selectionApply(selection.put(new SelectionBox(b.name(),at,at)));}
            else controller.selectionCorner(first,at);
        }else movePlacement(point(hit,true));
    }
    private void select(){
        if(mode()==ToolMode.REBUILD){execute();return;}
        if(usesSelection()){
            if(grabbed!=null){releaseGrab();return;}
            var eye=client.player.getEyePos();var dir=client.player.getRotationVec(1);var hit=SelectionTarget.ray(controller.selection(),eye.x,eye.y,eye.z,dir.x,dir.y,dir.z,settings().distance);var ground=ray();if(hit!=null&&ground!=null&&hit.distance()>eye.distanceTo(ground.getPos())+.01)hit=null;controller.selectionTarget(hit);
            if(hit!=null&&modifier("toolGrabModifier")){grabbed=grabLatest=controller.selection();grabTarget=hit;grabDistance=hit.distance();grabAnchor=eye.add(dir.multiply(grabDistance));}
        }else selectPlacement(modifier("toolGrabModifier"));
    }
    private void selectPlacement(boolean sub){
        var start=client.player.getEyePos();var end=start.add(client.player.getRotationVec(1).multiply(settings().distance));var ground=ray();double best=ground==null?Double.POSITIVE_INFINITY:start.squaredDistanceTo(ground.getPos())+.01;UUID id=null;String region="";
        for(var placement:controller.placements())if(placement.enabled())for(var source:controller.regions(placement.id())){
            if(!placement.region(source).enabled())continue;var b=PlacementBounds.clipped(source,placement.transformFor(source),LayerRange.ALL);
            var box=new Box(b.min().x(),b.min().y(),b.min().z(),(double)b.max().x()+1,(double)b.max().y()+1,(double)b.max().z()+1);
            var hit=box.raycast(start,end);double distance=box.contains(start)?0:hit.isPresent()?hit.get().squaredDistanceTo(start):Double.POSITIVE_INFINITY;
            if(distance<best){best=distance;id=placement.id();region=sub?source.name():"";}
        }
        controller.select(id);subPlacement=id;subRegion=region;
    }
    private Region selectedRegion(){var p=controller.selectedPlacement();if(p==null)throw new IllegalStateException("请先选择投影");return Objects.equals(subPlacement,p.id())&&!subRegion.isEmpty()?controller.regions(p.id()).stream().filter(r->r.name().equals(subRegion)).findFirst().orElse(null):null;}
    private void movePlacement(Vec3i at){var p=controller.selectedPlacement();if(p==null)throw new IllegalStateException("请先选择投影");var region=selectedRegion();if(region==null)controller.move(at.x(),at.y(),at.z());else{Vec3i local=p.transform().inverse(at);controller.region(p.id(),region.name(),r->r.moved(local));}}
    private Vec3i placementOrigin(){var p=controller.selectedPlacement();if(p==null)throw new IllegalStateException("请先选择投影");var region=selectedRegion();return region==null?p.transform().origin():p.transform().apply(p.region(region).position());}
    boolean scroll(double amount){
        if(gizmo.claimsInput())return true;
        if(!active()||!Double.isFinite(amount)||amount==0)return false;int sign=amount>0?1:-1;
        if(modifier("toolGrabModifier")&&usesSelection()){
            controller.action(()->{if(grabbed!=null)grabDistance=Math.max(1,Math.min(200,grabDistance+sign));else if(controller.selectionTarget()!=null&&controller.selectionTarget().part()==SelectionTarget.Part.ORIGIN)applyKeepingTarget(controller.selection().translated(direction(sign)));else if(mode()==ToolMode.MOVE)moveWorld(controller.selection().origin().add(direction(sign)));});return true;
        }
        if(modifier("toolGrowModifier")&&usesSelection()){controller.action(()->grow(sign));return true;}
        if(modifier("toolNudgeModifier")){controller.action(()->{if(usesSelection())controller.selectionNudge(direction(sign));else movePlacement(placementOrigin().add(direction(sign)));});return true;}
        if(modifier("toolCycleModifier")){controller.action(()->cycle(-sign));return true;}return false;
    }
    private Vec3i direction(int sign){var v=client.player.getRotationVec(1);var d=Direction.getFacing(v.x,v.y,v.z);return new Vec3i(d.getOffsetX()*sign,d.getOffsetY()*sign,d.getOffsetZ()*sign);}
    private void applyKeepingTarget(AreaSelection next){var target=controller.selectionTarget();controller.selectionApply(next);if(target!=null)controller.selectionTarget(target);}
    static SelectionBox growBox(SelectionBox box,int sign){for(var d:List.of(new Vec3i(1,0,0),new Vec3i(-1,0,0),new Vec3i(0,1,0),new Vec3i(0,-1,0),new Vec3i(0,0,1),new Vec3i(0,0,-1)))box=box.expand(d,sign);return box;}
    private void grow(int sign){var s=controller.selection();applyKeepingTarget(s.put(growBox(s.current(),sign)));}
    private void sampleState(){
        var real=client.player.raycast(6,1,false);var projected=controller.target(6);var eye=client.player.getEyePos();net.minecraft.block.BlockState state=net.minecraft.block.Blocks.AIR.getDefaultState();
        double distance=real.getType()==HitResult.Type.BLOCK?eye.squaredDistanceTo(real.getPos()):Double.POSITIVE_INFINITY;
        if(real instanceof BlockHitResult block&&real.getType()==HitResult.Type.BLOCK)state=client.world.getBlockState(block.getBlockPos());
        if(projected!=null){var at=projected.position();var hit=new Box(at.x(),at.y(),at.z(),at.x()+1,at.y()+1,at.z()+1).raycast(eye,eye.add(client.player.getRotationVec(1).multiply(6)));if(hit.isPresent()&&eye.squaredDistanceTo(hit.get())<distance)state=projected.state();}
        if(mode().primary()&&modifier("toolPrimaryModifier"))settings().primary=StateResolver1201.spec(state).toString();else settings().secondary=StateResolver1201.spec(state).toString();if(mode()==ToolMode.REBUILD&&controller.editor().active()){controller.editor().useHeld=false;controller.editor().block=settings().primary;}controller.saveOptions();
    }
    void cycleSelectionShape(){if(mode()==ToolMode.DELETE)settings().deletePlacement=!settings().deletePlacement;else if(mode()==ToolMode.PASTE)settings().pasteRule=ReplaceRule.values()[(settings().pasteRule.ordinal()+1)%3];else settings().expandSelection=!settings().expandSelection;controller.saveOptions();}
    void nudge(int step){if(usesSelection())controller.selectionNudge(direction(step));else movePlacement(placementOrigin().add(direction(step)));}
    void autoSize(boolean grow){resizeOriginal=controller.selection();resizeWorld=client.world;resizing=new ToolSelectionResize(resizeOriginal,grow);}
    void removeSelection(){if(controller.selectionTarget()!=null&&controller.selectionTarget().part()==SelectionTarget.Part.ORIGIN)resetSelectionOrigin();else controller.selectionRemove();}
    void resetSelectionOrigin(){var s=controller.selection();if(s.boxes().isEmpty())return;Vec3i min=s.boxes().get(0).region().min();for(var b:s.boxes()){var p=b.region().min();min=new Vec3i(Math.min(min.x(),p.x()),Math.min(min.y(),p.y()),Math.min(min.z(),p.z()));}controller.selectionApply(s.origin(min));}
    void cloneSelection(){if(controller.worldWriteBusy())throw new IllegalStateException("已有施工任务");cloneName="克隆-"+UUID.randomUUID();controller.captureTemporary(cloneName);clonePending=true;}
    void moveSelectionHere(){if(mode()==ToolMode.MOVE)moveWorld(vec(client.player.getBlockPos()));else if(!usesSelection())movePlacement(vec(client.player.getBlockPos()));else{var s=controller.selection();controller.selectionApply(s.translated(vec(client.player.getBlockPos()).subtract(s.origin())));}}
    private void moveWorld(Vec3i destination){long now=System.nanoTime();if(now-lastMove<400_000_000L)throw new IllegalStateException("请稍候再移动");startWorld();var original=controller.selection();var originalWorld=client.world;var future=controller.toolWorld().moveSelection(original,destination,settings().pasteEntities);lastMove=now;future.thenAccept(result->client.execute(()->{if(result.confirmed()&&client.world==originalWorld&&controller.selection()==original)controller.selectionApply(result.selection());}));}
    private void startWorld(){if(controller.worldWriteBusy())throw new IllegalStateException("已有施工任务");controller.printer().pause("工具操作");}
    private AreaSelection deleteArea(){if(!settings().deletePlacement)return controller.selection();var p=controller.selectedPlacement();if(p==null)throw new IllegalStateException("请先选择投影");var boxes=new ArrayList<SelectionBox>();for(var r:controller.regions(p.id()))if(p.region(r).enabled()){var b=PlacementBounds.clipped(r,p.transformFor(r),LayerRange.ALL);boxes.add(new SelectionBox(r.name(),b.min(),b.max()));}return new AreaSelection(boxes,"",p.transform().origin(),false);}
    void executeHotkey(){if(!controller.options().tool||!controller.toolRenderingEnabled()||(settings().executeRequiresTool&&!held()))throw new IllegalStateException("请手持启用的工具");execute();}
    void execute(){
        if(client.player==null||client.world==null)throw new IllegalStateException("请进入世界");settings().validate();if(mode().creativeOnly()&&!client.player.isCreative())throw new IllegalStateException("需要创造模式");
        switch(mode()){
            case FILL->{startWorld();controller.toolWorld().executeFill(controller.selection(),BlockStateSpec.parse(settings().primary),null,false);}
            case REPLACE->{startWorld();controller.toolWorld().executeFill(controller.selection(),BlockStateSpec.parse(settings().primary),BlockStateSpec.parse(settings().secondary),false);}
            case DELETE->{startWorld();controller.toolWorld().executeFill(deleteArea(),BlockStateSpec.AIR,null,settings().deleteEntities);}
            case PASTE->{var target=controller.selectedId();controller.paste(target,settings().pasteRule,settings().pasteEntities,settings().pasteNbt);pasteJob=controller.creativeJob(target);}
            case MOVE->moveWorld(vec(client.player.getBlockPos()));
            case REBUILD->{var p=controller.selectedPlacement();if(p==null)throw new IllegalStateException("请先选择投影");client.setScreen(null);controller.editor().open(p.id());controller.editor().useHeld=false;controller.editor().block=settings().primary;controller.editor().resume(p.id());}
            default->throw new IllegalStateException("当前模式无需执行");
        }
    }
    boolean edit(boolean place){if(mode()!=ToolMode.REBUILD)return false;return controller.editor().toolEdit(place,modifier("toolEditDirection"),modifier("toolEditAll"),modifier("toolEditType"),modifier("toolEditExcept"),modifier("toolEditFillAir"));}
    boolean pasteActive(){return pasteJob!=null&&controller.creativeActive(pasteJob.id());}
    boolean pastePaused(){return pasteJob!=null&&controller.creativePaused(pasteJob.id());}
    void pausePaste(){if(pasteJob!=null)controller.pauseCreative(pasteJob.id());}
    void cancelPaste(){if(pasteJob!=null)controller.cancelCreative(pasteJob.id());}
    String status(){
        if(gizmo.dragging())return gizmo.status();
        if(resizing!=null)return resizing.status();
        if(mode()==ToolMode.PASTE){
            if(pasteJob==null)return "";
            String value=controller.creativeStatus(pasteJob.id());
            return value.isEmpty()||Objects.equals(controller.selectedId(),pasteJob.target())?value:pasteJob.name()+" · "+value;
        }
        String task=controller.toolWorld().status();return task==null||task.isBlank()?status:task;
    }
    String targetName(){var p=controller.selectedPlacement();return usesSelection()?controller.selection().selected():p==null?"":p.name()+(subRegion.isEmpty()?"":" / "+subRegion);}
    ToolHudData.Input hudData(){
        var p=controller.selectedPlacement();var s=settings();boolean showPrimary=true;
        if(mode()==ToolMode.REBUILD&&client.player!=null){var hand=client.player.getMainHandStack();showPrimary=hand.isEmpty()||configuredItem().matches(hand);}
        return new ToolHudData.Input(mode(),controller.selection(),controller.selectionTarget(),p,
            p==null?List.of():controller.regions(p.id()),p!=null&&Objects.equals(subPlacement,p.id())?subRegion:"",
            s.expandSelection,s.deletePlacement,s.pasteRule,s.pasteNbt,s.pasteEntities,s.primary,s.secondary,
            showPrimary,grabbed!=null,Objects.requireNonNullElse(status(),""));
    }
}
