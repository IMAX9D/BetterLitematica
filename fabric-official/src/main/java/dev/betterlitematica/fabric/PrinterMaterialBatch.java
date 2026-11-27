package dev.betterlitematica.fabric;
import net.minecraft.world.level.block.state.BlockState;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.PrinterMaterialIndex;
import dev.betterlitematica.runtime.LoadCoordinator;
import net.minecraft.world.level.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

final class PrinterMaterialBatch implements AutoCloseable {
    private record Input(LoadCoordinator.Loaded lease,SchematicEdits.Snapshot edits,PlacementLayout layout,int[] materials){}
    private record Ref(PrinterMaterialIndex index,PrinterMaterialIndex.Slice slice,Vec3i origin,PlacementTransform transform){}
    private static final class Catalog implements AutoCloseable {
        final List<PrinterMaterialIndex> indices=new ArrayList<>();
        final Map<Integer,List<Ref>> groups=new HashMap<>();
        final Map<Integer,Long> counts=new HashMap<>();
        List<Integer> order=List.of();
        public void close(){for(var index:indices)try{index.close();}catch(IOException ignored){}}
    }
    private static final class Session {
        volatile boolean cancelled;volatile Catalog catalog;volatile String error="";
    }
    private static final class Pass {
        final ArrayBlockingQueue<long[]> pages=new ArrayBlockingQueue<>(4);
        volatile boolean cancelled,done;volatile String error="";
    }
    private record Receipt(int expected,int before,int tick,int material){}
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"betterlitematica-material-cache");t.setDaemon(true);return t;});
    private Session session;private Pass pass;private Vec3i anchor;private double radius;
    private long[] page;private int relative,order,active=Integer.MIN_VALUE,drainedAt=-1,notBefore;
    private long passProgress,cycleProgress;private boolean complete;
    private final LinkedHashMap<Long,Receipt> receipts=new LinkedHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> credited=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap confirmedUnits=new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    static boolean enabled(PrinterSettings s){return s.materialBatch&&s.print&&!s.fill&&!s.fluid&&!s.bedrock&&!s.breakExtra&&s.printScope==PrinterSettings.Scope.PROJECTION;}
    static int key(BlockState state,PrinterSettings settings){return state.getBlock() instanceof LiquidBlock?net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(state.is(Blocks.LAVA)?net.minecraft.world.item.Items.LAVA_BUCKET:settings.waterToIce&&PrinterIcePolicy.waterSource(state)?net.minecraft.world.item.Items.ICE:net.minecraft.world.item.Items.WATER_BUCKET):PrinterActions.materialKey(state);}
    boolean pending(){return !complete;}
    int material(){return active;}
    long confirmedUnits(int material){return confirmedUnits.get(material);}
    long remaining(){var c=session==null?null:session.catalog;return c==null?0:c.counts.getOrDefault(active,0L);}
    String status(){return session!=null&&!session.error.isEmpty()?"材料缓存失败，请检查磁盘空间":session==null||session.catalog==null?"正在建立材料缓存":"正在读取材料队列";}
    void ensure(List<ProjectionController.PrinterSource> sources,Vec3i center,double reach,LayerRange layer,PrinterActions actions,PrinterSettings settings,int tick){
        if(session!=null&&anchor!=null&&Math.abs(center.x()-anchor.x())<=8&&Math.abs(center.y()-anchor.y())<=8&&Math.abs(center.z()-anchor.z())<=8&&reach==radius)return;
        reset();anchor=center;radius=reach;notBefore=tick;
        var inputs=new ArrayList<Input>();
        for(var source:sources){if(source.renderer()==null){for(var input:inputs)try{input.lease().close();}catch(IOException ignored){}return;}
            var renderer=source.renderer();int[] materials=new int[renderer.metadata().palette().size()];Arrays.fill(materials,Integer.MIN_VALUE);
            for(int i=0;i<materials.length;i++){
                var state=renderer.resolve(0,i);if(state.isAir()||PrinterRules.forbiddenPrint(state)||PrinterRules.filtered(state,settings.skip))continue;
                materials[i]=key(state,settings);
            }
            inputs.add(new Input(renderer.printerSourceLease(),renderer.printerEdits(),renderer.layout(),materials));
        }
        Session owner=new Session();session=owner;int r=(int)Math.ceil(reach)+10;
        var bounds=new PlacementBounds(new Vec3i(center.x()-r,center.y()-r,center.z()-r),new Vec3i(center.x()+r,center.y()+r,center.z()+r));
        Path directory=Minecraft.getInstance().gameDirectory.toPath().resolve(".betterlitematica-cache/printer-materials");
        io.execute(()->{
            Catalog catalog=new Catalog();try{
                Cancellation cancel=()->owner.cancelled||Thread.currentThread().isInterrupted();
                for(var input:inputs){cancel.check();var index=PrinterMaterialIndex.prepare(directory,input.lease().cache(),input.edits(),cancel);catalog.indices.add(index);
                    for(var part:input.layout().overlapping(bounds)){
                        var first=part.local(bounds.min()).subtract(part.region().min());var last=part.local(bounds.max()).subtract(part.region().min());
                        int x0=Math.max(0,Math.min(first.x(),last.x())>>4),x1=Math.min((part.region().size().x()-1)>>4,Math.max(first.x(),last.x())>>4);
                        int y0=Math.max(0,Math.min(first.y(),last.y())>>4),y1=Math.min((part.region().size().y()-1)>>4,Math.max(first.y(),last.y())>>4);
                        int z0=Math.max(0,Math.min(first.z(),last.z())>>4),z1=Math.min((part.region().size().z()-1)>>4,Math.max(first.z(),last.z())>>4);
                        for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
                            cancel.check();var key=new SectionKey(part.index(),x,y,z);var origin=part.region().sectionOrigin(key);
                            for(var slice:index.slices(key)){
                                if(slice.state()>=input.materials().length)throw new IOException("Material palette mismatch");int material=input.materials()[slice.state()];if(material==Integer.MIN_VALUE)continue;
                                catalog.groups.computeIfAbsent(material,k->new ArrayList<>()).add(new Ref(index,slice,origin,part.transform()));catalog.counts.merge(material,(long)slice.count(),Long::sum);
                            }
                        }
                    }
                }
                var counts=catalog.counts;catalog.order=new ArrayList<>(catalog.groups.keySet());catalog.order.sort(Comparator.<Integer>comparingLong(k->counts.get(k)).reversed().thenComparingInt(k->k));
                cancel.check();owner.catalog=catalog;catalog=null;
            }catch(Exception e){if(!owner.cancelled){owner.error=e.toString();BetterLitematicaClient.LOGGER.error("Printer material cache failed",e);}}
            finally{if(catalog!=null)catalog.close();for(var input:inputs)try{input.lease().close();}catch(IOException ignored){}}
        });
    }

    boolean ready(int tick){
        if(session!=null&&!session.error.isEmpty()||pass!=null&&!pass.error.isEmpty())throw new IllegalStateException("材料缓存读取失败，请检查磁盘空间");
        if(session==null||session.catalog==null||tick<notBefore)return false;
        if(!session.error.isEmpty()||pass!=null&&!pass.error.isEmpty())throw new IllegalStateException("材料缓存读取失败，请检查磁盘空间");
        var catalog=session.catalog;
        if(pass==null){
            if(order>=catalog.order.size()){
                if(cycleProgress>0){order=0;cycleProgress=0;complete=false;}
                else {complete=true;order=0;notBefore=tick+200;return false;}
            }
            if(catalog.order.isEmpty())return false;complete=false;active=catalog.order.get(order);start(catalog.groups.get(active));
        }
        if(page==null||relative>=page.length){page=pass.pages.poll();relative=0;}
        return page!=null;
    }
    Vec3i next(){long pos=page[relative++];return new Vec3i(BlockPos.getX(pos),BlockPos.getY(pos),BlockPos.getZ(pos));}
    private void start(List<Ref> refs){
        Pass next=new Pass();pass=next;page=null;relative=0;drainedAt=-1;passProgress=0;Session owner=session;
        io.execute(()->{try{
            long[] data=new long[4096];int n=0;
            for(var ref:refs){if(owner.cancelled||next.cancelled)return;
                for(int cell:ref.index().cells(ref.slice())){
                    var p=ref.transform().apply(ref.origin().add(new Vec3i(cell&15,cell>>>8,(cell>>>4)&15)));
                    data[n++]=BlockPos.asLong(p.x(),p.y(),p.z());
                    if(n==data.length){if(!publish(next,owner,data))return;data=new long[4096];n=0;}
                }
            }if(n>0)publish(next,owner,Arrays.copyOf(data,n));
        }catch(Exception e){if(!owner.cancelled&&!next.cancelled){next.error=e.toString();BetterLitematicaClient.LOGGER.error("Printer material page failed",e);}}
        finally{next.done=true;}});
    }
    private static boolean publish(Pass pass,Session owner,long[] data)throws InterruptedException{
        while(!owner.cancelled&&!pass.cancelled){if(pass.pages.offer(data,100,TimeUnit.MILLISECONDS))return true;}return false;
    }
    boolean exhausted(){return pass!=null&&pass.done&&pass.error.isEmpty()&&(page==null||relative>=page.length)&&pass.pages.isEmpty();}

    void drained(int tick){
        if(!exhausted())return;if(drainedAt<0)drainedAt=tick;
        expire(tick);if(!receipts.isEmpty()&&tick-drainedAt<20)return;
        receipts.clear();pass=null;page=null;
        if(passProgress==0)order++;
    }
    void dispatched(PrinterQueue.Job job,int tick,int material){
        if(session==null||pass==null)return;expire(tick);receipts.remove(job.position());
        if(receipts.size()>=65536)receipts.remove(receipts.keySet().iterator().next());
        receipts.put(job.position(),new Receipt(job.expected(),job.observed(),tick,material));
    }
    void confirmed(BlockPos pos,BlockState state,int tick){
        long key=pos.asLong();var receipt=receipts.get(key);if(receipt==null)return;
        if(tick-receipt.tick()>40){receipts.remove(key);return;}

        if(Block.getId(state)!=receipt.before()&&PrinterRules.constructionMatches(state,Block.stateById(receipt.expected()))){
            receipts.remove(key);

            if(firstConfirmation(pos)){passProgress++;cycleProgress++;if(receipt.material()>=0)confirmedUnits.addTo(receipt.material(),1);}
        }
    }
    private boolean firstConfirmation(BlockPos pos){
        int x=pos.getX(),y=pos.getY(),z=pos.getZ();
        long section=((long)(x>>4)&0x3fffff)<<42|((long)(z>>4)&0x3fffff)<<20|((y>>4)&0xfffff);
        long[] bits=credited.get(section);if(bits==null){bits=new long[64];credited.put(section,bits);}
        int index=(x&15)|((z&15)<<4)|((y&15)<<8);long mask=1L<<(index&63);
        if((bits[index>>6]&mask)!=0)return false;bits[index>>6]|=mask;return true;
    }
    private void expire(int tick){var it=receipts.entrySet().iterator();while(it.hasNext()){if(tick-it.next().getValue().tick()<=40)break;it.remove();}}
    void reset(){
        Session old=session;if(old!=null)old.cancelled=true;if(pass!=null)pass.cancelled=true;
        if(old!=null)io.execute(()->{if(old.catalog!=null)old.catalog.close();});
        session=null;pass=null;page=null;anchor=null;active=Integer.MIN_VALUE;order=0;relative=0;complete=false;cycleProgress=passProgress=0;receipts.clear();credited.clear();confirmedUnits.clear();
    }
    public void close(){reset();io.shutdown();}
}
