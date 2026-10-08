package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.TemporarySources;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.material.MapColor;

/** One bounded preview worker. Registry colors are sampled in small main-thread slices. */
final class FilePreviews implements AutoCloseable {
    private final PreviewCache cache;
    private final OrbitPreviewCache orbitCache;
    private final OrbitPreviewCache detailCache;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),r->{var thread=new Thread(r,"betterlitematica-previews");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;});
    private final ThreadPoolExecutor orbitWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),r->{var thread=new Thread(r,"betterlitematica-preview-orbit");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;});
    private final Set<Orbit> orbits=ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> tasks=ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<PaletteJob> palettes=new ArrayBlockingQueue<>(2);
    private final Map<BlockStateSpec,Integer> colorCache=new HashMap<>();
    private volatile boolean closed;
    private final AtomicLong generated=new AtomicLong(),cached=new AtomicLong(),embedded=new AtomicLong();
    private final AtomicLong sourceReads=new AtomicLong(),modelBuilds=new AtomicLong(),modelHits=new AtomicLong(),orbitFrames=new AtomicLong();
    private final AtomicLong detailBuilds=new AtomicLong(),detailHits=new AtomicLong();
    private long colorNanos;
    record Stats(long generated,long cacheHits,long embeddedHits,int tasks,long colorNanos,long sourceReads,long modelBuilds,long modelHits,long orbitFrames,int orbits,long detailBuilds,long detailHits){}
    record DetailSource(TemporarySources.Reference reference,Map<String,RegionPlacement> regions,String sha,String key,BasicFileAttributes stamp){}
    record Preview(SchematicPreview.Images images,SchematicPreview.OrbitModel model,DetailSource source){Preview(SchematicPreview.Images images,SchematicPreview.OrbitModel model){this(images,model,null);}}
    Stats stats(){return new Stats(generated.get(),cached.get(),embedded.get(),tasks.size(),colorNanos,sourceReads.get(),modelBuilds.get(),modelHits.get(),orbitFrames.get(),orbits.size(),detailBuilds.get(),detailHits.get());}
    private static final class PaletteJob {
        final List<BlockStateSpec> palette;final StateResolver1201 resolver;
        final int[] colors;final CompletableFuture<int[]> result=new CompletableFuture<>();int cursor;
        PaletteJob(List<BlockStateSpec> palette){this.palette=palette;colors=new int[palette.size()];resolver=new StateResolver1201(palette,new PlacementTransform(Vec3i.ZERO,0,false,false));}
    }
    FilePreviews(Path directory){cache=new PreviewCache(directory.resolve("previews"));orbitCache=new OrbitPreviewCache(directory.resolve("preview-models"));detailCache=new OrbitPreviewCache(directory.resolve("preview-detail-models-v1"),true);}

    private <T> CompletableFuture<T> submit(Work<T> work){
        var result=new CompletableFuture<T>();if(closed)return CompletableFuture.failedFuture(new IOException("预览已关闭"));
        worker.purge();tasks.add(result);
        try {
            Future<?> running=worker.submit(()->{try{Cancellation cancel=()->result.isCancelled()||closed||Thread.currentThread().isInterrupted();cancel.check();T value=work.run(cancel);cancel.check();result.complete(value);}catch(Exception e){result.completeExceptionally(e);}});
            result.whenComplete((value,error)->{tasks.remove(result);if(result.isCancelled()){running.cancel(true);worker.purge();}});
        } catch(RejectedExecutionException e){tasks.remove(result);result.completeExceptionally(new IOException("预览任务繁忙，请重试",e));}
        return result;
    }
    @FunctionalInterface private interface Work<T>{T run(Cancellation cancel)throws Exception;}

    CompletableFuture<SchematicPreview.Images> request(TemporarySources.Reference reference){
        return submit(cancel->load(reference,false,Map.of(),cancel).images());
    }
    CompletableFuture<Preview> interactive(TemporarySources.Reference reference){return submit(cancel->load(reference,true,Map.of(),cancel));}
    CompletableFuture<Preview> interactive(TemporarySources.Reference reference,Map<String,RegionPlacement> regions){var snapshot=SchematicPreview.geometryOverrides(regions);return submit(cancel->load(reference,true,snapshot,cancel));}
    private Preview load(TemporarySources.Reference reference,boolean interactive,Map<String,RegionPlacement> regions,Cancellation cancel)throws IOException{
            Path source=reference.read();var stamp=Files.readAttributes(source,BasicFileAttributes.class);
            if(stamp.size()>SchematicImporter.MAX_SOURCE_BYTES)throw new IOException("投影超过预览读取上限");
            String sha=SchematicImporter.sha256(source,cancel),key=SchematicPreview.configuredKey(sha,regions);
            var detailSource=new DetailSource(reference,regions,sha,key,stamp);
            SchematicPreview.Images images=null;
            try{images=cache.read(key,cancel);}catch(InterruptedIOException e){throw e;}catch(IOException e){BetterLitematicaClient.LOGGER.debug("Preview cache unavailable",e);}
            if(images!=null)cached.incrementAndGet();
            SchematicPreview.OrbitModel model=null;
            if(interactive)try{model=orbitCache.read(key,cancel);if(model!=null)modelHits.incrementAndGet();}catch(InterruptedIOException e){throw e;}catch(IOException e){BetterLitematicaClient.LOGGER.debug("Orbit cache unavailable",e);}
            if(images!=null&&(!interactive||model!=null)){unchanged(source,stamp);return new Preview(images,model,detailSource);}
            Map<String,Object> root=NbtReader.readPartial(source,SchematicImporter.SOURCE_LIMITS,cancel,FilePreviews::omitAuxiliary);
            sourceReads.incrementAndGet();
            if(images==null&&regions.isEmpty()){images=SchematicPreview.embedded(root,cancel);if(images!=null)embedded.incrementAndGet();}
            if(images==null||interactive&&model==null){
                var geometry=SchematicPreview.readGeometry(root,regions,cancel);root=null;int[] colors=colors(geometry,cancel);
                if(images==null){images=SchematicPreview.render(geometry,colors,cancel);generated.incrementAndGet();}
                if(interactive&&model==null){model=SchematicPreview.createOrbitModel(geometry,colors,cancel);modelBuilds.incrementAndGet();}
            }
            unchanged(source,stamp);store(key,images,cancel);
            if(model!=null)try{orbitCache.write(key,model,cancel);}catch(InterruptedIOException e){throw e;}catch(IOException e){BetterLitematicaClient.LOGGER.debug("Orbit model could not be cached",e);}
            return new Preview(images,model,detailSource);
    }
    private static boolean omitAuxiliary(List<String> path){
        if(path.isEmpty()||path.size()==2&&path.get(0).equals("Regions"))return false;
        return switch(path.get(path.size()-1)){case "Entities","entities","TileEntities","BlockEntities","PendingBlockTicks","PendingFluidTicks"->true;default->false;};
    }
    private static void unchanged(Path source,BasicFileAttributes previous)throws IOException{
        var now=Files.readAttributes(source,BasicFileAttributes.class);
        if(now.size()!=previous.size()||!now.lastModifiedTime().equals(previous.lastModifiedTime())||!Objects.equals(now.fileKey(),previous.fileKey()))throw new IOException("投影文件已改变，请重新选择");
    }
    private void store(String key,SchematicPreview.Images images,Cancellation cancel)throws IOException{
        try{cache.write(key,images,cancel);}catch(InterruptedIOException e){throw e;}catch(IOException e){BetterLitematicaClient.LOGGER.warn("Preview could not be cached",e);}
    }

    /** Captured root is private to this save. It is never a live game object or an existing file. */
    CompletableFuture<SchematicPreview.Images> attach(Map<String,Object> root,Cancellation owner){
        return submit(cancel->{Cancellation together=()->cancel.cancelled()||owner.cancelled();var images=generate(SchematicPreview.readGeometry(root,together),together);SchematicPreview.embed(root,images,together);return images;});
    }
    void remember(Path source,SchematicPreview.Images images,Cancellation cancel)throws IOException{store(SchematicImporter.sha256(source,cancel),images,cancel);}
    private SchematicPreview.Images generate(SchematicPreview.Geometry geometry,Cancellation cancel)throws IOException{
        var images=SchematicPreview.render(geometry,colors(geometry,cancel),cancel);generated.incrementAndGet();return images;
    }
    private int[] colors(SchematicPreview.Geometry geometry,Cancellation cancel)throws IOException{
        var job=new PaletteJob(geometry.palette());
        if(!palettes.offer(job))throw new IOException("预览调色任务繁忙");
        try{return await(job.result,cancel);}
        finally{job.result.cancel(false);palettes.remove(job);}
    }
    static <T> T await(CompletableFuture<T> future,Cancellation cancel)throws IOException{
        try{while(true){cancel.check();try{return future.get(50,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("预览已取消");}
        catch(ExecutionException e){if(e.getCause() instanceof IOException io)throw io;throw new IOException("预览生成失败",e.getCause());}
        finally{if(!future.isDone())future.cancel(true);}
    }
    void tick(){
        long start=System.nanoTime(),deadline=start+700_000L;int budget=128;
        while(budget-->0&&System.nanoTime()<deadline){
            var job=palettes.peek();if(job==null)break;if(job.result.isDone()){palettes.remove(job);continue;}
            try{
                if(job.cursor<job.colors.length){int i=job.cursor++;var spec=job.palette.get(i);Integer known=colorCache.get(spec);
                    if(known==null){var state=job.resolver.resolve(i);var color=state.getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);known=0xff000000|(color==MapColor.NONE?0xc6d3df:color.col);if(colorCache.size()<4096)colorCache.put(spec,known);}
                    job.colors[i]=known;
                }
                if(job.cursor==job.colors.length){job.result.complete(job.colors);palettes.remove(job);}
            }catch(RuntimeException e){job.result.completeExceptionally(e);palettes.remove(job);}
        }
        colorNanos+=System.nanoTime()-start;
    }
    Orbit orbit(SchematicPreview.OrbitModel model){
        return orbit(new Preview(null,model));
    }
    Orbit orbit(Preview preview){
        if(closed||orbits.size()>=2)throw new IllegalStateException("预览已关闭或繁忙");
        var orbit=new Orbit(Objects.requireNonNull(preview.model()),preview.source());orbits.add(orbit);return orbit;
    }
    /** One latest-angle slot and one completed-frame slot; rapid input never queues old angles. */
    final class Orbit implements AutoCloseable {
        private SchematicPreview.OrbitModel model;private SchematicPreview.OrbitModel posed;private PlacementTransform preparedTransform;
        private final DetailSource source;
        private volatile SchematicPreview.OrbitModel detail;
        private SchematicPreview.OrbitModel posedDetail;private PlacementTransform detailTransform;
        private CompletableFuture<SchematicPreview.OrbitModel> detailWork;
        private volatile Angle latest;
        private final AtomicReference<Angle> wanted=new AtomicReference<>();
        private final AtomicReference<Frame> ready=new AtomicReference<>();
        private final AtomicLong sequences=new AtomicLong();
        private final AtomicBoolean scheduled=new AtomicBoolean();
        private volatile boolean released;private volatile Future<?> running;
        record Frame(long sequence,double yaw,double pitch,boolean dragging,int[] pixels,PlacementTransform transform,int side,double zoom,double panX,double panY,boolean detailed){}
        private record Angle(long sequence,double yaw,double pitch,boolean dragging,PlacementTransform transform,int side,double zoom,double panX,double panY,boolean camera){}
        private Orbit(SchematicPreview.OrbitModel model,DetailSource source){this.model=model;this.source=source;}
        long request(double yaw,double pitch,boolean dragging){return request(yaw,pitch,dragging,new PlacementTransform(Vec3i.ZERO,0,false,false));}
        long request(double yaw,double pitch,boolean dragging,PlacementTransform transform){return request(yaw,pitch,1,0,0,256,dragging,transform,false);}
        long request(double yaw,double pitch,double zoom,double panX,double panY,int side,boolean dragging,PlacementTransform transform){return request(yaw,pitch,zoom,panX,panY,side,dragging,transform,true);}
        private long request(double yaw,double pitch,double zoom,double panX,double panY,int side,boolean dragging,PlacementTransform transform,boolean camera){
            if(released||closed)return 0;SchematicPreview.validateCamera(yaw,pitch,zoom,panX,panY,side);
            var linear=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ());long sequence=sequences.incrementAndGet();
            var angle=new Angle(sequence,yaw,pitch,dragging,linear,side,zoom,panX,panY,camera);latest=angle;wanted.set(angle);
            if(camera&&!dragging&&zoom*side>512)prepareDetail();schedule();return sequence;
        }
        private synchronized void prepareDetail(){
            if(source==null||detail!=null||detailWork!=null||released)return;
            // At most two live panels, each with one fine grid (<=8M cells). The shared IO worker
            // builds only one at a time; camera gestures never queue scans or retain source arrays.
            detailWork=submit(cancel->{
                Path path=source.reference().read();unchanged(path,source.stamp());
                if(!source.sha().equals(SchematicImporter.sha256(path,cancel)))throw new IOException("投影文件已改变，请重新选择");
                var cachedModel=detailCache.read(source.key(),cancel);
                if(cachedModel!=null){unchanged(path,source.stamp());detailHits.incrementAndGet();return cachedModel;}
                var root=NbtReader.readPartial(path,SchematicImporter.SOURCE_LIMITS,cancel,FilePreviews::omitAuxiliary);sourceReads.incrementAndGet();
                var geometry=SchematicPreview.readGeometry(root,source.regions(),SchematicPreview.DETAIL_LOD,cancel);root=null;
                var fine=SchematicPreview.createOrbitModel(geometry,colors(geometry,cancel),cancel);unchanged(path,source.stamp());
                detailBuilds.incrementAndGet();
                try{detailCache.write(source.key(),fine,cancel);}catch(InterruptedIOException e){throw e;}catch(IOException e){BetterLitematicaClient.LOGGER.debug("Fine preview cache unavailable",e);}
                return fine;
            });
            detailWork.whenComplete((value,error)->{
                synchronized(Orbit.this){
                    if(released||closed)return;
                    if(error!=null){BetterLitematicaClient.LOGGER.debug("Fine preview unavailable; retaining base preview",error);return;}
                    detail=value;var target=latest;if(target!=null)wanted.accumulateAndGet(target,(pending,next)->pending==null||pending.sequence()<=next.sequence()?next:pending);schedule();
                }
            });
        }
        Frame poll(){if(released)return null;Frame frame=ready.getAndSet(null);schedule();return frame;}
        private void schedule(){
            if(released||closed||wanted.get()==null||ready.get()!=null||!scheduled.compareAndSet(false,true))return;
            try{running=orbitWorker.submit(()->{
                try{var angle=wanted.getAndSet(null);if(angle==null||released||closed)return;
                    Cancellation cancel=()->released||closed||Thread.currentThread().isInterrupted()||(!angle.dragging()&&angle.sequence()!=sequences.get());var base=model;if(base==null)return;
                    if(!angle.transform().equals(preparedTransform)){posed=null;preparedTransform=null;var prepared=SchematicPreview.posedOrbitModel(base,angle.transform(),cancel);cancel.check();posed=prepared;preparedTransform=angle.transform();}
                    var renderModel=posed;var fine=detail;
                    // Navigation keeps the best resident geometry. Lower raster resolution during
                    // gestures is cheap; replacing leaves/buildings with the 64-cell drag grid is not.
                    boolean detailed=fine!=null&&angle.camera();
                    if(detailed){if(!angle.transform().equals(detailTransform)){posedDetail=null;detailTransform=null;posedDetail=SchematicPreview.posedOrbitModel(fine,angle.transform(),cancel);detailTransform=angle.transform();}renderModel=posedDetail;}
                    var pixels=angle.camera()?SchematicPreview.renderOrbit(renderModel,posed,angle.yaw(),angle.pitch(),angle.zoom(),angle.panX(),angle.panY(),angle.side(),false,cancel):SchematicPreview.renderOrbit(posed,angle.yaw(),angle.pitch(),angle.dragging(),cancel);
                    if(!released&&!closed){ready.set(new Frame(angle.sequence(),angle.yaw(),angle.pitch(),angle.dragging(),pixels,angle.transform(),angle.side(),angle.zoom(),angle.panX(),angle.panY(),detailed));orbitFrames.incrementAndGet();}
                }catch(InterruptedIOException ignored){}catch(Exception failure){BetterLitematicaClient.LOGGER.warn("Orbit preview failed",failure);}
                finally{if(released){model=null;posed=null;detail=null;posedDetail=null;preparedTransform=null;}scheduled.set(false);if(!released&&ready.get()==null)schedule();}
            });if(released)running.cancel(true);}
            catch(RejectedExecutionException unavailable){scheduled.set(false);}
        }
        @Override public synchronized void close(){released=true;latest=null;wanted.set(null);ready.set(null);if(detailWork!=null){detailWork.cancel(true);detailWork=null;}detail=null;var task=running;if(task!=null)task.cancel(true);orbitWorker.purge();if(!scheduled.get()){model=null;posed=null;posedDetail=null;preparedTransform=null;}orbits.remove(this);}
    }
    void cancelAll(){for(var task:List.copyOf(tasks))task.cancel(true);for(var palette:palettes)palette.result.cancel(false);palettes.clear();worker.purge();for(var orbit:List.copyOf(orbits))orbit.close();}
    @Override public void close(){closed=true;cancelAll();worker.shutdownNow();orbitWorker.shutdownNow();colorCache.clear();}
}
