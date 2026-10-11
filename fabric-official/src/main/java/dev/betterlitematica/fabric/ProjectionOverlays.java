package dev.betterlitematica.fabric;

import dev.betterlitematica.core.VerificationReport;
import dev.betterlitematica.core.HighlightFades;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import java.util.List;

final class ProjectionOverlays {
    private ProjectionOverlays() {}
    private static final int[][] FACE_CORNERS={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
    static void nearby(Minecraft client,ProjectionFrame context,java.util.Collection<NearbyProjectionHighlights.Mark> marks,java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> actions,PrinterSettings settings,boolean errors){
        if(client.player==null||marks.isEmpty()||context.matrixStack()==null)return;
        var priority=new java.util.HashSet<Long>();for(var action:actions)priority.add(action.position());
        var matrices=context.matrixStack();var camera=context.camera().getPosition();var origin=client.player.getEyePosition(context.tickDelta());var look=net.minecraft.world.phys.Vec3.directionFromRotation(context.camera().getXRot(),context.camera().getYRot());var buffers=OverlayBuffers.of(context);var lines=OverlayLayers.lines(settings.highlightOnTop);float alpha=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(lines);for(var mark:marks){if((mark.kind()==NearbyProjectionHighlights.Kind.WRONG)!=errors)continue;if(priority.contains(mark.position())&&!(errors&&settings.highlightOnTop))continue;var box=new AABB(net.minecraft.core.BlockPos.of(mark.position())).inflate(.003);if(context.frustum()!=null&&!context.frustum().isVisible(box))continue;
            boolean missing=mark.kind()==NearbyProjectionHighlights.Kind.MISSING;
            int color=missing?NearbyProjectionHighlights.MISSING_COLOR:NearbyProjectionHighlights.WRONG_COLOR;
            float opacity=missing?missingAlpha(box,origin,camera,look,settings.highlightRange):alpha;
            if(opacity>0)OverlayBuffers.lineBox(matrices,vertices,box,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,opacity);
        }}finally{matrices.popPose();buffers.endBatch(lines);}
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
            if(!frustum.isVisible(new AABB(x0,y0,z0,x1,y1,z1)))return dev.betterlitematica.core.HighlightBoxIndex.OUTSIDE;
            // A convex volume contains a box exactly when it contains all eight corners.
            for(int c=0;c<8;c++){double x=(c&1)!=0?x1:x0,y=(c&2)!=0?y1:y0,z=(c&4)!=0?z1:z0;if(!frustum.isVisible(new AABB(x,y,z,x,y,z)))return dev.betterlitematica.core.HighlightBoxIndex.INTERSECTS;}
            return dev.betterlitematica.core.HighlightBoxIndex.INSIDE;
        }
        @Override public boolean intersects(double x0,double y0,double z0,double x1,double y1,double z1){return frustum==null||frustum.isVisible(new AABB(x0,y0,z0,x1,y1,z1));}
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
        private final AABB bounds;
        private int color;private float alpha;private AABB drawn;
        PreparedBox(dev.betterlitematica.core.HighlightCuboids.Box source,AABB bounds){this.source=source;this.bounds=bounds;}
        dev.betterlitematica.core.HighlightCuboids.Box source(){return source;}
        AABB bounds(){return bounds;}
    }
    /** Geometry is immutable; retain unchanged cells when the live snapshot changes. */
    private static final class BoxOrder {
        private final double expansion;
        private List<dev.betterlitematica.core.HighlightCuboids.Box> source=List.of();
        private List<PreparedBox> prepared=List.of(),sorted=List.of();
        private net.minecraft.core.BlockPos eye;
        BoxOrder(double expansion){this.expansion=expansion;}
        List<PreparedBox> get(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,net.minecraft.world.phys.Vec3 camera,int limit){
            if(source!=boxes){
                var previous=new java.util.IdentityHashMap<dev.betterlitematica.core.HighlightCuboids.Box,PreparedBox>();
                for(var box:prepared)previous.put(box.source(),box);
                var next=new java.util.ArrayList<PreparedBox>(boxes.size());
                for(var box:boxes){var cached=previous.get(box);next.add(cached!=null?cached:new PreparedBox(box,bounds(box).inflate(expansion)));}
                source=boxes;prepared=next;sorted=List.of();eye=null;
            }
            if(limit==0)return prepared;
            var next=net.minecraft.core.BlockPos.containing(camera);
            if(!next.equals(eye)){eye=next;var result=new java.util.ArrayList<>(prepared);result.sort(java.util.Comparator.comparingDouble(b->distance(b.source(),camera)));sorted=result;}
            return sorted;
        }
        void clear(){source=List.of();prepared=List.of();sorted=List.of();eye=null;}
    }
    private static final java.util.ArrayList<PreparedBox> nearbyMissing=new java.util.ArrayList<>(),nearbyErrors=new java.util.ArrayList<>();
    static void clearCaches(){nearbyOrder.clear();outlineSource=null;outlineClip=null;outline=null;outlineActive=false;if(outlinePending!=null)outlinePending.cancel(false);outlinePending=null;pendingSource=null;pendingClip=null;releaseVerificationIndex();errorSlots=new int[256];errorAlpha=new float[256];errorCount=0;nearbyMissing.clear();nearbyErrors.clear();}
    /**
     * Construction highlights are drawn as the silhouette of each kind's cells: only edges where the region bends,
     * so a run of missing blocks reads as one outlined shape instead of a lattice of frames. The outline is
     * recomputed when the highlighted cells or the visible layer change, never per frame; very large sets fall
     * back to per-box frames.
     */
    private static final int OUTLINE_CELLS=16384;
    private static List<dev.betterlitematica.core.HighlightCuboids.Box> outlineSource;private static double[] outlineClip;
    private static List<dev.betterlitematica.core.HighlightOutline.Segment> outline;
    private static int[] outlineDraw=new int[256],outlineColor=new int[256];private static float[] outlineAlpha=new float[256];
    private static int outlineMissing,outlineErrors;private static boolean outlineActive;
    private static java.util.concurrent.CompletableFuture<List<dev.betterlitematica.core.HighlightOutline.Segment>> outlinePending;
    private static List<dev.betterlitematica.core.HighlightCuboids.Box> pendingSource;private static double[] pendingClip;
    /**
     * Outlines are computed on the highlight worker from the immutable box list. Only a result matching the current
     * request is accepted; until then the previous outline stays on screen (per-box frames before the first one).
     */
    private static List<dev.betterlitematica.core.HighlightOutline.Segment> outline(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,double[] clip){
        if(outlinePending!=null&&outlinePending.isDone()){
            try{var result=outlinePending.join();if(pendingSource==boxes&&java.util.Arrays.equals(pendingClip,clip)){outline=result;outlineSource=boxes;outlineClip=pendingClip;}}
            catch(RuntimeException e){BetterLitematicaClient.LOGGER.warn("Highlight outline failed",e);outlineSource=boxes;outlineClip=clip.clone();outline=null;}
            outlinePending=null;pendingSource=null;pendingClip=null;
        }
        if(!(boxes==outlineSource&&java.util.Arrays.equals(clip,outlineClip))&&outlinePending==null){
            var request=clip.clone();pendingSource=boxes;pendingClip=request;
            outlinePending=java.util.concurrent.CompletableFuture.supplyAsync(()->dev.betterlitematica.core.HighlightOutline.of(clipCells(boxes,request),OUTLINE_CELLS),INDEXER);
        }
        return outlineSource==null?null:outline;
    }
    /** Clip to whole cells of the visible layer first, so the cut itself is outlined. */
    private static List<dev.betterlitematica.core.HighlightCuboids.Box> clipCells(List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,double[] clip){
        int axis=(int)clip[0];if(axis<0)return boxes;
        long from=(long)Math.ceil(clip[1]),to=(long)Math.floor(clip[2])-1;var clipped=new java.util.ArrayList<dev.betterlitematica.core.HighlightCuboids.Box>(boxes.size());
        for(var box:boxes){int[] min={box.min().x(),box.min().y(),box.min().z()},max={box.max().x(),box.max().y(),box.max().z()};
            long lo=Math.max(min[axis],from),hi=Math.min(max[axis],to);if(lo>hi)continue;min[axis]=(int)lo;max[axis]=(int)hi;
            clipped.add(new dev.betterlitematica.core.HighlightCuboids.Box(new dev.betterlitematica.core.Vec3i(min[0],min[1],min[2]),new dev.betterlitematica.core.Vec3i(max[0],max[1],max[2]),box.group()));}
        return clipped;
    }
    private static double segmentDistance(dev.betterlitematica.core.HighlightOutline.Segment s,net.minecraft.world.phys.Vec3 p){
        double[] a={s.x(),s.y(),s.z()};double[] q={p.x,p.y,p.z};double t=Math.max(a[s.axis()],Math.min(a[s.axis()]+s.length(),q[s.axis()]));a[s.axis()]=t;
        double x=a[0]-q[0],y=a[1]-q[1],z=a[2]-q[2];return x*x+y*y+z*z;
    }
    private static AABB segmentBox(dev.betterlitematica.core.HighlightOutline.Segment s){
        double[] min={s.x(),s.y(),s.z()},max={s.x(),s.y(),s.z()};max[s.axis()]+=s.length();return new AABB(min[0]-.01,min[1]-.01,min[2]-.01,max[0]+.01,max[1]+.01,max[2]+.01);
    }
    private static void prepareOutline(Minecraft client,ProjectionFrame context,List<dev.betterlitematica.core.HighlightOutline.Segment> segments,PrinterSettings settings,int extraColor){
        var camera=context.camera().getPosition();var origin=client.player.getEyePosition(context.tickDelta());
        var look=net.minecraft.world.phys.Vec3.directionFromRotation(context.camera().getXRot(),context.camera().getYRot());
        double range=settings.highlightRange,rangeSquared=range*range;float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        var kept=new java.util.ArrayList<Integer>();
        for(int i=0;i<segments.size();i++){var s=segments.get(i);if(segmentDistance(s,origin)>rangeSquared||context.frustum()!=null&&!context.frustum().isVisible(segmentBox(s)))continue;kept.add(i);}
        // The limit counts boxes; an outlined box has about four visible edges.
        if(settings.highlightLimit>0&&kept.size()>settings.highlightLimit*4){kept.sort(java.util.Comparator.comparingDouble(i->segmentDistance(segments.get(i),origin)));kept.subList(settings.highlightLimit*4,kept.size()).clear();}
        if(outlineDraw.length<kept.size()){outlineDraw=new int[kept.size()];outlineColor=new int[kept.size()];outlineAlpha=new float[kept.size()];}
        outlineMissing=0;outlineErrors=0;int n=0;
        // Missing first, then wrong and extra, matching the two draw passes.
        for(int pass=0;pass<2;pass++)for(int i:kept){var s=segments.get(i);var kind=NEARBY_KINDS[s.group()];boolean missing=kind==NearbyProjectionHighlights.Kind.MISSING;if(missing!=(pass==0))continue;
            float alpha;
            if(missing){double[] m={s.x(),s.y(),s.z()};m[s.axis()]+=s.length()*.5;double dx=m[0]-camera.x,dy=m[1]-camera.y,dz=m[2]-camera.z,length=Math.sqrt(dx*dx+dy*dy+dz*dz);
                alpha=HighlightFades.missing(Math.sqrt(segmentDistance(s,origin)),range,length<1e-6?1:(dx*look.x+dy*look.y+dz*look.z)/length);}
            else alpha=pulse;
            if(alpha<=0)continue;
            outlineDraw[n]=i;outlineColor[n]=missing?NearbyProjectionHighlights.MISSING_COLOR:kind==NearbyProjectionHighlights.Kind.WRONG?NearbyProjectionHighlights.WRONG_COLOR:extraColor;outlineAlpha[n]=alpha;n++;
            if(missing)outlineMissing++;else outlineErrors++;
        }
    }
    private static void drawOutline(Minecraft client,ProjectionFrame context,PrinterSettings settings,boolean errors){
        int from=errors?outlineMissing:0,to=errors?outlineMissing+outlineErrors:outlineMissing;
        if(from>=to||context.matrixStack()==null||outline==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPosition();
        var buffers=OverlayBuffers.of(context);var layer=OverlayLayers.nearLines(settings.highlightOnTop);
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(layer);var entry=matrices.last();
            for(int k=from;k<to;k++){var s=outline.get(outlineDraw[k]);int color=outlineColor[k];float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=outlineAlpha[k];
                // Nudge the line off the block faces toward the empty side so it never z-fights them.
                float[] p={s.x(),s.y(),s.z()};p[(s.axis()+1)%3]+=s.du()*.003f;p[(s.axis()+2)%3]+=s.dv()*.003f;
                float[] q=p.clone();q[s.axis()]+=s.length();float nx=s.axis()==0?1:0,ny=s.axis()==1?1:0,nz=s.axis()==2?1:0;
                vertices.addVertex(entry.pose(),p[0],p[1],p[2]).setColor(r,g,b,a).setNormal(entry,nx,ny,nz);
                vertices.addVertex(entry.pose(),q[0],q[1],q[2]).setColor(r,g,b,a).setNormal(entry,nx,ny,nz);
            }
        }finally{matrices.popPose();buffers.endBatch(layer);}
    }
    /** One selection for both passes preserves the shared limit and their draw order. */
    static void prepareNearby(Minecraft client,ProjectionFrame context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,PrinterSettings settings,int extraColor,double[] clip){
        nearbyMissing.clear();nearbyErrors.clear();outlineActive=false;
        if(client.player==null||context.matrixStack()==null)return;
        var segments=outline(boxes,clip);
        if(segments!=null){outlineActive=true;prepareOutline(client,context,segments,settings,extraColor);return;}
        var camera=context.camera().getPosition();var origin=client.player.getEyePosition(context.tickDelta());
        var look=net.minecraft.world.phys.Vec3.directionFromRotation(context.camera().getXRot(),context.camera().getYRot());
        double rangeSquared=settings.highlightRange*(double)settings.highlightRange;int selected=0;
        float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        for(var prepared:nearbyOrder.get(boxes,origin,settings.highlightLimit)){
            var box=prepared.source();var bounds=prepared.bounds();
            // Scanned regardless of layer; the visible slab clips them here so they glide with it instead of rescanning.
            int axis=(int)clip[0];if(axis>=0){double a=axis==0?bounds.minX:axis==1?bounds.minY:bounds.minZ,b=axis==0?bounds.maxX:axis==1?bounds.maxY:bounds.maxZ;if(b<=clip[1]||a>=clip[2])continue;
                if(a<clip[1]||b>clip[2]){double lo=Math.max(a,clip[1]),hi=Math.min(b,clip[2]);bounds=axis==0?new AABB(lo,bounds.minY,bounds.minZ,hi,bounds.maxY,bounds.maxZ):axis==1?new AABB(bounds.minX,lo,bounds.minZ,bounds.maxX,hi,bounds.maxZ):new AABB(bounds.minX,bounds.minY,lo,bounds.maxX,bounds.maxY,hi);}}
            if(distance(box,origin)>rangeSquared||context.frustum()!=null&&!context.frustum().isVisible(bounds))continue;
            if(settings.highlightLimit>0&&selected++>=settings.highlightLimit)break;
            var kind=NEARBY_KINDS[box.group()];boolean missing=kind==NearbyProjectionHighlights.Kind.MISSING;
            int color=missing?NearbyProjectionHighlights.MISSING_COLOR:kind==NearbyProjectionHighlights.Kind.WRONG?NearbyProjectionHighlights.WRONG_COLOR:extraColor;
            float alpha=missing?missingAlpha(bounds,origin,camera,look,settings.highlightRange):pulse;
            if(alpha>0){prepared.color=color;prepared.alpha=alpha;prepared.drawn=bounds;(missing?nearbyMissing:nearbyErrors).add(prepared);}
        }
    }
    private static AABB bounds(dev.betterlitematica.core.HighlightCuboids.Box b){return new AABB(b.min().x(),b.min().y(),b.min().z(),(double)b.max().x()+1,(double)b.max().y()+1,(double)b.max().z()+1);}
    private static double distance(dev.betterlitematica.core.HighlightCuboids.Box b,net.minecraft.world.phys.Vec3 p){
        double x=Math.max(b.min().x()-p.x,Math.max(0,p.x-b.max().x()-1)),y=Math.max(b.min().y()-p.y,Math.max(0,p.y-b.max().y()-1)),z=Math.max(b.min().z()-p.z,Math.max(0,p.z-b.max().z()-1));return x*x+y*y+z*z;
    }
    private static float missingAlpha(AABB box,net.minecraft.world.phys.Vec3 eye,net.minecraft.world.phys.Vec3 camera,net.minecraft.world.phys.Vec3 look,double range){
        return missingAlpha(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ,eye,camera,look,range);
    }
    private static float missingAlpha(double minX,double minY,double minZ,double maxX,double maxY,double maxZ,net.minecraft.world.phys.Vec3 eye,net.minecraft.world.phys.Vec3 camera,net.minecraft.world.phys.Vec3 look,double range){
        double x=Math.max(minX-eye.x,Math.max(0,eye.x-maxX)),y=Math.max(minY-eye.y,Math.max(0,eye.y-maxY)),z=Math.max(minZ-eye.z,Math.max(0,eye.z-maxZ));
        double dx=(minX+maxX)*.5-camera.x,dy=(minY+maxY)*.5-camera.y,dz=(minZ+maxZ)*.5-camera.z;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz),alignment=length<1e-6?1:(dx*look.x+dy*look.y+dz*look.z)/length;
        return HighlightFades.missing(Math.sqrt(x*x+y*y+z*z),range,alignment);
    }
    static void drawNearby(Minecraft client,ProjectionFrame context,PrinterSettings settings,boolean errors){
        if(outlineActive){drawOutline(client,context,settings,errors);return;}
        var boxes=errors?nearbyErrors:nearbyMissing;
        if(boxes.isEmpty()||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPosition();
        var buffers=OverlayBuffers.of(context);var layer=OverlayLayers.nearLines(settings.highlightOnTop);
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{var vertices=buffers.getBuffer(layer);for(var box:boxes){int color=box.color;
            OverlayBuffers.lineBox(matrices,vertices,box.drawn,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,box.alpha);
        }}finally{matrices.popPose();buffers.endBatch(layer);}
    }
    static void errorBoxes(Minecraft client,ProjectionFrame context,List<dev.betterlitematica.core.HighlightCuboids.Box> boxes,DisplayOptions settings,boolean onTop,int limit,double[] clip){
        if(client.player==null||boxes.isEmpty()||context.matrixStack()==null)return;
        var index=verificationIndex(boxes);if(index.size()==0)return;
        var camera=context.camera().getPosition();var origin=client.player.getEyePosition(context.tickDelta());var look=net.minecraft.world.phys.Vec3.directionFromRotation(context.camera().getXRot(),context.camera().getYRot());
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
        var matrices=context.matrixStack();var buffers=OverlayBuffers.of(context);var lines=OverlayLayers.nearLines(onTop);var faces=OverlayLayers.nearFaces(onTop);
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED){var vertices=buffers.getBuffer(lines);
                for(int i=0;i<errorCount;i++){int slot=errorSlots[i],color=errorColor(index,slot,settings);
                    OverlayBuffers.lineBox(matrices,vertices,test.low(0,index.minX(slot))-.002,test.low(1,index.minY(slot))-.002,test.low(2,index.minZ(slot))-.002,test.high(0,index.maxX(slot)+1.0)+.002,test.high(1,index.maxY(slot)+1.0)+.002,test.high(2,index.maxZ(slot)+1.0)+.002,((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,errorAlpha[i]);}
                buffers.endBatch(lines);}
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.last().pose();
                for(int i=0;i<errorCount;i++){int slot=errorSlots[i],color=errorColor(index,slot,settings);float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=errorAlpha[i]*.25f;
                    float x0=(float)(test.low(0,index.minX(slot))-.002),y0=(float)(test.low(1,index.minY(slot))-.002),z0=(float)(test.low(2,index.minZ(slot))-.002),x1=(float)(test.high(0,index.maxX(slot)+1.0)+.002),y1=(float)(test.high(1,index.maxY(slot)+1.0)+.002),z1=(float)(test.high(2,index.maxZ(slot)+1.0)+.002);
                    for(var face:FACE_CORNERS)for(int corner:face)vertices.addVertex(matrix,(corner&1)!=0?x1:x0,(corner&2)!=0?y1:y0,(corner&4)!=0?z1:z0).setColor(r,g,b,a);}
                buffers.endBatch(faces);}
        }finally{matrices.popPose();buffers.endBatch(lines);buffers.endBatch(faces);}
    }
    /** Same order and limit semantics as before: a box selected for the limit counts even if its fade is zero. */
    private static void keepError(dev.betterlitematica.core.HighlightBoxIndex index,int slot,LayerViewTest test,net.minecraft.world.phys.Vec3 origin,net.minecraft.world.phys.Vec3 camera,net.minecraft.world.phys.Vec3 look,float pulse){
        float a=COMPARISONS[index.box(slot).group()]==dev.betterlitematica.core.Comparison.MISSING?missingAlpha(test.low(0,index.minX(slot))-.002,test.low(1,index.minY(slot))-.002,test.low(2,index.minZ(slot))-.002,test.high(0,index.maxX(slot)+1.0)+.002,test.high(1,index.maxY(slot)+1.0)+.002,test.high(2,index.maxZ(slot)+1.0)+.002,origin,camera,look,128):pulse;
        if(a<=0)return;
        if(errorCount==errorSlots.length){errorSlots=java.util.Arrays.copyOf(errorSlots,errorCount*2);errorAlpha=java.util.Arrays.copyOf(errorAlpha,errorCount*2);}
        errorSlots[errorCount]=slot;errorAlpha[errorCount++]=a;
    }
    private static int errorColor(dev.betterlitematica.core.HighlightBoxIndex index,int slot,DisplayOptions settings){
        return switch(COMPARISONS[index.box(slot).group()]){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case EXTRA->settings.extraColor;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;default->0xff999999;};
    }
    static void actions(Minecraft client,ProjectionFrame context,java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> marks,PrinterSettings settings){
        if(client.player==null||marks.isEmpty()||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPosition();var origin=client.player.getEyePosition();var buffers=OverlayBuffers.of(context);var lines=OverlayLayers.lines(settings.highlightOnTop);var faces=OverlayLayers.faces(settings.highlightOnTop);long now=System.nanoTime();
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);try{java.util.Collection<dev.betterlitematica.core.ActionHighlights.Mark> ordered=marks;if(settings.highlightLimit>0){var sorted=new java.util.ArrayList<>(marks);sorted.sort(java.util.Comparator.comparingDouble(m->PrinterReach.distanceSquared(origin,net.minecraft.core.BlockPos.of(m.position()))));ordered=sorted;}
            // Lines then faces: switching layers per mark would flush a draw call each time.
            for(int pass=0;pass<2;pass++){if(pass==0?settings.highlightStyle==PrinterSettings.HighlightStyle.FILLED:settings.highlightStyle==PrinterSettings.HighlightStyle.OUTLINE)continue;var vertices=buffers.getBuffer(pass==0?lines:faces);int drawn=0;for(var mark:ordered){var pos=net.minecraft.core.BlockPos.of(mark.position());if(PrinterReach.distanceSquared(origin,pos)>settings.highlightRange*(double)settings.highlightRange)continue;if(settings.highlightLimit>0&&drawn++>=settings.highlightLimit)break;int color=switch(mark.kind()){case PLACE->settings.placeColor;case ADJUST->settings.adjustColor;case BREAK->settings.breakColor;case FAILED->settings.failedColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,alpha=(color>>>24)/255f*HighlightFades.action(mark,now);if(alpha<=0)continue;
                if(pass==0)OverlayBuffers.lineBox(matrices,vertices,pos.getX()-.003,pos.getY()-.003,pos.getZ()-.003,pos.getX()+1.003,pos.getY()+1.003,pos.getZ()+1.003,r,g,b,alpha);
                else{var matrix=matrices.last().pose();for(var face:FACE_CORNERS)for(int corner:face)vertices.addVertex(matrix,pos.getX()+((corner&1)!=0?1.003f:-0.003f),pos.getY()+((corner&2)!=0?1.003f:-0.003f),pos.getZ()+((corner&4)!=0?1.003f:-0.003f)).setColor(r,g,b,alpha*0.25f);}
            }buffers.endBatch(pass==0?lines:faces);}}finally{matrices.popPose();buffers.endBatch(lines);buffers.endBatch(faces);}
    }
    static void selection(Minecraft client,ProjectionFrame context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target){
        selection(client,context,selection,target,false);
    }
    static void selection(Minecraft client,ProjectionFrame context,dev.betterlitematica.core.AreaSelection selection,dev.betterlitematica.core.SelectionTarget target,boolean allNodes){
        if(selection.boxes().isEmpty()||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPosition();var buffers=OverlayBuffers.of(context);var vertices=buffers.getBuffer(OverlayLayers.lines(false));
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);
        try{for(var box:selection.boxes()){var r=box.region();boolean selected=box.name().equals(selection.selected());OverlayBuffers.lineBox(matrices,vertices,new AABB(r.min().x(),r.min().y(),r.min().z(),(double)r.min().x()+r.size().x(),(double)r.min().y()+r.size().y(),(double)r.min().z()+r.size().z()),selected?0:1,1,selected?1:0,1);if(selected||allNodes){var a=box.first();var b=box.second();OverlayBuffers.lineBox(matrices,vertices,new AABB(a.x()-0.1,a.y()-0.1,a.z()-0.1,a.x()+1.1,a.y()+1.1,a.z()+1.1),0.25f,0.6f,1f,1f);OverlayBuffers.lineBox(matrices,vertices,new AABB(b.x()-0.1,b.y()-0.1,b.z()-0.1,b.x()+1.1,b.y()+1.1,b.z()+1.1),1f,0.7f,0.2f,1f);}}
            var o=selection.origin();boolean origin=target!=null&&target.part()==dev.betterlitematica.core.SelectionTarget.Part.ORIGIN;OverlayBuffers.lineBox(matrices,vertices,new AABB(o.x()-0.2,o.y()-0.2,o.z()-0.2,o.x()+0.2,o.y()+0.2,o.z()+0.2),1,origin?1:0.2f,origin?1:0.2f,1);
        }
        finally{matrices.popPose();buffers.endBatch(OverlayLayers.lines(false));}
    }
    static void placements(Minecraft client,ProjectionFrame context,List<dev.betterlitematica.core.PlacementLayout> layouts,java.util.UUID selected,DisplayOptions settings){
        placements(client,context,layouts,selected,settings,false,null,dev.betterlitematica.core.Vec3i.ZERO);
    }
    static void placements(Minecraft client,ProjectionFrame context,List<dev.betterlitematica.core.PlacementLayout> layouts,java.util.UUID selected,DisplayOptions settings,boolean toolBounds,java.util.UUID previewId,dev.betterlitematica.core.Vec3i previewOffset){
        if(!toolBounds&&!settings.placementBounds&&!settings.regionBounds&&!settings.origins||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPosition();var buffers=OverlayBuffers.of(context);var layer=OverlayLayers.lines(false);var vertices=buffers.getBuffer(layer);
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);try{int drawn=0;for(var layout:layouts){boolean active=layout.placement().id().equals(selected);AABB total=null;var offset=layout.placement().id().equals(previewId)?previewOffset:dev.betterlitematica.core.Vec3i.ZERO;
            for(int i=0;i<layout.size();i++){if(!layout.enabled(i))continue;var part=layout.part(i);var min=part.bounds().min();var max=part.bounds().max();var box=new AABB(min.x(),min.y(),min.z(),(double)max.x()+1,(double)max.y()+1,(double)max.z()+1).move(offset.x(),offset.y(),offset.z());total=total==null?box:total.minmax(box);
                if(settings.regionBounds&&drawn<1024&&(context.frustum()==null||context.frustum().isVisible(box))){OverlayBuffers.lineBox(matrices,vertices,box,active?.3f:.5f,.8f,1,.8f);drawn++;}
                if(settings.origins&&settings.regionBounds&&drawn<1024){var pos=part.transform().apply(part.region().anchor()).add(offset);OverlayBuffers.lineBox(matrices,vertices,new AABB(pos.x()-.15,pos.y()-.15,pos.z()-.15,pos.x()+.15,pos.y()+.15,pos.z()+.15),1,.7f,.2f,1);drawn++;}
            }
            if((settings.placementBounds||toolBounds)&&total!=null&&(context.frustum()==null||context.frustum().isVisible(total)))OverlayBuffers.lineBox(matrices,vertices,total,active?0:0.4f,active?1:.7f,1,active?1:.55f);
            if(settings.origins||toolBounds&&active){var pos=layout.placement().transform().origin().add(offset);OverlayBuffers.lineBox(matrices,vertices,new AABB(pos.x()-.25,pos.y()-.25,pos.z()-.25,pos.x()+.25,pos.y()+.25,pos.z()+.25),1,.3f,.4f,1);}
        }}finally{matrices.popPose();buffers.endBatch(layer);}
    }
    static void marker(Minecraft client,ProjectionFrame context,dev.betterlitematica.core.Vec3i pos){
        if(pos==null||context.matrixStack()==null)return;var matrices=context.matrixStack();var camera=context.camera().getPosition();var buffers=OverlayBuffers.of(context);var lines=OverlayLayers.lines(true);matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);try{OverlayBuffers.lineBox(matrices,buffers.getBuffer(lines),new AABB(pos.x()-.01,pos.y()-.01,pos.z()-.01,pos.x()+1.01,pos.y()+1.01,pos.z()+1.01),1,.9f,.25f,1);}finally{matrices.popPose();buffers.endBatch(lines);}
    }
    static void errors(Minecraft client,ProjectionFrame context,List<VerificationReport.Sample> samples,DisplayOptions settings,boolean onTop){
        if(client.player==null)return;var origin=client.player.getEyePosition(context.tickDelta());var camera=context.camera().getPosition();var look=net.minecraft.world.phys.Vec3.directionFromRotation(context.camera().getXRot(),context.camera().getYRot());var matrices=context.matrixStack();if(matrices==null)return;var buffers=OverlayBuffers.of(context);var lines=OverlayLayers.lines(onTop);var faces=OverlayLayers.faces(onTop);float pulse=NearbyProjectionHighlights.alpha(System.nanoTime());
        matrices.pushPose();matrices.translate(-camera.x,-camera.y,-camera.z);try{int drawn=0;for(var sample:samples){var p=sample.position();if(origin.distanceToSqr(p.x(),p.y(),p.z())>128*128)continue;
            int color=switch(sample.key().type()){case MISSING->NearbyProjectionHighlights.MISSING_COLOR;case WRONG_BLOCK,WRONG_STATE->NearbyProjectionHighlights.WRONG_COLOR;case EXTRA->settings.extraColor;case UNKNOWN->0xaa999999;default->settings.wrongBlockColor;};float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f,a=(color>>>24)/255f;
            if(sample.key().type()==dev.betterlitematica.core.Comparison.MISSING)a=missingAlpha(new AABB(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1),origin,camera,look,128);
            else if(sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_BLOCK||sample.key().type()==dev.betterlitematica.core.Comparison.WRONG_STATE)a=pulse;
            if(a<=0)continue;
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.FILLED)OverlayBuffers.lineBox(matrices,buffers.getBuffer(lines),new AABB(p.x()-.002,p.y()-.002,p.z()-.002,p.x()+1.002,p.y()+1.002,p.z()+1.002),r,g,b,a);
            if(settings.errorStyle!=PrinterSettings.HighlightStyle.OUTLINE){var vertices=buffers.getBuffer(faces);var matrix=matrices.last().pose();for(var face:FACE_CORNERS)for(int corner:face)vertices.addVertex(matrix,p.x()+((corner&1)!=0?1.002f:-.002f),p.y()+((corner&2)!=0?1.002f:-.002f),p.z()+((corner&4)!=0?1.002f:-.002f)).setColor(r,g,b,a*.25f);}
            if(++drawn>=2048)break;
        }}finally{matrices.popPose();buffers.endBatch(lines);buffers.endBatch(faces);}
    }
}
