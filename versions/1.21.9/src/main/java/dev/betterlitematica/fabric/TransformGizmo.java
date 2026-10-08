package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** A held, single-axis metadata transaction. Preview never mutates a placement or selection. */
final class TransformGizmo {
    private static final int MIDDLE=-GLFW.GLFW_MOUSE_BUTTON_MIDDLE-1;
    private final MinecraftClient client;
    private final ProjectionController controller;
    private final ToolInteractions tool;
    private record Target(UUID id,SelectionTarget.Part part,String name,Vec3i anchor,Object original){}
    private record Ray(Vec3d eye,Vec3d direction){}
    private static final class Drag {
        final Target target;final Object world,connection;final long epoch;final ToolMode mode;
        final int axis;final double start;
        int amount;AreaSelection selectionPreview;
        Drag(Target target,Object world,Object connection,long epoch,ToolMode mode,int axis,double start){this.target=target;this.world=world;this.connection=connection;this.epoch=epoch;this.mode=mode;this.axis=axis;this.start=start;selectionPreview=target.original() instanceof AreaSelection s?s:null;}
    }
    private Target hover;
    private int hoveredAxis=-1,lockedAxes;
    private double length=1;
    private Target scaleTarget;
    private Object scaleWorld;
    private long scaleEpoch,scaleTime;
    private long hoverUntil;
    private Drag drag;
    private boolean middleClaimed,escapeClaimed;

