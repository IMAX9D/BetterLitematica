package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.VerificationLedger;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Incremental live verifier. Section contributions are replaced atomically, never double-counted. */
final class PlacementAnalysis {
    record Material(Item item,long total,long missing,long unknown,long available){}
    private final ProjectionRenderer1201 source;private final ClientWorld world;private final PlacementLayout layout;private final PlacementLayout.Source cells;private final LayerRange layer;
    private final DeferredSections queue;private final VerificationReport report=new VerificationReport();
    private final VerificationLedger ledger=new VerificationLedger();
    private final dev.betterlitematica.runtime.VerificationHighlights highlights=new dev.betterlitematica.runtime.VerificationHighlights(ledger,report);
    private final Map<Long,String> descriptions=new HashMap<>();private final Map<BlockState,String> actualDescriptions=new HashMap<>();
    private final LinkedHashSet<SectionKey> dirty=new LinkedHashSet<>();private final LinkedHashSet<Long> chunks=new LinkedHashSet<>();
    private final Map<SectionKey,CompletableFuture<VerificationLedger.Replacement>> pending=new LinkedHashMap<>();
    private CompletableFuture<List<SectionKey>> known;private Iterator<SectionKey> catchup=Collections.emptyIterator();private boolean needCatchup;
    private final long volume;private long ticks;private int workTurn;private boolean paused,cancelled;private String error="";private Scan scan;private Expansion expansion;
    private static final class Scan {final SectionKey key;final DeferredSections.Work initial;final PackedSection section;final VerificationSection.Builder result;List<PlacementLayout.Part> overlaps;int cursor;Scan(SectionKey key,DeferredSections.Work initial,PackedSection section,VerificationReport report){this.key=key;this.initial=initial;this.section=section;this.result=new VerificationSection.Builder(report::ignored);}}
    PlacementAnalysis(ProjectionRenderer1201 source,ClientWorld world,LayerRange layer){this.source=source;this.world=world;this.layer=layer;layout=source.layout();cells=new PlacementLayout.Source(){public int state(PlacementLayout.Part p,Vec3i local){return source.sampleRaw(p.section(local),p.cell(local));}public BlockStateSpec spec(int region,int state){return source.metadata().palette().get(state);}};queue=new DeferredSections(source.metadata().regions());volume=source.metadata().regions().stream().mapToLong(Region::volume).sum();report.onIgnoredChanged(()->{needCatchup=true;highlights.ignoredChanged();});}
    boolean world(ClientWorld value){return world==value&&!cancelled;}
    private boolean outsideLayer(Region r,SectionKey key){if(!layout.enabled(key.region()))return true;var transform=layout.part(key.region()).transform();var base=r.sectionOrigin(key);return PlacementBounds.clipped(new Region("section",base,new Vec3i(Math.min(16,r.size().x()-key.x()*16),Math.min(16,r.size().y()-key.y()*16),Math.min(16,r.size().z()-key.z()*16))),transform,layer)==null;}
    private boolean anyChunkLoaded(Region r,SectionKey key){var transform=layout.part(key.region()).transform();var origin=r.sectionOrigin(key);for(int x:new int[]{0,Math.min(15,r.size().x()-key.x()*16-1)})for(int z:new int[]{0,Math.min(15,r.size().z()-key.z()*16-1)}){var p=transform.apply(origin.add(new Vec3i(x,0,z)));if(world.isChunkLoaded(p.x()>>4,p.z()>>4))return true;}return false;}
    void changed(BlockPos position){if(cancelled)return;var at=new Vec3i(position.getX(),position.getY(),position.getZ());if(!layer.contains(at))return;for(var part:layout.at(at)){var local=part.local(at);var key=part.section(local);mark(key);highlights.pending(key);}}
    void chunkChanged(int x,int z){if(cancelled)return;highlights.chunkChanged(x,z);long key=((long)x<<32)|(z&0xffffffffL);if(chunks.size()<8192)chunks.add(key);else needCatchup=true;}
    private void mark(SectionKey key){if(dirty.size()<8192)dirty.add(key);else needCatchup=true;}
    void tick(){
        if(cancelled||!error.isEmpty())return;source.drain();ticks++;long end=System.nanoTime()+3_000_000L;
        try{
            for(var it=pending.entrySet().iterator();it.hasNext();){var e=it.next();if(!e.getValue().isDone())continue;var replacement=e.getValue().join();report.replace(replacement.before(),replacement.after());if(!dirty.contains(replacement.key()))highlights.committed(replacement.key(),replacement.revision());it.remove();}
            if(paused)return;
            if(known!=null&&known.isDone()){catchup=known.join().iterator();known=null;}
            if((needCatchup||ticks%200==0)&&known==null&&!catchup.hasNext()){known=ledger.knownSections();needCatchup=false;}
            int feed=128;while(catchup.hasNext()&&dirty.size()<4096&&feed-->0)mark(catchup.next());
            if(expansion==null&&!chunks.isEmpty()){var iterator=chunks.iterator();long chunk=iterator.next();iterator.remove();expansion=new Expansion((int)(chunk>>32),(int)chunk);}
            if(expansion!=null){int budget=256;while(budget-->0&&dirty.size()<4096&&System.nanoTime()<end){var key=expansion.next();if(key==null){expansion=null;break;}mark(key);highlights.pending(key);}}
            queue.discover(256,key->{var r=source.metadata().regions().get(key.region());if(outsideLayer(r,key))return 0;return anyChunkLoaded(r,key)?1:-1;},end);
            int turns=32,cells=16384;
            while(turns-->0&&cells>0&&pending.size()<4&&System.nanoTime()<end){
                if(scan==null){SectionKey key=null;DeferredSections.Work work=null;boolean initial=(workTurn++&1)==0;
                    if(initial){work=queue.poll(ticks);if(work!=null)key=work.key;}
                    if(key==null)key=takeDirty();
                    if(key==null&&!initial){work=queue.poll(ticks);if(work!=null)key=work.key;}
                    if(key==null)break;
                    if(pending.containsKey(key)){if(work!=null){work.retryAt=ticks+1;queue.defer(work);}else mark(key);continue;}
                    var section=source.analysisSection(key);if(section==null){if(work!=null){work.retryAt=ticks+1;queue.defer(work);}else mark(key);continue;}
                    scan=new Scan(key,work,section,report);var r=source.metadata().regions().get(key.region());var box=PlacementBounds.clipped(new Region("section",r.sectionOrigin(key),new Vec3i(16,16,16)),layout.part(key.region()).transform(),LayerRange.ALL);scan.overlaps=layout.overlapping(box);
                }
                var r=source.metadata().regions().get(scan.key.region());var base=r.sectionOrigin(scan.key);var transform=layout.part(scan.key.region()).transform();
                while(scan.cursor<4096&&cells-->0&&System.nanoTime()<end){int i=scan.cursor++;var local=base.add(new Vec3i(i&15,i>>>8,(i>>>4)&15));if(!r.contains(local))continue;var at=transform.apply(local);if(!layer.contains(at))continue;var cell=layout.sample(scan.overlaps,at,this.cells);if(cell!=null&&cell.unknown()){scan.cursor--;return;}if(cell==null||cell.part().index()!=scan.key.region())continue;int id=cell.state();var resolver=source.resolver(scan.key.region());BlockState expected=resolver.resolve(id);var pos=new BlockPos(at.x(),at.y(),at.z());boolean loaded=world.isChunkLoaded(pos);BlockState actual=loaded?world.getBlockState(pos):null;
                    Comparison comparison=!loaded||resolver.unresolved(id)?Comparison.UNKNOWN:expected.equals(actual)||expected.isAir()&&actual.isAir()?Comparison.MATCH:expected.isAir()?Comparison.EXTRA:actual.isAir()?Comparison.MISSING:expected.getBlock()==actual.getBlock()?Comparison.WRONG_STATE:Comparison.WRONG_BLOCK;
                    String wanted="",found="";if(comparison!=Comparison.MATCH){wanted=descriptions.computeIfAbsent(((long)scan.key.region()<<32)|id,key->expected.toString());if(actual!=null){if(actualDescriptions.size()>=4096)actualDescriptions.clear();found=actualDescriptions.computeIfAbsent(actual,Object::toString);}}
                    scan.result.add(at,comparison,wanted,found);
                }
                if(scan.cursor<4096)break;
                if(scan.initial!=null){scan.initial.done.set(0,4096);queue.defer(scan.initial);}
                pending.put(scan.key,ledger.replace(scan.key,scan.result.build()));scan=null;
            }
        }catch(RuntimeException e){error=e.toString();highlights.close();ledger.close();}
    }
    private SectionKey takeDirty(){int attempts=Math.min(64,dirty.size());while(attempts-->0){var iterator=dirty.iterator();var key=iterator.next();iterator.remove();if(!pending.containsKey(key))return key;dirty.add(key);}return null;}
    /** Lazily expands a changed world chunk; even a tall or multi-region source cannot block a network callback. */
    private final class Expansion {
        final int cx,cz;int region=-1,x,y,z,x0,x1,z0,z1,ny;
        Expansion(int x,int z){cx=x;cz=z;}
        SectionKey next(){
            while(true){if(region>=0&&y<ny){var key=new SectionKey(region,x,y,z);if(++x>x1){x=x0;if(++z>z1){z=z0;y++;}}return key;}
                if(++region>=source.metadata().regions().size())return null;var r=source.metadata().regions().get(region);if(!layout.enabled(region)){ny=0;continue;}var transform=layout.part(region).transform();
                Vec3i a=transform.inverse(new Vec3i(cx*16,0,cz*16)),b=transform.inverse(new Vec3i(cx*16+15,0,cz*16+15));
                int minX=Math.max(r.min().x(),Math.min(a.x(),b.x())),maxX=Math.min(r.min().x()+r.size().x()-1,Math.max(a.x(),b.x()));int minZ=Math.max(r.min().z(),Math.min(a.z(),b.z())),maxZ=Math.min(r.min().z()+r.size().z()-1,Math.max(a.z(),b.z()));
                if(minX>maxX||minZ>maxZ){ny=0;continue;}x0=(minX-r.min().x())>>4;x1=(maxX-r.min().x())>>4;z0=(minZ-r.min().z())>>4;z1=(maxZ-r.min().z())>>4;ny=(r.size().y()+15)/16;x=x0;y=0;z=z0;
            }
        }
    }
    java.util.UUID target(){return layout.placement().id();}boolean cancelled(){return cancelled;}
    void updateHighlights(Vec3i camera){highlights.update(camera,world::isChunkLoaded);}
    List<HighlightCuboids.Box> highlightBoxes(){return highlights.boxes();}
    long highlightRevision(){return highlights.revision();}
    VerificationReport report(){return report;}boolean finished(){return queue.finished()&&scan==null&&pending.isEmpty();}boolean paused(){return paused;}void pause(){paused=!paused;}
    void cancel(){cancelled=true;queue.clear();dirty.clear();chunks.clear();pending.clear();scan=null;expansion=null;catchup=Collections.emptyIterator();if(known!=null)known.cancel(false);highlights.close();ledger.close();}
    String status(){if(!error.isEmpty())return "校验失败："+error;String spatial=highlights.status();return (cancelled?"已取消":paused?"已暂停":finished()?"实时校验":"校验中")+" "+report.scanned()+"/"+volume+" · 错误 "+report.remainingErrors()+" · 未知 "+report.count(Comparison.UNKNOWN)+(spatial.isEmpty()?"":" · "+spatial);}
}
