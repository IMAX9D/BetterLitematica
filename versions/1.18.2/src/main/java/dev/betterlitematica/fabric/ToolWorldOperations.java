package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit creative world operations. Server work never touches the client world or GPU. */
final class ToolWorldOperations implements AutoCloseable {
    record Result(long changed,AreaSelection selection,boolean confirmed,String message){}
    private record Prepared(SchematicDocument document,Path recovery,List<UUID> entities){}
    private final MinecraftClient client;private final ProjectionController controller;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"betterlitematica-tool-snapshot");t.setDaemon(true);return t;});
    private volatile Job active;private boolean closed;private String lastStatus="";
    ToolWorldOperations(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    CompletableFuture<Result> executeFill(AreaSelection selection,BlockStateSpec target,BlockStateSpec match,boolean deleteEntities){
        Objects.requireNonNull(target);var boxes=operationBoxes(selection);return start(new Job(selection,boxes,null,target,match,deleteEntities));
    }
    CompletableFuture<Result> moveSelection(AreaSelection selection,Vec3i destinationOrigin,boolean includeEntities){
        Objects.requireNonNull(destinationOrigin);validateMove(selection,destinationOrigin);return start(new Job(selection,selection.boxes(),destinationOrigin,null,null,includeEntities));
    }
    static List<SelectionBox> operationBoxes(AreaSelection area){return area.selected().isEmpty()?area.boxes():List.of(area.current());}
    static AreaSelection validateMove(AreaSelection area,Vec3i destination){
        if(area.boxes().isEmpty())throw new IllegalArgumentException("请先选择区域");long volume=0;
        for(int i=0;i<area.boxes().size();i++){var region=area.boxes().get(i).region();volume=Math.addExact(volume,region.volume());for(int j=0;j<i;j++)if(region.intersects(area.boxes().get(j).region()))throw new IllegalArgumentException("移动选区的区域不能互相重叠");}
        if(volume>4_194_304)throw new IllegalArgumentException("移动上限为 4194304 格");
        return area.translated(destination.subtract(area.origin()));
    }
    private CompletableFuture<Result> start(Job job){
        if(closed)throw new IllegalStateException("工具任务已关闭");if(controller.worldWriteBusy())throw new IllegalStateException("已有施工任务");
        if(client.player==null||client.world==null||!client.player.isCreative()||client.getNetworkHandler()==null)throw new IllegalStateException("需要创造模式");
        job.clientWorld=client.world;job.connection=client.getNetworkHandler();job.server=client.getServer();job.player=client.player.getUuid();job.dimension=client.world.getRegistryKey();
        if(job.server==null)job.prepareCommands();active=job;
        return job.result;
    }
    boolean busy(){return active!=null&&!active.result.isDone();}
    String status(){var job=active;return job==null?lastStatus:job.status;}
    void cancel(){var job=active;if(job!=null)job.cancelled=true;}
    void tick(){var job=active;if(job==null)return;
        if(client.world!=job.clientWorld||client.getNetworkHandler()!=job.connection||client.player==null||!client.player.isCreative()||client.getServer()!=job.server){job.cancelled=true;job.cancel();}
        if(job.server==null&&!job.result.isDone())job.commands();
        if(job.result.isDone()){lastStatus=job.status;active=null;}
    }
    void serverTick(MinecraftServer server){var job=active;if(job!=null&&job.server==server&&!job.result.isDone())job.serverStep();}
    @Override public void close(){closed=true;cancel();var job=active;if(job!=null)job.cancel();io.shutdownNow();}

    private final class Job {
        final AreaSelection selection;final List<SelectionBox> boxes;final Vec3i destination;final BlockStateSpec target,match;final boolean entities;
        final CompletableFuture<Result> result=new CompletableFuture<>();
        ClientWorld clientWorld;net.minecraft.client.network.ClientPlayNetworkHandler connection;MinecraftServer server;UUID player;net.minecraft.util.registry.RegistryKey<net.minecraft.world.World> dimension;
        volatile boolean cancelled;volatile String status="准备工具任务";volatile Path recovery;
        ServerWorld world;WorldCapture capture;CreativeFillTask fill;CreativePasteTask paste;CompletableFuture<Prepared> preparing;Prepared prepared;ChunkAdmission admission;int phase,entityCursor;long ticks,sent;
        CommandPages pages;Region pendingKill;List<Region> regions;CommandSettings settings;
        Job(AreaSelection selection,List<SelectionBox> boxes,Vec3i destination,BlockStateSpec target,BlockStateSpec match,boolean entities){this.selection=selection;this.boxes=List.copyOf(boxes);this.destination=destination;this.target=target;this.match=match;this.entities=entities;if(boxes.isEmpty()||boxes.stream().mapToLong(b->b.region().volume()).sum()>16_777_216)throw new IllegalArgumentException("选区为空或超过 16777216 格");result.whenComplete((value,failure)->{if(result.isCancelled()){cancelled=true;cancel();}});}
        boolean moving(){return destination!=null;}
        void cancel(){
            if(capture!=null)capture.cancel();if(fill!=null)fill.cancel();if(paste!=null)paste.cancel();
            status="已取消"+(phase>=2?"，已写入内容保留":"")+(recovery==null?"":"；源备份："+recovery.getFileName());
            result.complete(new Result(0,selection,false,status));
        }
        void fail(Throwable error){status="工具操作失败："+Objects.toString(error.getMessage(),error.getClass().getSimpleName())+(recovery==null?"":"；源备份："+recovery.getFileName());result.completeExceptionally(new IllegalStateException(status,error));}
        void success(String text,boolean confirmed){status=text;result.complete(new Result(confirmed?-1:sent,moving()?validateMove(selection,destination):selection,confirmed,text));}
        void serverStep(){
            if(cancelled){cancel();return;}
            try{
                var actor=server.getPlayerManager().getPlayer(player);world=server.getWorld(dimension);
                if(actor==null||!actor.isCreative()||world==null||actor.getWorld()!=world){cancelled=true;cancel();return;}
                if(phase==0){
                    validateBounds(world,boxes);if(moving())validateBounds(world,validateMove(selection,destination).boxes());
                    if(moving()){capture=new WorldCapture(world,boxes);phase=1;}
                    else{fill=new CreativeFillTask(world,player,boxes,target,match);phase=2;}
                    return;
                }
                if(phase==1){
                    if(capture!=null){capture.tick();status=capture.status();if(!capture.result().isDone())return;var captures=capture.result().join();capture=null;preparing=prepareMove(captures);status="保存移动前快照";return;}
                    if(prepared==null){if(!preparing.isDone())return;prepared=preparing.join();preparing=null;recovery=prepared.recovery();
                        var domains=new ArrayList<Region>();for(var box:boxes)domains.add(box.region());for(var box:validateMove(selection,destination).boxes())domains.add(box.region());admission=new ChunkAdmission(domains);}
                    if(!admission.tick(p->world.isChunkLoaded(p.x(),p.z()),256,System.nanoTime()+2_000_000L)){status="移动等待源或目标区块";return;}
                    // Construct and validate the destination task before touching the original region.
                    var placement=recoveryPlacement(recovery,destination);
                    paste=new CreativePasteTask(world,player,prepared.document(),placement,LayerRange.ALL,ReplaceRule.ALL,entities,true);
                    fill=new CreativeFillTask(world,player,boxes,BlockStateSpec.AIR,null);phase=2;return;
                }
                if(phase==2){fill.tick();status=fill.status();if(!fill.result().isDone())return;fill.result().join();fill=null;phase=3;return;}
                if(phase==3){
                    if(entities){if(moving()){int budget=32;long deadline=System.nanoTime()+2_000_000L;while(entityCursor<prepared.entities().size()&&budget-->0&&System.nanoTime()<deadline){var entity=world.getEntity(prepared.entities().get(entityCursor++));if(entity!=null&&!(entity instanceof PlayerEntity))entity.discard();}if(entityCursor<prepared.entities().size())return;}
                        else{if(!deleteEntitiesStep())return;}}
                    if(!moving()){success("工具操作完成",true);return;}phase=4;
                }
                if(phase==4){paste.tick();status=paste.status();if(paste.result().isDone()){paste.result().join();paste=null;success("移动完成；源备份："+recovery.getFileName(),true);}}
            }catch(Throwable failure){fail(failure);}
        }
        CompletableFuture<Prepared> prepareMove(List<LitematicExport.Capture> captures){
            var future=new CompletableFuture<Prepared>();try{io.execute(()->{try{
                var folder=FabricLoader.getInstance().getGameDir().resolve("schematics/.betterlitematica-tool-recovery");Files.createDirectories(folder);int count=0;long bytes=0;
                try(var files=Files.newDirectoryStream(folder,"*.litematic")){for(var file:files){if(++count>=16||(bytes+=Files.size(file))>384L*1024*1024)throw new IllegalStateException("移动备份空间已满，请整理 .betterlitematica-tool-recovery");}}
                Cancellation check=()->cancelled||Thread.currentThread().isInterrupted();check.check();Path output=folder.resolve("move-"+UUID.randomUUID()+".litematic");
                var data=LitematicExport.create("移动前备份","",net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId(),selection.origin(),captures);NbtWriter.writeNew(output,data,check);recovery=output;
                if(Files.size(output)>128L*1024*1024)throw new IllegalStateException("移动备份超过 128 MiB，未修改世界");
                var document=SchematicDocument.read(output,check);var ids=new ArrayList<UUID>();if(entities)for(var part:document.parts())for(var tag:part.entities())collectIds((NbtCompound)NbtBridge.game(tag),ids,0);
                future.complete(new Prepared(document,output,List.copyOf(ids)));
            }catch(Throwable failure){future.completeExceptionally(failure);}});}catch(RejectedExecutionException failure){future.completeExceptionally(failure);}return future;
        }
        private int deleteBox,deleteX,deleteZ;private boolean deleteReady;
        boolean deleteEntitiesStep(){
            long deadline=System.nanoTime()+2_000_000L;int budget=32,queries=8;
            while(deleteBox<boxes.size()&&budget>0&&queries-->0&&System.nanoTime()<deadline){var region=boxes.get(deleteBox).region();int minX=region.min().x()>>4,minZ=region.min().z()>>4,maxX=(region.min().x()+region.size().x()-1)>>4,maxZ=(region.min().z()+region.size().z()-1)>>4;
                if(!deleteReady){deleteX=minX;deleteZ=minZ;deleteReady=true;}if(!world.isChunkLoaded(deleteX,deleteZ)){status="删除实体等待区块";return false;}
                var bounds=new net.minecraft.util.math.Box(Math.max(region.min().x(),deleteX*16),region.min().y(),Math.max(region.min().z(),deleteZ*16),Math.min((long)region.min().x()+region.size().x(),(long)deleteX*16+16),region.min().y()+region.size().y(),Math.min((long)region.min().z()+region.size().z(),(long)deleteZ*16+16));
                var found=new ArrayList<net.minecraft.entity.Entity>();LegacyGame.collect(world,net.minecraft.util.TypeFilter.instanceOf(net.minecraft.entity.Entity.class),bounds,e->!(e instanceof PlayerEntity)&&bounds.contains(e.getPos()),found,budget+1);int removed=0;while(removed<found.size()&&budget>0&&System.nanoTime()<deadline){found.get(removed++).discard();budget--;}if(removed<found.size())return false;
                if(++deleteX>maxX){deleteX=minX;if(++deleteZ>maxZ){deleteReady=false;deleteBox++;}}
            }return deleteBox==boxes.size();
        }
        void prepareCommands(){
            settings=controller.options().commands.copy();settings.validate();regions=boxes.stream().map(SelectionBox::region).toList();
            if(moving()){if(boxes.size()!=1||entities||regions.get(0).volume()>32768)throw new IllegalArgumentException("多人移动仅支持不带实体的单区域，最多 32768 格");requireCommand("clone");}
            else{requireCommand("fill");if(entities)requireCommand("kill");}
            for(var box:boxes)validateClientBounds(box.region());if(moving())for(var box:validateMove(selection,destination).boxes())validateClientBounds(box.region());pages=new CommandPages(regions,settings.fillVolume);
            if(!moving()){var resolver=new StateResolver1201(match==null?List.of(target):List.of(target,match),new PlacementTransform(Vec3i.ZERO,0,false,false));if(resolver.unresolved(0)||match!=null&&resolver.unresolved(1))throw new IllegalArgumentException("未知方块状态");}
        }
        void requireCommand(String name){if(connection.getCommandDispatcher().getRoot().getChild(name)==null)throw new IllegalStateException("需要服务器 "+name+" 权限");}
        void validateClientBounds(Region region){for(var at:corners(region))if(at.y()<clientWorld.getBottomY()||at.y()>=clientWorld.getTopY()||!clientWorld.getWorldBorder().contains(new BlockPos(at.x(),at.y(),at.z())))throw new IllegalArgumentException("选区超出世界范围");}
        void commands(){
            if(cancelled){cancel();return;}try{
                if(++ticks%settings.interval!=0)return;long deadline=System.nanoTime()+2_000_000L;int budget=settings.perTick;
                if(moving()){
                    var region=regions.get(0);var offset=destination.subtract(selection.origin());for(var box:List.of(region,new Region("destination",region.min().add(offset),region.size())))if(!loadedRegion(box)){status="移动等待区块";return;}
                    String line=cloneCommand(region,offset);requireCommand("clone");client.player.sendChatMessage("/"+line);sent++;success("移动命令已发送，服务端结果未确认",false);return;
                }
                while((pendingKill!=null||!pages.done())&&budget-->0&&System.nanoTime()<deadline){
                    if(pendingKill!=null){if(!loadedRegion(pendingKill)){status="删除实体等待区块";return;}requireCommand("kill");client.player.sendChatMessage("/"+deleteCommand(pendingKill));pendingKill=null;sent++;continue;}
                    var page=pages.current();if(!loadedRegion(page)){status="工具操作等待区块";return;}
                    String line="fill "+xyz(page.min())+" "+xyz(page.min().add(page.size()).subtract(new Vec3i(1,1,1)))+" "+target+(match==null?" replace":" replace "+match);if(line.length()>256)throw new IllegalArgumentException("方块状态超过命令长度上限");requireCommand("fill");client.player.sendChatMessage("/"+line);sent++;pages.advance();if(entities)pendingKill=page;phase=2;
                }
                if(pages.done()&&pendingKill==null){
                    success("工具命令已发送 "+sent+" 条，服务端结果未确认",false);
                }else status="已发送 "+sent+" 条命令";
            }catch(Throwable failure){fail(failure);}
        }
        boolean loadedRegion(Region region){int minX=region.min().x()>>4,minZ=region.min().z()>>4,maxX=(region.min().x()+region.size().x()-1)>>4,maxZ=(region.min().z()+region.size().z()-1)>>4;for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(!WorldChunks.loaded(clientWorld,x,z))return false;return true;}
    }
    /** Readiness is completed before the first destructive step; never force-loads a target. */
    static final class ChunkAdmission {
        private final List<Region> regions;private int part,x,z;private boolean initialized;
        ChunkAdmission(List<Region> regions){this.regions=List.copyOf(regions);}
        boolean tick(java.util.function.Predicate<Vec3i> loaded,int maxChecks,long deadline){
            while(part<regions.size()&&maxChecks-->0&&System.nanoTime()<deadline){var r=regions.get(part);int minX=Math.floorDiv(r.min().x(),16),minZ=Math.floorDiv(r.min().z(),16),maxX=Math.floorDiv(Math.addExact(r.min().x(),r.size().x()-1),16),maxZ=Math.floorDiv(Math.addExact(r.min().z(),r.size().z()-1),16);
                if(!initialized){x=minX;z=minZ;initialized=true;}if(!loaded.test(new Vec3i(x,0,z)))return false;
                if(++x>maxX){x=minX;if(++z>maxZ){part++;initialized=false;}}
            }return part==regions.size();
        }
    }
    /** Fixed-size 3D pages; tail pages cannot shift the next row or exceed the command volume. */
    static final class CommandPages {
        private final List<Region> regions;private final int volume;private int part,x,y,z,sx,sy,sz;
        CommandPages(List<Region> regions,int volume){if(volume<1||volume>32768)throw new IllegalArgumentException("命令体积无效");this.regions=List.copyOf(regions);this.volume=volume;strides();}
        boolean done(){return part==regions.size();}
        private void strides(){if(done())return;var size=regions.get(part).size();sx=Math.min(16,Math.min(size.x(),volume));sz=Math.min(16,Math.min(size.z(),volume/sx));sy=Math.min(16,Math.min(size.y(),volume/(sx*sz)));}
        Region current(){var r=regions.get(part);return new Region(r.name(),r.min().add(new Vec3i(x,y,z)),new Vec3i(Math.min(sx,r.size().x()-x),Math.min(sy,r.size().y()-y),Math.min(sz,r.size().z()-z)));}
        void advance(){var r=regions.get(part);if((x+=sx)>=r.size().x()){x=0;if((z+=sz)>=r.size().z()){z=0;if((y+=sy)>=r.size().y()){y=0;part++;strides();}}}}
    }
    static Placement recoveryPlacement(Path recovery,Vec3i destination){return new Placement(UUID.randomUUID(),"move",".betterlitematica-tool-recovery/"+recovery.getFileName(),new PlacementTransform(destination,0,false,false),true,false);}
    static String xyz(Vec3i p){return p.x()+" "+p.y()+" "+p.z();}
    static String cloneCommand(Region region,Vec3i offset){return "clone "+xyz(region.min())+" "+xyz(region.min().add(region.size()).subtract(new Vec3i(1,1,1)))+" "+xyz(region.min().add(offset))+" replace move";}
    static String deleteCommand(Region r){return "kill @e[type=!minecraft:player,x="+r.min().x()+",y="+r.min().y()+",z="+r.min().z()+",dx="+(r.size().x()-1)+",dy="+(r.size().y()-1)+",dz="+(r.size().z()-1)+"]";}
    static void collectIds(NbtCompound tag,List<UUID> ids,int depth){if(depth>32||ids.size()>=8192||!tag.containsUuid("UUID"))throw new IllegalArgumentException("实体快照超过预算或缺少身份");ids.add(tag.getUuid("UUID"));for(var value:tag.getList("Passengers",NbtElement.COMPOUND_TYPE))collectIds((NbtCompound)value,ids,depth+1);}
    static List<Vec3i> corners(Region r){return List.of(r.min(),r.min().add(r.size()).subtract(new Vec3i(1,1,1)));}
    static void validateBounds(ServerWorld world,List<SelectionBox> boxes){for(var box:boxes)for(var p:corners(box.region()))if(p.y()<world.getBottomY()||p.y()>=world.getTopY()||!world.getWorldBorder().contains(new BlockPos(p.x(),p.y(),p.z())))throw new IllegalArgumentException("选区超出世界范围");}
}
