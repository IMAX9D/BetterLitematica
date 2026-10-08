package dev.betterlitematica.fabric;

import dev.betterlitematica.core.VerificationReport;
import dev.betterlitematica.core.HighlightFades;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.util.math.Box;
import java.util.List;

final class ProjectionOverlays {
    private ProjectionOverlays() {}
    private static final int[][] FACE_CORNERS={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
    static void nearby(MinecraftClient client,WorldRenderContext context,java.util.Collection<NearbyProjectionHighlights.Mark> marks,java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> actions,PrinterSettings settings,boolean errors){
        if(client.player==null||marks.isEmpty()||context.matrixStack()==null)return;
        var priority=new java.util.HashSet<Long>();for(var action:actions)priority.add(action.position());
        var matrices=context.matrixStack();var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickCounter().getTickProgress(false));var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(settings.highlightOnTop);float alpha=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(lines);for(var mark:marks){if((mark.kind()==NearbyProjectionHighlights.Kind.WRONG)!=errors)continue;if(priority.contains(mark.position())&&!(errors&&settings.highlightOnTop))continue;var box=new Box(net.minecraft.util.math.BlockPos.fromLong(mark.position())).expand(.003);if(context.frustum()!=null&&!context.frustum().isVisible(box))continue;
            boolean missing=mark.kind()==NearbyProjectionHighlights.Kind.MISSING;
            int color=missing?NearbyProjectionHighlights.MISSING_COLOR:NearbyProjectionHighlights.WRONG_COLOR;
            float opacity=missing?missingAlpha(box,origin,camera,look,settings.highlightRange):alpha;
            if(opacity>0)net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,box,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,opacity);
        }}finally{matrices.pop();buffers.draw(lines);}
    }
    private static final NearbyProjectionHighlights.Kind[] NEARBY_KINDS=NearbyProjectionHighlights.Kind.values();
    private static final dev.betterlitematica.core.Comparison[] COMPARISONS=dev.betterlitematica.core.Comparison.values();
    private static final BoxOrder nearbyOrder=new BoxOrder(),errorOrder=new BoxOrder();
    private static final class BoxOrder {
        private List<dev.betterlitematica.core.HighlightCuboids.Box> source=List.of(),sorted=List.of();
        private net.minecraft.util.math.BlockPos eye;
        List<dev.betterlitematica.core.HighlightCuboids.Box> get(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,net.minecraft.util.math.Vec3d camera,int limit){
            if(limit==0){source=List.of();sorted=List.of();eye=null;return boxes;}
            var next=net.minecraft.util.math.BlockPos.ofFloored(camera);
            if(source!=boxes||!next.equals(eye)){source=boxes;eye=next;var result=new java.util.ArrayList<>(boxes);result.sort(java.util.Comparator.comparingDouble(b->distance(b,camera)));sorted=List.copyOf(result);}
            return sorted;
        }
    }
    private static Box bounds(dev.betterlitematica.core.HighlightCuboids.Box b){return new Box(b.min().x(),b.min().y(),b.min().z(),(double)b.max().x()+1,(double)b.max().y()+1,(double)b.max().z()+1);}
    private static double distance(dev.betterlitematica.core.HighlightCuboids.Box b,net.minecraft.util.math.Vec3d p){
        double x=Math.max(b.min().x()-p.x,Math.max(0,p.x-b.max().x()-1)),y=Math.max(b.min().y()-p.y,Math.max(0,p.y-b.max().y()-1)),z=Math.max(b.min().z()-p.z,Math.max(0,p.z-b.max().z()-1));return x*x+y*y+z*z;
    }
    private static float missingAlpha(Box box,net.minecraft.util.math.Vec3d eye,net.minecraft.util.math.Vec3d camera,net.minecraft.util.math.Vec3d look,double range){
        double x=Math.max(box.minX-eye.x,Math.max(0,eye.x-box.maxX)),y=Math.max(box.minY-eye.y,Math.max(0,eye.y-box.maxY)),z=Math.max(box.minZ-eye.z,Math.max(0,eye.z-box.maxZ));
        double dx=(box.minX+box.maxX)*.5-camera.x,dy=(box.minY+box.maxY)*.5-camera.y,dz=(box.minZ+box.maxZ)*.5-camera.z;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz),alignment=length<1e-6?1:(dx*look.x+dy*look.y+dz*look.z)/length;
        return HighlightFades.missing(Math.sqrt(x*x+y*y+z*z),range,alignment);
    }
    static void nearbyBoxes(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,PrinterSettings settings,int extraColor,boolean errors){
        if(client.player==null||boxes.isEmpty()||context.matrixStack()==null)return;
        var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickCounter().getTickProgress(false));var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var matrices=context.matrixStack();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var layer=OverlayLayers.lines(settings.highlightOnTop);int drawn=0;float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(layer);for(var box:nearbyOrder.get(boxes,origin,settings.highlightLimit)){
            if(distance(box,origin)>settings.highlightRange*(double)settings.highlightRange)continue;
            var bounds=bounds(box).expand(.003);if(context.frustum()!=null&&!context.frustum().isVisible(bounds))continue;
            if(settings.highlightLimit>0&&drawn++>=settings.highlightLimit)break;
            var kind=NEARBY_KINDS[box.group()];if((kind!=NearbyProjectionHighlights.Kind.MISSING)!=errors)continue;
            int color=kind==NearbyProjectionHighlights.Kind.MISSING?NearbyProjectionHighlights.MISSING_COLOR:kind==NearbyProjectionHighlights.Kind.WRONG?NearbyProjectionHighlights.WRONG_COLOR:extraColor;
            float alpha=kind==NearbyProjectionHighlights.Kind.MISSING?missingAlpha(bounds,origin,camera,look,settings.highlightRange):pulse;
            if(alpha>0)net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,bounds,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,alpha);
        }}finally{matrices.pop();buffers.draw(layer);}
    }
    static void errorBoxes(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,DisplayOptions settings,boolean onTop,int limit){
        if(client.player==null||boxes.isEmpty()||context.matrixStack()==null)return;
        var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickCounter().getTickProgress(false));var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var matrices=context.matrixStack();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(onTop);var faces=OverlayLayers.faces(onTop);int drawn=0;float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{for(var box:errorOrder.get(boxes,origin,limit)){
            if(distance(box,origin)>128*128)continue;var bounds=bounds(box).expand(.002);if(context.frustum()!=null&&!context.frustum().isVisible(bounds))continue;
            if(limit>0&&drawn++>=limit)break;
            var type=COMPARISONS[box.group()];
            int color=switch(type){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case EXTRA->settings.extraColor;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;default->0xff999999;};
            float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=type==dev.betterlitematica.core.Comparison.MISSING?missingAlpha(bounds,origin,camera,look,128):pulse;if(a<=0)continue;
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED)net.minecraft.client.render.VertexRendering.drawBox(matrices,buffers.getBuffer(lines),bounds,r,g,b,a);
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.peek().getPositionMatrix();for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,(float)((corner&1)!=0?bounds.maxX:bounds.minX),(float)((corner&2)!=0?bounds.maxY:bounds.minY),(float)((corner&4)!=0?bounds.maxZ:bounds.minZ)).color(r,g,b,a*.25f);}
        }}finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
    static void actions(MinecraftClient client,WorldRenderContext context,java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> marks,PrinterSettings settings){
        if(client.player==null||marks.isEmpty()||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPos();var origin=client.player.getEyePos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(settings.highlightOnTop);var faces=OverlayLayers.faces(settings.highlightOnTop);long now=System.nanoTime();
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{var ordered=new java.util.ArrayList<>(marks);if(settings.highlightLimit>0)ordered.sort(java.util.Comparator.comparingDouble(m->PrinterReach.distanceSquared(origin,net.minecraft.util.math.BlockPos.fromLong(m.position()))));int drawn=0;for(var mark:ordered){var pos=net.minecraft.util.math.BlockPos.fromLong(mark.position());if(PrinterReach.distanceSquared(origin,pos)>settings.highlightRange*(double)settings.highlightRange)continue;if(settings.highlightLimit>0&&drawn++>=settings.highlightLimit)break;int color=switch(mark.kind()){case PLACE->settings.placeColor;case ADJUST->settings.adjustColor;case BREAK->settings.breakColor;case FAILED->settings.failedColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,alpha=(color>>>24)/255f*HighlightFades.action(mark,now);if(alpha<=0)continue;
                if(settings.highlightStyle!=PrinterSettings.HighlightStyle.FILLED)net.minecraft.client.render.VertexRendering.drawBox(matrices,buffers.getBuffer(lines),new Box(pos).expand(0.003),r,g,b,alpha);
                if(settings.highlightStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.peek().getPositionMatrix();for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,pos.getX()+((corner&1)!=0?1.003f:-0.003f),pos.getY()+((corner&2)!=0?1.003f:-0.003f),pos.getZ()+((corner&4)!=0?1.003f:-0.003f)).color(r,g,b,alpha*0.25f);}
            }}finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
    static void selection(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target){
        selection(client,context,selection,target,false);
    }
    static void selection(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target,boolean allNodes){
        if(selection.boxes().isEmpty()||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var vertices=buffers.getBuffer(RenderLayer.getLines());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{for(var box:selection.boxes()){var r=box.region();boolean selected=box.name().equals(selection.selected());net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(r.min().x(),r.min().y(),r.min().z(),(double)r.min().x()+r.size().x(),(double)r.min().y()+r.size().y(),(double)r.min().z()+r.size().z()),selected?0:1,1,selected?1:0,1);if(selected||allNodes){var a=box.first();var b=box.second();net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(a.x()-0.1,a.y()-0.1,a.z()-0.1,a.x()+1.1,a.y()+1.1,a.z()+1.1),0.25f,0.6f,1f,1f);net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(b.x()-0.1,b.y()-0.1,b.z()-0.1,b.x()+1.1,b.y()+1.1,b.z()+1.1),1f,0.7f,0.2f,1f);}}
            var o=selection.origin();boolean origin=target!=null&&target.part()==dev.betterlitematica.core.SelectionTarget.Part.ORIGIN;net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(o.x()-0.2,o.y()-0.2,o.z()-0.2,o.x()+0.2,o.y()+0.2,o.z()+0.2),1,origin?1:0.2f,origin?1:0.2f,1);
        }
        finally{matrices.pop();buffers.draw(RenderLayer.getLines());}
    }
    static void placements(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.PlacementLayout> layouts,java.util.UUID selected,DisplayOptions settings){
        placements(client,context,layouts,selected,settings,false,null,dev.betterlitematica.core.Vec3i.ZERO);
    }
    static void placements(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.PlacementLayout> layouts,java.util.UUID selected,DisplayOptions settings,boolean toolBounds,java.util.UUID previewId,dev.betterlitematica.core.Vec3i previewOffset){
        if(!toolBounds&&!settings.placementBounds&&!settings.regionBounds&&!settings.origins||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var layer=OverlayLayers.lines(false);var vertices=buffers.getBuffer(layer);
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{int drawn=0;for(var layout:layouts){boolean active=layout.placement().id().equals(selected);Box total=null;var offset=layout.placement().id().equals(previewId)?previewOffset:dev.betterlitematica.core.Vec3i.ZERO;
            for(int i=0;i<layout.size();i++){if(!layout.enabled(i))continue;var part=layout.part(i);var min=part.bounds().min();var max=part.bounds().max();var box=new Box(min.x(),min.y(),min.z(),(double)max.x()+1,(double)max.y()+1,(double)max.z()+1).offset(offset.x(),offset.y(),offset.z());total=total==null?box:total.union(box);
                if(settings.regionBounds&&drawn<1024&&(context.frustum()==null||context.frustum().isVisible(box))){net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,box,active?.3f:.5f,.8f,1,.8f);drawn++;}
                if(settings.origins&&settings.regionBounds&&drawn<1024){var pos=part.transform().apply(part.region().anchor()).add(offset);net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(pos.x()-.15,pos.y()-.15,pos.z()-.15,pos.x()+.15,pos.y()+.15,pos.z()+.15),1,.7f,.2f,1);drawn++;}
            }
            if((settings.placementBounds||toolBounds)&&total!=null&&(context.frustum()==null||context.frustum().isVisible(total)))net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,total,active?0:0.4f,active?1:.7f,1,active?1:.55f);
            if(settings.origins||toolBounds&&active){var pos=layout.placement().transform().origin().add(offset);net.minecraft.client.render.VertexRendering.drawBox(matrices,vertices,new Box(pos.x()-.25,pos.y()-.25,pos.z()-.25,pos.x()+.25,pos.y()+.25,pos.z()+.25),1,.3f,.4f,1);}
        }}finally{matrices.pop();buffers.draw(layer);}
    }
    static void marker(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.Vec3i pos){
        if(pos==null||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(true);matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{net.minecraft.client.render.VertexRendering.drawBox(matrices,buffers.getBuffer(lines),new Box(pos.x()-.01,pos.y()-.01,pos.z()-.01,pos.x()+1.01,pos.y()+1.01,pos.z()+1.01),1,.9f,.25f,1);}finally{matrices.pop();buffers.draw(lines);}
    }
    static void errors(MinecraftClient client,WorldRenderContext context,List<VerificationReport.Sample> samples,DisplayOptions settings,boolean onTop){
        if(client.player==null)return;var origin=client.player.getCameraPosVec(context.tickCounter().getTickProgress(false));var camera=context.camera().getPos();var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var matrices=context.matrixStack();if(matrices==null)return;var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(onTop);var faces=OverlayLayers.faces(onTop);float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{int drawn=0;for(var sample:samples){var p=sample.position();if(origin.squaredDistanceTo(p.x(),p.y(),p.z())>128*128)continue;
            int color=switch(sample.key().type()){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;case EXTRA->settings.extraColor;case UNKNOWN->0xaa999999;default->settings.wrongBlockColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=(color>>>24)/255f;
            if(sample.key().type()==dev.betterlitematica.core.Comparison.MISSING)a=missingAlpha(new Box(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1),origin,camera,look,128);
            else if(sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_BLOCK||sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_STATE)a=pulse;
            if(a<=0)continue;
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED)net.minecraft.client.render.VertexRendering.drawBox(matrices,buffers.getBuffer(lines),new Box(p.x()-.002,p.y()-.002,p.z()-.002,p.x()+1.002,p.y()+1.002,p.z()+1.002),r,g,b,a);
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.peek().getPositionMatrix();for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,p.x()+((corner&1)!=0?1.002f:-.002f),p.y()+((corner&2)!=0?1.002f:-.002f),p.z()+((corner&4)!=0?1.002f:-.002f)).color(r,g,b,a*.25f);}
            if(++drawn>=2048)break;
        }}finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
}
