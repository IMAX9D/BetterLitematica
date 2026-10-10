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
        var matrices=context.matrixStack();var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickDelta());var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(settings.highlightOnTop);float alpha=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(lines);for(var mark:marks){if((mark.kind()==NearbyProjectionHighlights.Kind.WRONG)!=errors)continue;if(priority.contains(mark.position())&&!(errors&&settings.highlightOnTop))continue;var box=new Box(net.minecraft.util.math.BlockPos.fromLong(mark.position())).expand(.003);if(context.frustum()!=null&&!context.frustum().isVisible(box))continue;
            boolean missing=mark.kind()==NearbyProjectionHighlights.Kind.MISSING;
            int color=missing?NearbyProjectionHighlights.MISSING_COLOR:NearbyProjectionHighlights.WRONG_COLOR;
            float opacity=missing?missingAlpha(box,origin,camera,look,settings.highlightRange):alpha;
            if(opacity>0)WorldRenderer.drawBox(matrices,vertices,box,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,opacity);
        }}finally{matrices.pop();buffers.draw(lines);}
    }
    private static final NearbyProjectionHighlights.Kind[] NEARBY_KINDS=NearbyProjectionHighlights.Kind.values();
    private static final dev.betterlitematica.core.Comparison[] COMPARISONS=dev.betterlitematica.core.Comparison.values();
    private static final BoxOrder nearbyOrder=new BoxOrder(.003);
    /**
     * Verification highlights can hold hundreds of thousands of merged boxes. They are indexed into 16³ buckets
     * off the render thread whenever the published list changes; a frame then touches only buckets that are in
     * range and in view, and selects its nearest boxes without sorting the ledger.
     */
    private static final java.util.concurrent.ExecutorService INDEXER=java.util.concurrent.Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"BetterLitematica highlight index");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;});
    private static final int SYNC_INDEX_LIMIT=4096;
    private static List<dev.betterlitematica.core.HighlightCuboids.Box> indexSource=List.of(),indexPendingSource;
    private static dev.betterlitematica.core.HighlightBoxIndex verificationIndex=dev.betterlitematica.core.HighlightBoxIndex.EMPTY;
    private static java.util.concurrent.CompletableFuture<dev.betterlitematica.core.HighlightBoxIndex> indexPending;
    /** At most one build in flight. A finished build is always newer than the one on screen, so it is shown; a newer list starts the next build. */
    private static dev.betterlitematica.core.HighlightBoxIndex verificationIndex(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes){
        if(indexPending!=null&&indexPending.isDone()){
            try{verificationIndex=indexPending.join();indexSource=indexPendingSource;}catch(RuntimeException e){BetterLitematicaClient.LOGGER.warn("Highlight index build failed",e);}
            indexPending=null;indexPendingSource=null;
        }
        if(boxes!=indexSource&&boxes!=indexPendingSource){
            if(boxes.size()<=SYNC_INDEX_LIMIT){releaseVerificationIndex();verificationIndex=dev.betterlitematica.core.HighlightBoxIndex.build(boxes);indexSource=boxes;}
            else if(indexPending==null){indexPendingSource=boxes;indexPending=java.util.concurrent.CompletableFuture.supplyAsync(()->dev.betterlitematica.core.HighlightBoxIndex.build(boxes),INDEXER);}
        }
        return verificationIndex;
    }
    static void releaseVerificationIndex(){
        if(indexPending!=null)indexPending.cancel(false);indexPending=null;indexPendingSource=null;
        indexSource=List.of();verificationIndex=dev.betterlitematica.core.HighlightBoxIndex.EMPTY;
    }
    /** Frustum classification: whole buckets inside the view skip per-box tests; boxes only ask "intersects". */
    private record FrustumTest(Frustum frustum) implements dev.betterlitematica.core.HighlightBoxIndex.BoundsTest {
        public int test(double x0,double y0,double z0,double x1,double y1,double z1){
            if(frustum==null)return dev.betterlitematica.core.HighlightBoxIndex.INSIDE;
            if(!frustum.isVisible(new Box(x0,y0,z0,x1,y1,z1)))return dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE;
            // A convex volume contains a box exactly when it contains all eight corners.
            for(int c=0;c<8;c++){double x=(c&1)!=0?x1:x0,y=(c&2)!=0?y1:y0,z=(c&4)!=0?z1:z0;if(!frustum.isVisible(new Box(x,y,z,x,y,z)))return dev.betterlitematica.core.HighlightBoxIndex.INTERSECTS;}
            return dev.betterlitematica.core.HighlightBoxIndex.INSIDE;
        }
        @Override public boolean intersects(double x0,double y0,double z0,double x1,double y1,double z1){return frustum==null||frustum.isVisible(new Box(x0,y0,z0,x1,y1,z1));}
    }
    /** Frustum intersected with the visible layer slab; both convex, so the combination classifies exactly. */
    private record LayerViewTest(FrustumTest view,int axis,double from,double to) implements dev.betterlitematica.core.HighlightBoxIndex.BoundsTest {
        public int test(double x0,double y0,double z0,double x1,double y1,double z1){
            int slab=slab(x0,y0,z0,x1,y1,z1);if(slab==dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE)return slab;
            int sight=view.test(x0,y0,z0,x1,y1,z1);return sight==dev.betterlitematica.core.HighlightBoxIndex.INSIDE&&slab==dev.betterlitematica.core.HighlightBoxIndex.INSIDE?sight:sight==dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE?sight:dev.betterlitematica.core.HighlightBoxIndex.INTERSECTS;
        }
        @Override public boolean intersects(double x0,double y0,double z0,double x1,double y1,double z1){return slab(x0,y0,z0,x1,y1,z1)!=dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE&&view.intersects(x0,y0,z0,x1,y1,z1);}
        private int slab(double x0,double y0,double z0,double x1,double y1,double z1){
            if(axis<0)return dev.betterlitematica.core.HighlightBoxIndex.INSIDE;
            double a=axis==0?x0:axis==1?y0:z0,b=axis==0?x1:axis==1?y1:z1;
            return b<=from||a>=to?dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE:a>=from&&b<=to?dev.betterlitematica.core.HighlightBoxIndex.INSIDE:dev.betterlitematica.core.HighlightBoxIndex.INTERSECTS;
        }
        /** Cell-exclusive bound along {@code a} of a box clipped to the slab; draw extents stay within the layer. */
        double low(int a,double value){return a==axis?Math.max(value,from):value;}
        double high(int a,double value){return a==axis?Math.min(value,to):value;}
    }
    private static int[] errorSlots=new int[256];private static float[] errorAlpha=new float[256];private static int errorCount;
    private static final class PreparedBox {
        private final dev.betterlitematica.core.HighlightCuboids.Box source;
        private final Box bounds;
        private int color;private float alpha;private Box drawn;
        PreparedBox(dev.betterlitematica.core.HighlightCuboids.Box source,Box bounds){this.source=source;this.bounds=bounds;}
        dev.betterlitematica.core.HighlightCuboids.Box source(){return source;}
        Box bounds(){return bounds;}
    }
    /** Geometry is immutable; retain unchanged cells when the live snapshot changes. */
    private static final class BoxOrder {
        private final double expansion;
        private List<dev.betterlitematica.core.HighlightCuboids.Box> source=List.of();
        private List<PreparedBox> prepared=List.of(),sorted=List.of();
        private net.minecraft.util.math.BlockPos eye;
        BoxOrder(double expansion){this.expansion=expansion;}
        List<PreparedBox> get(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,net.minecraft.util.math.Vec3d camera,int limit){
            if(source!=boxes){
                var previous=new java.util.IdentityHashMap<dev.betterlitematica.core.HighlightCuboids.Box,PreparedBox>();
                for(var box:prepared)previous.put(box.source(),box);
                var next=new java.util.ArrayList<PreparedBox>(boxes.size());
                for(var box:boxes){var cached=previous.get(box);next.add(cached!=null?cached:new PreparedBox(box,bounds(box).expand(expansion)));}
                source=boxes;prepared=next;sorted=List.of();eye=null;
            }
            if(limit==0)return prepared;
            var next=net.minecraft.util.math.BlockPos.ofFloored(camera);
            if(!next.equals(eye)){eye=next;var result=new java.util.ArrayList<>(prepared);result.sort(java.util.Comparator.comparingDouble(b->distance(b.source(),camera)));sorted=result;}
            return sorted;
        }
        void clear(){source=List.of();prepared=List.of();sorted=List.of();eye=null;}
    }
    private static final java.util.ArrayList<PreparedBox> nearbyMissing=new java.util.ArrayList<>(),nearbyErrors=new java.util.ArrayList<>();
    static void clearCaches(){nearbyOrder.clear();releaseVerificationIndex();errorSlots=new int[256];errorAlpha=new float[256];errorCount=0;nearbyMissing.clear();nearbyErrors.clear();}
    /** One selection for both passes preserves the shared limit and their draw order. */
    static void prepareNearby(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,PrinterSettings settings,int extraColor,double[] clip){
        nearbyMissing.clear();nearbyErrors.clear();
        if(client.player==null||context.matrixStack()==null)return;
        var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickDelta());
        var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());
        double rangeSquared=settings.highlightRange*(double)settings.highlightRange;int selected=0;
        float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        for(var prepared:nearbyOrder.get(boxes,origin,settings.highlightLimit)){
            var box=prepared.source();var bounds=prepared.bounds();
            // Scanned regardless of layer; the visible slab clips them here so they glide with it instead of rescanning.
            int axis=(int)clip[0];if(axis>=0){double a=axis==0?bounds.minX:axis==1?bounds.minY:bounds.minZ,b=axis==0?bounds.maxX:axis==1?bounds.maxY:bounds.maxZ;if(b<=clip[1]||a>=clip[2])continue;
                if(a<clip[1]||b>clip[2]){double lo=Math.max(a,clip[1]),hi=Math.min(b,clip[2]);bounds=axis==0?new Box(lo,bounds.minY,bounds.minZ,hi,bounds.maxY,bounds.maxZ):axis==1?new Box(bounds.minX,lo,bounds.minZ,bounds.maxX,hi,bounds.maxZ):new Box(bounds.minX,bounds.minY,lo,bounds.maxX,bounds.maxY,hi);}}
            if(distance(box,origin)>rangeSquared||context.frustum()!=null&&!context.frustum().isVisible(bounds))continue;
            if(settings.highlightLimit>0&&selected++>=settings.highlightLimit)break;
            var kind=NEARBY_KINDS[box.group()];boolean missing=kind==NearbyProjectionHighlights.Kind.MISSING;
            int color=missing?NearbyProjectionHighlights.MISSING_COLOR:kind==NearbyProjectionHighlights.Kind.WRONG?NearbyProjectionHighlights.WRONG_COLOR:extraColor;
            float alpha=missing?missingAlpha(bounds,origin,camera,look,settings.highlightRange):pulse;
            if(alpha>0){prepared.color=color;prepared.alpha=alpha;prepared.drawn=bounds;(missing?nearbyMissing:nearbyErrors).add(prepared);}
        }
    }
    private static Box bounds(dev.betterlitematica.core.HighlightCuboids.Box b){return new Box(b.min().x(),b.min().y(),b.min().z(),(double)b.max().x()+1,(double)b.max().y()+1,(double)b.max().z()+1);}
    private static double distance(dev.betterlitematica.core.HighlightCuboids.Box b,net.minecraft.util.math.Vec3d p){
        double x=Math.max(b.min().x()-p.x,Math.max(0,p.x-b.max().x()-1)),y=Math.max(b.min().y()-p.y,Math.max(0,p.y-b.max().y()-1)),z=Math.max(b.min().z()-p.z,Math.max(0,p.z-b.max().z()-1));return x*x+y*y+z*z;
    }
    private static float missingAlpha(Box box,net.minecraft.util.math.Vec3d eye,net.minecraft.util.math.Vec3d camera,net.minecraft.util.math.Vec3d look,double range){
        return missingAlpha(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ,eye,camera,look,range);
    }
    private static float missingAlpha(double minX,double minY,double minZ,double maxX,double maxY,double maxZ,net.minecraft.util.math.Vec3d eye,net.minecraft.util.math.Vec3d camera,net.minecraft.util.math.Vec3d look,double range){
        double x=Math.max(minX-eye.x,Math.max(0,eye.x-maxX)),y=Math.max(minY-eye.y,Math.max(0,eye.y-maxY)),z=Math.max(minZ-eye.z,Math.max(0,eye.z-maxZ));
        double dx=(minX+maxX)*.5-camera.x,dy=(minY+maxY)*.5-camera.y,dz=(minZ+maxZ)*.5-camera.z;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz),alignment=length<1e-6?1:(dx*look.x+dy*look.y+dz*look.z)/length;
        return HighlightFades.missing(Math.sqrt(x*x+y*y+z*z),range,alignment);
    }
    static void drawNearby(MinecraftClient client,WorldRenderContext context,PrinterSettings settings,boolean errors){
        var boxes=errors?nearbyErrors:nearbyMissing;
        if(boxes.isEmpty()||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPos();
        var buffers=client.getBufferBuilders().getEntityVertexConsumers();var layer=OverlayLayers.nearLines(settings.highlightOnTop);
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(layer);for(var box:boxes){int color=box.color;
            WorldRenderer.drawBox(matrices,vertices,box.drawn,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,box.alpha);
        }}finally{matrices.pop();buffers.draw(layer);}
    }
    static void errorBoxes(MinecraftClient client,WorldRenderContext context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,DisplayOptions settings,boolean onTop,int limit,double[] clip){
        if(client.player==null||boxes.isEmpty()||context.matrixStack()==null)return;
        var index=verificationIndex(boxes);if(index.size()==0)return;
        var camera=context.camera().getPos();var origin=client.player.getCameraPosVec(context.tickDelta());var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());
        // Verification spans the whole placement; only the visible layers are drawn, clipped at the layer planes.
        var test=new LayerViewTest(new FrustumTest(context.frustum()),(int)clip[0],clip[1],clip[2]);float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        // Select first, then emit lines and faces as two batches: alternating layers per box flushes a draw call each time.
        errorCount=0;
        if(limit>0){
            if(errorSlots.length<limit){errorSlots=new int[limit];errorAlpha=new float[limit];}
            int selected=index.nearest(origin.x,origin.y,origin.z,128*128,limit,test,errorSlots);
            for(int i=0;i<selected;i++)keepError(index,errorSlots[i],test,origin,camera,look,pulse);
        }else index.forEach(origin.x,origin.y,origin.z,128*128,test,(slot,distance)->keepError(index,slot,test,origin,camera,look,pulse));
        if(errorCount==0)return;
        var matrices=context.matrixStack();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.nearLines(onTop);var faces=OverlayLayers.nearFaces(onTop);
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED){var vertices=buffers.getBuffer(lines);
                for(int i=0;i<errorCount;i++){int slot=errorSlots[i],color=errorColor(index,slot,settings);
                    WorldRenderer.drawBox(matrices,vertices,test.low(0,index.minX(slot))-.002,test.low(1,index.minY(slot))-.002,test.low(2,index.minZ(slot))-.002,test.high(0,index.maxX(slot)+1.0)+.002,test.high(1,index.maxY(slot)+1.0)+.002,test.high(2,index.maxZ(slot)+1.0)+.002,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,errorAlpha[i]);}
                buffers.draw(lines);}
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.peek().getPositionMatrix();
                for(int i=0;i<errorCount;i++){int slot=errorSlots[i],color=errorColor(index,slot,settings);float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=errorAlpha[i]*.25f;
                    float x0=(float)(test.low(0,index.minX(slot))-.002),y0=(float)(test.low(1,index.minY(slot))-.002),z0=(float)(test.low(2,index.minZ(slot))-.002),x1=(float)(test.high(0,index.maxX(slot)+1.0)+.002),y1=(float)(test.high(1,index.maxY(slot)+1.0)+.002),z1=(float)(test.high(2,index.maxZ(slot)+1.0)+.002);
                    for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,(corner&1)!=0?x1:x0,(corner&2)!=0?y1:y0,(corner&4)!=0?z1:z0).color(r,g,b,a).next();}
                buffers.draw(faces);}
        }finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
    /** Same order and limit semantics as before: a box selected for the limit counts even if its fade is zero. */
    private static void keepError(dev.betterlitematica.core.HighlightBoxIndex index,int slot,LayerViewTest test,net.minecraft.util.math.Vec3d origin,net.minecraft.util.math.Vec3d camera,net.minecraft.util.math.Vec3d look,float pulse){
        float a=COMPARISONS[index.box(slot).group()]==dev.betterlitematica.core.Comparison.MISSING?missingAlpha(test.low(0,index.minX(slot))-.002,test.low(1,index.minY(slot))-.002,test.low(2,index.minZ(slot))-.002,test.high(0,index.maxX(slot)+1.0)+.002,test.high(1,index.maxY(slot)+1.0)+.002,test.high(2,index.maxZ(slot)+1.0)+.002,origin,camera,look,128):pulse;
        if(a<=0)return;
        if(errorCount==errorSlots.length){errorSlots=java.util.Arrays.copyOf(errorSlots,errorCount*2);errorAlpha=java.util.Arrays.copyOf(errorAlpha,errorCount*2);}
        errorSlots[errorCount]=slot;errorAlpha[errorCount++]=a;
    }
    private static int errorColor(dev.betterlitematica.core.HighlightBoxIndex index,int slot,DisplayOptions settings){
        return switch(COMPARISONS[index.box(slot).group()]){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case EXTRA->settings.extraColor;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;default->0xff999999;};
    }
    static void actions(MinecraftClient client,WorldRenderContext context,java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> marks,PrinterSettings settings){
        if(client.player==null||marks.isEmpty()||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPos();var origin=client.player.getEyePos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(settings.highlightOnTop);var faces=OverlayLayers.faces(settings.highlightOnTop);long now=System.nanoTime();
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> ordered=marks;if(settings.highlightLimit>0){var sorted=new java.util.ArrayList<>(marks);sorted.sort(java.util.Comparator.comparingDouble(m->PrinterReach.distanceSquared(origin,net.minecraft.util.math.BlockPos.fromLong(m.position()))));ordered=sorted;}
            // Lines then faces: switching layers per mark would flush a draw call each time.
            for(int pass=0;pass<2;pass++){if(pass==0?settings.highlightStyle==PrinterSettings.HighlightStyle.FILLED:settings.highlightStyle==PrinterSettings.HighlightStyle.OUTLINE)continue;var vertices=buffers.getBuffer(pass==0?lines:faces);int drawn=0;for(var mark:ordered){var pos=net.minecraft.util.math.BlockPos.fromLong(mark.position());if(PrinterReach.distanceSquared(origin,pos)>settings.highlightRange*(double)settings.highlightRange)continue;if(settings.highlightLimit>0&&drawn++>=settings.highlightLimit)break;int color=switch(mark.kind()){case PLACE->settings.placeColor;case ADJUST->settings.adjustColor;case BREAK->settings.breakColor;case FAILED->settings.failedColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,alpha=(color>>>24)/255f*HighlightFades.action(mark,now);if(alpha<=0)continue;
                if(pass==0)WorldRenderer.drawBox(matrices,vertices,pos.getX()-.003,pos.getY()-.003,pos.getZ()-.003,pos.getX()+1.003,pos.getY()+1.003,pos.getZ()+1.003,r,g,b,alpha);
                else{var matrix=matrices.peek().getPositionMatrix();for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,pos.getX()+((corner&1)!=0?1.003f:-0.003f),pos.getY()+((corner&2)!=0?1.003f:-0.003f),pos.getZ()+((corner&4)!=0?1.003f:-0.003f)).color(r,g,b,alpha*0.25f).next();}
            }buffers.draw(pass==0?lines:faces);}}finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
    static void selection(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target){
        selection(client,context,selection,target,false);
    }
    static void selection(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target,boolean allNodes){
        if(selection.boxes().isEmpty()||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var vertices=buffers.getBuffer(RenderLayer.getLines());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{for(var box:selection.boxes()){var r=box.region();boolean selected=box.name().equals(selection.selected());WorldRenderer.drawBox(matrices,vertices,new Box(r.min().x(),r.min().y(),r.min().z(),(double)r.min().x()+r.size().x(),(double)r.min().y()+r.size().y(),(double)r.min().z()+r.size().z()),selected?0:1,1,selected?1:0,1);if(selected||allNodes){var a=box.first();var b=box.second();WorldRenderer.drawBox(matrices,vertices,new Box(a.x()-0.1,a.y()-0.1,a.z()-0.1,a.x()+1.1,a.y()+1.1,a.z()+1.1),0.25f,0.6f,1f,1f);WorldRenderer.drawBox(matrices,vertices,new Box(b.x()-0.1,b.y()-0.1,b.z()-0.1,b.x()+1.1,b.y()+1.1,b.z()+1.1),1f,0.7f,0.2f,1f);}}
            var o=selection.origin();boolean origin=target!=null&&target.part()==dev.betterlitematica.core.SelectionTarget.Part.ORIGIN;WorldRenderer.drawBox(matrices,vertices,new Box(o.x()-0.2,o.y()-0.2,o.z()-0.2,o.x()+0.2,o.y()+0.2,o.z()+0.2),1,origin?1:0.2f,origin?1:0.2f,1);
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
                if(settings.regionBounds&&drawn<1024&&(context.frustum()==null||context.frustum().isVisible(box))){WorldRenderer.drawBox(matrices,vertices,box,active?.3f:.5f,.8f,1,.8f);drawn++;}
                if(settings.origins&&settings.regionBounds&&drawn<1024){var pos=part.transform().apply(part.region().anchor()).add(offset);WorldRenderer.drawBox(matrices,vertices,new Box(pos.x()-.15,pos.y()-.15,pos.z()-.15,pos.x()+.15,pos.y()+.15,pos.z()+.15),1,.7f,.2f,1);drawn++;}
            }
            if((settings.placementBounds||toolBounds)&&total!=null&&(context.frustum()==null||context.frustum().isVisible(total)))WorldRenderer.drawBox(matrices,vertices,total,active?0:0.4f,active?1:.7f,1,active?1:.55f);
            if(settings.origins||toolBounds&&active){var pos=layout.placement().transform().origin().add(offset);WorldRenderer.drawBox(matrices,vertices,new Box(pos.x()-.25,pos.y()-.25,pos.z()-.25,pos.x()+.25,pos.y()+.25,pos.z()+.25),1,.3f,.4f,1);}
        }}finally{matrices.pop();buffers.draw(layer);}
    }
    static void marker(MinecraftClient client,WorldRenderContext context,dev.betterlitematica.core.Vec3i pos){
        if(pos==null||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPos();var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(true);matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{WorldRenderer.drawBox(matrices,buffers.getBuffer(lines),new Box(pos.x()-.01,pos.y()-.01,pos.z()-.01,pos.x()+1.01,pos.y()+1.01,pos.z()+1.01),1,.9f,.25f,1);}finally{matrices.pop();buffers.draw(lines);}
    }
    static void errors(MinecraftClient client,WorldRenderContext context,List<VerificationReport.Sample> samples,DisplayOptions settings,boolean onTop){
        if(client.player==null)return;var origin=client.player.getCameraPosVec(context.tickDelta());var camera=context.camera().getPos();var look=net.minecraft.util.math.Vec3d.fromPolar(context.camera().getPitch(),context.camera().getYaw());var matrices=context.matrixStack();if(matrices==null)return;var buffers=client.getBufferBuilders().getEntityVertexConsumers();var lines=OverlayLayers.lines(onTop);var faces=OverlayLayers.faces(onTop);float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);try{int drawn=0;for(var sample:samples){var p=sample.position();if(origin.squaredDistanceTo(p.x(),p.y(),p.z())>128*128)continue;
            int color=switch(sample.key().type()){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;case EXTRA->settings.extraColor;case UNKNOWN->0xaa999999;default->settings.wrongBlockColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=(color>>>24)/255f;
            if(sample.key().type()==dev.betterlitematica.core.Comparison.MISSING)a=missingAlpha(new Box(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1),origin,camera,look,128);
            else if(sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_BLOCK||sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_STATE)a=pulse;
            if(a<=0)continue;
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED)WorldRenderer.drawBox(matrices,buffers.getBuffer(lines),new Box(p.x()-.002,p.y()-.002,p.z()-.002,p.x()+1.002,p.y()+1.002,p.z()+1.002),r,g,b,a);
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.peek().getPositionMatrix();for(var face:FACE_CORNERS)for(int corner:face)vertices.vertex(matrix,p.x()+((corner&1)!=0?1.002f:-.002f),p.y()+((corner&2)!=0?1.002f:-.002f),p.z()+((corner&4)!=0?1.002f:-.002f)).color(r,g,b,a*.25f).next();}
            if(++drawn>=2048)break;
        }}finally{matrices.pop();buffers.draw(lines);buffers.draw(faces);}
    }
}
