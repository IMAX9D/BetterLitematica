package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.SchematicDocument;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded preparation and vanilla commands. Sent is not server-confirmed. */
final class CommandOperation {
    private record Cell(Vec3i at,String state,List<String> tag){}
    private final MinecraftClient client;private final Object world,connection;private final SchematicDocument document;
    private final PlacementLayout layout;private final PlacementLayout.Source cells;private final LayerRange layer;private final ReplaceRule rule;
    private final boolean includeNbt,entities,merge;private final int perTick,interval,fillVolume,limit;private final Path output;
    private final String storage="betterlitematica:"+UUID.randomUUID().toString().replace("-","");
    private final List<StateResolver1201> states=new ArrayList<>();private final List<List<String>> stateText=new ArrayList<>();
    private final DeferredSections sections;private DeferredSections.Work work;
    private final ArrayBlockingQueue<List<String>> outputQueue=new ArrayBlockingQueue<>(4);private final CompletableFuture<String> result=new CompletableFuture<>();
    private final dev.betterlitematica.runtime.NewFileCommit fileCommit=new dev.betterlitematica.runtime.NewFileCommit();
    private final List<BitSet> entitiesDone=new ArrayList<>();private long entitiesRemaining;
    private final dev.betterlitematica.runtime.CommandDispatchQueue pending=new dev.betterlitematica.runtime.CommandDispatchQueue();
    private Cell held;private Vec3i runStart,runEnd,runStep;private String runState;private int runSize;
    private int validationPart,validationState,entityPart,entityIndex,ticks;
    private final ExecutorService preparer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{var thread=new Thread(r,"betterlitematica-command-prepare");thread.setDaemon(true);return thread;});
    private Future<CommandPlans.Prepared> preparing;private CommandPlans.Prepared prepared;
    private boolean preflightDone,blocksDispatched;private long generated;private volatile boolean cancelled,paused,producerDone;private String status="检查命令";
    CommandOperation(MinecraftClient client,SchematicDocument document,Placement placement,LayerRange layer,ReplaceRule rule,boolean nbt,Path output){this(client,document,placement,layer,rule,nbt,false,output,new CommandSettings());}
    CommandOperation(MinecraftClient client,SchematicDocument document,Placement placement,LayerRange layer,ReplaceRule rule,boolean nbt,boolean entities,Path output,CommandSettings settings){
        settings.validate();if(client==null&&output==null)throw new IllegalArgumentException("发送命令需要活动客户端");this.client=client;world=client==null?null:client.world;connection=client==null?null:client.getNetworkHandler();this.document=document;this.layer=layer;this.rule=rule;includeNbt=nbt;this.entities=entities;this.output=output;perTick=settings.perTick;interval=settings.interval;fillVolume=settings.fillVolume;limit=output==null?256:65536;merge=settings.merge&&(output!=null||permission("fill"));
        layout=new PlacementLayout(placement,document.parts().stream().map(SchematicDocument.Part::region).toList());sections=new DeferredSections(document.parts().stream().map(SchematicDocument.Part::region).toList());
        cells=new PlacementLayout.Source(){public int state(PlacementLayout.Part part,Vec3i local){var p=document.parts().get(part.index());var v=local.subtract(p.region().min());return p.blocks().get(v.x()+v.z()*p.region().size().x()+v.y()*p.region().size().x()*p.region().size().z());}public BlockStateSpec spec(int region,int state){return document.parts().get(region).palette().get(state);}};
        for(var p:document.parts()){int i=states.size();states.add(new StateResolver1201(p.palette(),layout.part(i).transform()));stateText.add(new ArrayList<>());var bounds=layout.enabled(i)?PlacementBounds.clipped(p.region(),layout.part(i).transform(),layer):null;if(output==null&&bounds!=null&&(bounds.min().y()<client.world.getBottomY()||bounds.max().y()>=client.world.getTopY()))throw new IllegalArgumentException("投影超出世界高度");}
        for(int i=0;i<document.parts().size();i++){entitiesDone.add(new BitSet());if(entities&&layout.enabled(i))entitiesRemaining+=document.parts().get(i).entities().size();}
        result.whenComplete((value,error)->preparer.shutdownNow());
        if(output!=null){Thread writer=new Thread(this::write,"betterlitematica-command-export");writer.setDaemon(true);writer.start();}
    }
    CompletableFuture<String> result(){return result;}String status(){return status;}boolean paused(){return paused;}void paused(boolean value){paused=value;status=paused?"已暂停":"继续发送";}void pause(){paused(!paused);}boolean cancel(){if(result.isDone())return false;if(output==null||fileCommit.cancel()){cancelled=true;preparer.shutdownNow();if(output==null)result.complete("命令任务已取消");return true;}return false;}
    private boolean permission(String command){return client.getNetworkHandler()!=null&&client.getNetworkHandler().getCommandDispatcher().getRoot().getChild(command)!=null;}
    private boolean accepted(int part,Vec3i at){if(!layer.contains(at))return false;var owner=layout.sample(at,cells);return owner!=null&&owner.part().index()==part;}
    private void preflight(long deadline){
        int budget=256;
        while(validationPart<states.size()&&budget-->0&&System.nanoTime()<deadline){if(!layout.enabled(validationPart)){validationPart++;validationState=0;continue;}var resolver=states.get(validationPart);if(resolver.unresolved(validationState))throw new IllegalArgumentException("投影包含无法识别的方块");var state=resolver.resolve(validationState);var props=new TreeMap<String,String>();state.getEntries().forEach((p,v)->props.put(p.getName(),v.toString().toLowerCase(Locale.ROOT)));String text=new BlockStateSpec(Registries.BLOCK.getId(state.getBlock()).toString(),props).toString();CommandNbt.block(new Vec3i(-30000000,-2048,-30000000),text,null,rule,limit,storage);stateText.get(validationPart).add(text);if(++validationState==document.parts().get(validationPart).palette().size()){validationPart++;validationState=0;}}
        if(validationPart<states.size())return;
        if(preparing==null){preparing=preparer.submit(()->CommandPlans.prepare(document,layout,cells,layer,rule,includeNbt,entities,stateText,limit,storage));return;}
        if(!preparing.isDone())return;
        try{prepared=preparing.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}catch(ExecutionException e){throw new IllegalArgumentException(e.getCause().getMessage(),e.getCause());}
        if(output==null)for(String command:prepared.commands())if(!permission(command))throw new IllegalArgumentException("需要服务器 "+command+" 权限");
        preflightDone=true;entityPart=0;entityIndex=0;status="准备发送";
    }
    private int admission(SectionKey key){if(!layout.enabled(key.region()))return 0;var p=document.parts().get(key.region());if(rule!=ReplaceRule.ALL&&p.nonAir().sectionEmpty(key))return 0;var base=p.region().sectionOrigin(key);var size=p.region().size();var clipped=new Region("section",base,new Vec3i(Math.min(16,size.x()-key.x()*16),Math.min(16,size.y()-key.y()*16),Math.min(16,size.z()-key.z()*16)));var bounds=PlacementBounds.clipped(clipped,layout.part(key.region()).transform(),layer);if(bounds==null)return 0;if(output!=null)return 1;for(int x:new int[]{bounds.min().x(),bounds.max().x()})for(int z:new int[]{bounds.min().z(),bounds.max().z()})if(loaded(new Vec3i(x,bounds.min().y(),z)))return 1;return -1;}
    private boolean loaded(Vec3i at){return output!=null||client.world.isChunkLoaded(at.x()>>4,at.z()>>4);}
    private Cell next(long deadline){
        int budget=512;
        while(budget-->0&&System.nanoTime()<deadline){if(work==null){work=sections.poll(ticks);if(work==null)return null;}var p=document.parts().get(work.key.region());if(admission(work.key)<0){sections.unavailable(work);work=null;continue;}if(work.cursor==4096){if(!work.finished()){work.cursor=0;work.retryAt=ticks+20;sections.defer(work);}work=null;continue;}
            if(rule!=ReplaceRule.ALL){int next=p.nonAir().next(work.key,work.cursor);work.done.set(work.cursor,next);work.cursor=next;if(next==4096)continue;}
            int localIndex=work.cursor++;if(work.done.get(localIndex))continue;var local=p.region().sectionOrigin(work.key).add(new Vec3i(localIndex&15,localIndex>>>8,(localIndex>>>4)&15));if(!p.region().contains(local)){work.done.set(localIndex);continue;}int part=work.key.region();var at=layout.part(part).transform().apply(local);if(!accepted(part,at)){work.done.set(localIndex);continue;}if(!loaded(at))continue;work.done.set(localIndex);var offset=local.subtract(p.region().min());int index=offset.x()+offset.z()*p.region().size().x()+offset.y()*p.region().size().x()*p.region().size().z();int id=p.blocks().get(index);var state=states.get(part).resolve(id);if(state.isOf(net.minecraft.block.Blocks.STRUCTURE_VOID)||rule!=ReplaceRule.ALL&&state.isAir())continue;return new Cell(at,stateText.get(part).get(id),prepared.blocks().get(CommandPlans.key(part,index)));
        }return null;
    }
    private boolean extend(Cell cell){if(!merge||cell.tag()!=null||runSize>=Math.min(fillVolume,4096)||!cell.state().equals(runState))return false;var delta=cell.at().subtract(runEnd);if(Math.abs(delta.x())+Math.abs(delta.y())+Math.abs(delta.z())!=1||runStep!=null&&!runStep.equals(delta))return false;runStep=delta;runEnd=cell.at();runSize++;return true;}
    private void flush(){if(runSize==0)return;String mode=rule==ReplaceRule.NONE?" keep":" replace";String line=runSize==1?"setblock "+xyz(runStart)+" "+runState+mode:"fill "+xyz(runStart)+" "+xyz(runEnd)+" "+runState+mode;var lines=new ArrayList<String>();if(line.length()>limit){lines.addAll(CommandNbt.block(runStart,runState,null,rule,limit,storage));for(int i=1;i<runSize;i++)lines.addAll(CommandNbt.block(runStart.add(new Vec3i(runStep.x()*i,runStep.y()*i,runStep.z()*i)),runState,null,rule,limit,storage));}else lines.add(line);var required=new ArrayList<Vec3i>();for(int i=0;i<runSize;i+=16)required.add(runStart.add(runStep==null?Vec3i.ZERO:new Vec3i(runStep.x()*i,runStep.y()*i,runStep.z()*i)));if(!required.contains(runEnd))required.add(runEnd);pending.add(lines,required);runSize=0;runState=null;runStep=null;}
    private static String xyz(Vec3i at){return at.x()+" "+at.y()+" "+at.z();}
    void tick(){
        if(result.isDone()||paused||producerDone)return;ticks++;
        try{
            if(client!=null&&(client.world!=world||client.getNetworkHandler()!=connection))throw new IllegalStateException("世界已切换");if(output==null&&(client.player==null||!client.player.isCreative()||!permission("setblock")))throw new IllegalStateException("需要创造模式与服务器命令权限");
            long deadline=System.nanoTime()+2_000_000L;if(!preflightDone){preflight(deadline);return;}if(output!=null&&outputQueue.remainingCapacity()==0||output==null&&ticks%interval!=0)return;
            sections.discover(512,this::admission,deadline);var batch=new ArrayList<String>();int budget=output==null?perTick:128,steps=1024;boolean finished=false,waiting=false;
            while(budget>0&&steps-->0&&System.nanoTime()<deadline){
                String line=pending.poll(this::loaded);if(line!=null){if(output==null)client.getNetworkHandler().sendChatCommand(line);else batch.add(line);generated++;budget--;continue;}
                if(!pending.isEmpty())waiting=true;if(!pending.hasRoom())break;Cell cell=held==null?next(deadline):held;held=null;
                if(cell!=null){if(runSize>0){if(extend(cell))continue;held=cell;flush();continue;}if(cell.tag()!=null){pending.add(cell.tag(),List.of(cell.at()));continue;}runStart=runEnd=cell.at();runState=cell.state();runSize=1;continue;}
                if(runSize>0){flush();continue;}if(work!=null||!sections.finished())break;
                if(!blocksDispatched){if(!pending.isEmpty())break;blocksDispatched=true;}
                if(entitiesRemaining>0){var p=document.parts().get(entityPart);if(!layout.enabled(entityPart)||entityIndex>=p.entities().size()){entityPart=(entityPart+1)%states.size();entityIndex=0;continue;}int index=entityIndex++;if(entitiesDone.get(entityPart).get(index))continue;var plan=prepared.entities().get(entityPart).get(index);if(plan!=null&&!plan.required().stream().allMatch(this::loaded)){waiting=true;continue;}entitiesDone.get(entityPart).set(index);entitiesRemaining--;if(plan!=null)pending.add(List.of(plan.line()),plan.required());continue;}
                finished=pending.isEmpty();break;
            }
            if(!batch.isEmpty()&&!outputQueue.offer(List.copyOf(batch)))throw new IllegalStateException("命令队列已满");status=(output==null?"已发送 ":"已生成 ")+generated+" 条命令"+(waiting?" · 等待区块":"");if(finished){producerDone=true;if(output==null)result.complete(status);}
        }catch(RuntimeException e){fail(e);}
    }
    void fail(Throwable failure){if(fileCommit.committed())return;fileCommit.cancel();cancelled=true;result.completeExceptionally(failure);}
    private void write(){Path temporary=null;try{Path absolute=output.toAbsolutePath();Files.createDirectories(absolute.getParent());if(Files.exists(absolute))throw new FileAlreadyExistsException(absolute.toString());temporary=Files.createTempFile(absolute.getParent(),"commands-",".part");long bytes=0;
        try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){while(!cancelled){var batch=outputQueue.poll(100,TimeUnit.MILLISECONDS);if(batch!=null)for(String line:batch){bytes+=line.getBytes(StandardCharsets.UTF_8).length+1;if(bytes>128L*1024*1024)throw new IOException("命令文件超过 128 MiB");writer.write(line);writer.newLine();}if(producerDone&&outputQueue.isEmpty())break;}}
        if(producerDone&&!cancelled&&fileCommit.commit(temporary,absolute))result.complete("已导出："+absolute.getFileName());
    }catch(Exception e){fail(e);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}if(cancelled&&!result.isDone())result.complete("命令任务已取消");}}
}