    TransformGizmo(MinecraftClient client,ProjectionController controller,ToolInteractions tool){this.client=client;this.controller=controller;this.tool=tool;}
    boolean dragging(){return drag!=null;}
    boolean claimsInput(){return drag!=null||middleClaimed;}
    private boolean available(){return client.world!=null&&client.player!=null&&client.currentScreen==null&&client.isWindowFocused()&&!client.options.hudHidden&&!client.player.isSpectator()&&tool.held()&&controller.toolRenderingEnabled()&&tool.mode()!=ToolMode.REBUILD&&!tool.legacyGrabActive();}
    boolean showBounds(){return available();}
    private boolean selectionMode(){return tool.mode().selection()&&!(tool.mode()==ToolMode.DELETE&&tool.settings().deletePlacement);}
    private Ray ray(){var camera=client.gameRenderer.getCamera();return new Ray(camera.getPos(),Vec3d.fromPolar(camera.getPitch(),camera.getYaw()));}
    private static AxisGizmoMath.Point point(Vec3d p){return new AxisGizmoMath.Point(p.x,p.y,p.z);}
    private static Vec3d anchor(Target target){var p=target.anchor();return new Vec3d(p.x(),p.y(),p.z()).add(target.id()==null&&target.part()!=SelectionTarget.Part.ORIGIN?.5:0,target.id()==null&&target.part()!=SelectionTarget.Part.ORIGIN?.5:0,target.id()==null&&target.part()!=SelectionTarget.Part.ORIGIN?.5:0);}
    private Vec3d previewOrigin(Target target){var at=anchor(target);var delta=previewOffset();return at.add(delta.x(),delta.y(),delta.z());}
    private double size(Target target,Ray ray){return Math.max(.75,Math.min(32,ray.eye().distanceTo(previewOrigin(target))*.16));}
    private static boolean sameHandle(Target a,Target b){return a!=null&&b!=null&&Objects.equals(a.id(),b.id())&&a.part()==b.part()&&a.name().equals(b.name());}
    private void clearScale(){scaleTarget=null;scaleWorld=null;scaleTime=0;}
    private void scaleTarget(Target target,Ray ray){
        if(target==null){clearScale();return;}
        if(!sameHandle(scaleTarget,target)||scaleWorld!=client.world||scaleEpoch!=controller.sessionEpoch()){
            length=size(target,ray);scaleTime=System.nanoTime();
        }
        scaleTarget=target;scaleWorld=client.world;scaleEpoch=controller.sessionEpoch();
    }
    private boolean reachable(Target target){return client.player.getEyePos().squaredDistanceTo(anchor(target))<=tool.settings().distance*(double)tool.settings().distance;}
    private boolean current(Target target){return target!=null&&(target.id()!=null?Objects.equals(controller.selectedId(),target.id())&&Objects.equals(controller.placement(target.id()),target.original()):Objects.equals(controller.selection(),target.original()));}
    private int locks(Target target){if(target.id()==null)return 0;var p=(Placement)target.original();return p.locked()?7:p.lockedAxes();}
    private static double component(Vec3d v,int axis){return axis==0?v.x:axis==1?v.y:v.z;}
    private static int parallelAxes(Ray ray){int mask=0;for(int axis=0;axis<3;axis++)if(Math.abs(component(ray.direction(),axis))>.995)mask|=1<<axis;return mask;}
    private double obstruction(Ray ray){double reach=tool.settings().distance+ray.eye().distanceTo(client.player.getEyePos())+10;var hit=client.world.raycast(new net.minecraft.world.RaycastContext(ray.eye(),ray.eye().add(ray.direction().multiply(reach)),net.minecraft.world.RaycastContext.ShapeType.OUTLINE,net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));return hit.getType()==net.minecraft.util.hit.HitResult.Type.MISS?Double.POSITIVE_INFINITY:ray.eye().distanceTo(hit.getPos())+.03;}
    private static double nodeHit(Target target,Ray ray){var at=anchor(target);double radius=target.part()==SelectionTarget.Part.ORIGIN?.2:.6;var box=new Box(at.x-radius,at.y-radius,at.z-radius,at.x+radius,at.y+radius,at.z+radius);if(box.contains(ray.eye()))return 0;return box.raycast(ray.eye(),ray.eye().add(ray.direction().multiply(512))).map(p->p.distanceTo(ray.eye())).orElse(Double.POSITIVE_INFINITY);}
    private int axisHit(Target target,Ray ray){int axis=-1;double best=Double.POSITIVE_INFINITY;var at=anchor(target);for(int a=0;a<3;a++){double hit=AxisGizmoMath.hit(point(ray.eye()),point(ray.direction()),point(at),a,length,length*.115);if(Double.isFinite(hit)&&hit<best){axis=a;best=hit;}}return axis;}
    private void hover(){
        if(!available()){hover=null;hoveredAxis=-1;clearScale();return;}var ray=ray();long now=System.nanoTime();
        if(!selectionMode()){
            var p=controller.selectedPlacement();hover=p==null||!p.enabled()||controller.regions(p.id()).isEmpty()?null:new Target(p.id(),null,"",p.transform().origin(),p);
            if(hover!=null&&!reachable(hover))hover=null;
        }else{
            double limit=obstruction(ray);
            if(!current(hover)||hover.id()!=null||!reachable(hover))hover=null;
            // The revealed node owns its complete handle while aiming from the node onto an axis.
            double node=hover==null?Double.POSITIVE_INFINITY:nodeHit(hover,ray);
            if(hover!=null&&(axisHit(hover,ray)>=0||Double.isFinite(node)&&node<=limit)){hoverUntil=now+220_000_000L;}
            else {
                Target next=null;double best=limit;var selection=controller.selection();
                for(var box:selection.boxes())for(int i=0;i<2;i++){
                    var candidate=new Target(null,i==0?SelectionTarget.Part.FIRST:SelectionTarget.Part.SECOND,box.name(),i==0?box.first():box.second(),selection);
                    if(!reachable(candidate))continue;double hit=nodeHit(candidate,ray);if(hit<best){best=hit;next=candidate;}
                }
                if(!selection.boxes().isEmpty()){
                    var candidate=new Target(null,SelectionTarget.Part.ORIGIN,"",selection.origin(),selection);
                    if(reachable(candidate)){double hit=nodeHit(candidate,ray);if(hit<best)next=candidate;}
                }
                if(next!=null){hover=next;hoverUntil=now+220_000_000L;}else if(now>hoverUntil)hover=null;
            }
        }
        scaleTarget(hover,ray);
        hoveredAxis=hover==null?-1:axisHit(hover,ray);if(hover!=null)lockedAxes=locks(hover)|parallelAxes(ray);
    }
    // Camera/player motion changes the live ray, not ownership of the held metadata transaction.
    private boolean valid(){return drag!=null&&available()&&client.world==drag.world&&client.getNetworkHandler()==drag.connection&&controller.sessionEpoch()==drag.epoch&&tool.mode()==drag.mode&&current(drag.target)&&!controller.worldWriteBusy();}
    private void updateDrag(){
        if(!valid()){cancel();return;}var ray=ray();if((parallelAxes(ray)&1<<drag.axis)!=0)return;
        double parameter=AxisGizmoMath.parameter(point(ray.eye()),point(ray.direction()),point(anchor(drag.target)),drag.axis);
        if(!Double.isFinite(parameter))return;
        try{
            int amount=AxisGizmoMath.snapped(parameter-drag.start,drag.amount);if(amount==drag.amount)return;
            var offset=AxisGizmoMath.offset(drag.axis,amount);var value=drag.target.anchor().add(offset);
            if(Math.abs((long)value.x())>30_000_000||Math.abs((long)value.y())>30_000_000||Math.abs((long)value.z())>30_000_000)return;
            if(drag.target.id()==null)drag.selectionPreview=new SelectionTarget(drag.target.part(),drag.target.name(),0).translate((AreaSelection)drag.target.original(),offset);
            else{var p=(Placement)drag.target.original();var t=p.transform();new PlacementLayout(p.placed(new PlacementTransform(value,t.quarterTurns(),t.mirrorX(),t.mirrorZ())),controller.regions(p.id()));}
            drag.amount=amount;
        }
        catch(IllegalArgumentException|ArithmeticException ignored){}
    }
    void tick(){
        if(drag!=null)updateDrag();else hover();
        if(middleClaimed&&GLFW.glfwGetMouseButton(client.getWindow().getHandle(),GLFW.GLFW_MOUSE_BUTTON_MIDDLE)!=GLFW.GLFW_PRESS){cancel();middleClaimed=false;}
    }
    boolean event(int key,int action){
        if(key==MIDDLE&&action==GLFW.GLFW_RELEASE&&middleClaimed){if(drag!=null){updateDrag();finish();}middleClaimed=false;return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE&&action==GLFW.GLFW_RELEASE&&escapeClaimed){escapeClaimed=false;return true;}
        if(drag!=null&&key==GLFW.GLFW_KEY_ESCAPE&&action==GLFW.GLFW_PRESS){cancel();escapeClaimed=true;return true;}
        if(middleClaimed&&key==MIDDLE)return true;
        if(middleClaimed&&key<0)return true;
        if(key!=MIDDLE||action!=GLFW.GLFW_PRESS||!available()||tool.selectModifierActive()||net.minecraft.client.MinecraftClient.getInstance().isCtrlPressed()||net.minecraft.client.MinecraftClient.getInstance().isShiftPressed()||net.minecraft.client.MinecraftClient.getInstance().isAltPressed())return false;
        hover();if(hover==null||hoveredAxis<0)return false;
        middleClaimed=true;if((lockedAxes&1<<hoveredAxis)!=0)return true;
        if(controller.worldWriteBusy()){controller.action(()->{throw new IllegalStateException("施工中，暂不能拖动");});return true;}
        var ray=ray();double parameter=AxisGizmoMath.parameter(point(ray.eye()),point(ray.direction()),point(anchor(hover)),hoveredAxis);if(!Double.isFinite(parameter))return true;
        controller.printer().pause("调整位置");controller.editor().pause();
        drag=new Drag(hover,client.world,client.getNetworkHandler(),controller.sessionEpoch(),tool.mode(),hoveredAxis,parameter);return true;
    }
    private void finish(){
        var completed=drag;drag=null;if(completed==null)return;
        if(!current(completed.target)){cancel();return;}
        if(completed.amount!=0&&controller.action(()->{
            var offset=AxisGizmoMath.offset(completed.axis,completed.amount);
            if(completed.target.id()!=null){var p=completed.target.anchor().add(offset);controller.move(p.x(),p.y(),p.z());}
            else{var target=new SelectionTarget(completed.target.part(),completed.target.name(),0);controller.selectionApply(completed.selectionPreview);controller.selectionTarget(target);}
        })==0){cancel();return;}
        // Rebind the committed node without resetting its displayed scale or requiring another node hit.
        hover=committedTarget(completed.target);hoveredAxis=-1;hoverUntil=System.nanoTime()+220_000_000L;
        if(hover==null)clearScale();
    }
    private Target committedTarget(Target previous){
        if(previous.id()!=null){
            var placement=controller.placement(previous.id());
            return placement==null||!Objects.equals(controller.selectedId(),previous.id())?null:new Target(placement.id(),null,"",placement.transform().origin(),placement);
        }
        var selection=controller.selection();
        if(previous.part()==SelectionTarget.Part.ORIGIN)return selection.boxes().isEmpty()?null:new Target(null,previous.part(),"",selection.origin(),selection);
        for(var box:selection.boxes())if(box.name().equals(previous.name()))return new Target(null,previous.part(),box.name(),previous.part()==SelectionTarget.Part.FIRST?box.first():box.second(),selection);
        return null;
    }
    void cancel(){drag=null;hover=null;hoveredAxis=-1;clearScale();}
    AreaSelection selectionPreview(){return drag==null||drag.selectionPreview==null?controller.selection():drag.selectionPreview;}
    UUID previewPlacement(){return drag==null?null:drag.target.id();}
    Vec3i previewOffset(){return drag==null?Vec3i.ZERO:AxisGizmoMath.offset(drag.axis,drag.amount);}
    String status(){return drag==null?"":"XYZ".charAt(drag.axis)+" "+(drag.amount>0?"+":"")+drag.amount;}
    void prepareFrame(){
        if(drag!=null)updateDrag();else hover();
        var target=drag==null?hover:drag.target;
        if(target==null){clearScale();return;}
        var ray=ray();scaleTarget(target,ray);long now=System.nanoTime();
        // Advance once per rendered frame; ticking and input only read the displayed geometry.
        double seconds=Math.min(.05,Math.max(0,(now-scaleTime)/1e9));scaleTime=now;
        length+=(size(target,ray)-length)*-Math.expm1(-18*seconds);
        if(drag==null)hoveredAxis=axisHit(target,ray);
    }
    boolean visible(ProjectionRenderContext context){
        if(!available())return false;var target=drag==null?hover:drag.target;if(target==null)return false;
        var at=previewOrigin(target);double extent=length;
        return context.frustum()==null||context.frustum().isVisible(new Box(at.x-extent*.12,at.y-extent*.12,at.z-extent*.12,at.x+extent*1.12,at.y+extent*1.12,at.z+extent*1.12));
    }
    void render(ProjectionRenderContext context){
        if(!visible(context))return;
        var target=drag==null?hover:drag.target;if(target==null)return;
        GizmoOverlay.draw(client,context,new GizmoOverlay.View(previewOrigin(target),drag==null?hoveredAxis:drag.axis,drag==null?-1:drag.axis,drag==null?lockedAxes:locks(target),length,List.of(),drag!=null,drag==null?0:drag.amount));
    }
}
