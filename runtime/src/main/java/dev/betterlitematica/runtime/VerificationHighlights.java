package dev.betterlitematica.runtime;

import dev.betterlitematica.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Client-thread working set; ledger IO and box merging stay on its serial worker. */
public final class VerificationHighlights implements AutoCloseable {
    public static final long MAX_BYTES=64L<<20;
    public static final int MAX_SECTIONS=32768;
    private final VerificationLedger ledger;
    private final VerificationReport report;
    private final Map<SectionKey,VerificationLedger.SpatialSection> cache=new HashMap<>();
    private final Map<SectionKey,Long> minimum=new HashMap<>();
    private final Map<Long,Set<SectionKey>> chunks=new HashMap<>();
    private final Set<SectionKey> blocked=new HashSet<>(),pressure=new HashSet<>();
    private List<SectionKey> candidates=List.of();private Set<SectionKey> candidateSet=Set.of();
    private CompletableFuture<VerificationLedger.SpatialQuery> query;
    private CompletableFuture<Map<SectionKey,VerificationLedger.SpatialSection>> batch;
    private CompletableFuture<List<HighlightCuboids.Box>> merging;private long mergeRevision=-1;
    private Vec3i center;private long bytes,ticks,queryRevision=-1,revision;private int total;
    private boolean closed,dirty=true;private String error="";
    private List<HighlightCuboids.Box> view=List.of();
    public VerificationHighlights(VerificationLedger ledger,VerificationReport report){this.ledger=ledger;this.report=report;}
    public long revision(){return revision;}
    public List<HighlightCuboids.Box> boxes(){if(dirty)rebuildView();return view;}
    public String status(){return !error.isEmpty()?"高亮读取失败":!pressure.isEmpty()||total>MAX_SECTIONS?"高亮缓存达到 64 MiB / 32768 区段预算":query!=null||batch!=null?"高亮载入中":"";}
    public void ignoredChanged(){reset();}
    public void pending(SectionKey key){
        if(!candidateSet.contains(key))return;
        blocked.add(key);remove(key);pressure.remove(key);
    }
    public void chunkChanged(int x,int z){var affected=chunks.get(chunk(x,z));if(affected!=null)for(var key:List.copyOf(affected))pending(key);}
    public void committed(SectionKey key,long version){
        blocked.remove(key);
        if(candidateSet.contains(key)){minimum.put(key,version);var old=cache.get(key);if(old!=null&&old.revision()!=version)remove(key);pressure.remove(key);}
    }
    public void update(Vec3i camera){update(camera,(x,z)->true);}
    public void update(Vec3i camera,java.util.function.BiPredicate<Integer,Integer> loaded){
        if(closed)return;ticks++;
        // Re-query after four blocks of travel; cached geometry remains drawable while IO catches up.
        if(center==null||distance(center,camera)>=16){center=camera;cancelQuery();queryRevision=-1;pressure.clear();}
        try{
            if(query!=null&&query.isDone()){
                var found=query.join();query=null;queryRevision=found.revision();total=found.total();candidates=found.keys();candidateSet=Set.copyOf(candidates);
                for(var iterator=cache.entrySet().iterator();iterator.hasNext();){var e=iterator.next();if(!candidateSet.contains(e.getKey())){bytes-=weight(e.getValue());index(e.getKey(),e.getValue(),false);iterator.remove();dirty=true;}}
                minimum.keySet().retainAll(candidateSet);blocked.retainAll(candidateSet);pressure.retainAll(candidateSet);
            }
            if(batch!=null&&batch.isDone()){
                var found=batch.join();batch=null;
                for(var e:found.entrySet()){
                    var key=e.getKey();var value=e.getValue();if(!candidateSet.contains(key)||blocked.contains(key)||value.revision()<minimum.getOrDefault(key,0L))continue;
                    if(!loaded(value.bounds(),loaded)){pending(key);continue;}
                    remove(key);long size=weight(value);
                    if(bytes+size>MAX_BYTES){
                        // Admission evicts only farther sections, never a closer section just filled.
                        int rank=candidates.indexOf(key);
                        for(int i=candidates.size()-1;i>rank&&bytes+size>MAX_BYTES;i--){var evicted=candidates.get(i);if(cache.containsKey(evicted)){remove(evicted);pressure.add(evicted);}}
                    }
                    if(bytes+size<=MAX_BYTES){cache.put(key,value);index(key,value,true);bytes+=size;dirty=true;}else pressure.add(key);
                }
            }
            if(query==null&&(queryRevision<0||ticks%20==0&&queryRevision!=ledger.revision()))query=ledger.nearby(center,132,MAX_SECTIONS);
            if(batch==null&&!candidates.isEmpty()){
                var next=new ArrayList<SectionKey>();
                for(var key:candidates)if(!cache.containsKey(key)&&!blocked.contains(key)&&!pressure.contains(key)){next.add(key);if(next.size()==8)break;}
                if(!next.isEmpty())batch=ledger.spatial(next,report.ignoredKeys());
            }
            if(dirty)rebuildView();
            if(merging!=null&&merging.isDone()){var merged=merging.join();merging=null;if(mergeRevision==revision)view=merged;}
            if(merging==null&&batch==null&&query==null&&mergeRevision!=revision){mergeRevision=revision;if(view.size()<=600000)merging=ledger.coalesce(view);}
        }catch(RuntimeException e){error=e.toString();cancelQuery();if(batch!=null)batch.cancel(false);batch=null;}
    }
    private static long distance(Vec3i a,Vec3i b){long x=(long)a.x()-b.x(),y=(long)a.y()-b.y(),z=(long)a.z()-b.z();if(Math.abs(x)>4||Math.abs(y)>4||Math.abs(z)>4)return 17;return x*x+y*y+z*z;}
    private static long weight(VerificationLedger.SpatialSection section){return 384L+section.boxes().size()*112L;}
    private void remove(SectionKey key){var old=cache.remove(key);if(old!=null){bytes-=weight(old);index(key,old,false);dirty=true;}}
    private static long chunk(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static boolean loaded(PlacementBounds box,java.util.function.BiPredicate<Integer,Integer> loaded){if(box==null)return true;int x0=box.min().x()>>4,x1=box.max().x()>>4,z0=box.min().z()>>4,z1=box.max().z()>>4;if((long)x1-x0>1||(long)z1-z0>1)return false;for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)if(!loaded.test(x,z))return false;return true;}
    private void index(SectionKey key,VerificationLedger.SpatialSection value,boolean add){var box=value.bounds();if(box==null)return;int x0=box.min().x()>>4,x1=box.max().x()>>4,z0=box.min().z()>>4,z1=box.max().z()>>4;if((long)x1-x0>1||(long)z1-z0>1)throw new IllegalArgumentException("Non-section highlight bounds");for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){long at=chunk(x,z);if(add)chunks.computeIfAbsent(at,n->new HashSet<>()).add(key);else{var keys=chunks.get(at);if(keys!=null){keys.remove(key);if(keys.isEmpty())chunks.remove(at);}}}}
    private void cancelQuery(){if(query!=null)query.cancel(false);query=null;}
    private void reset(){cancelQuery();if(batch!=null)batch.cancel(false);batch=null;if(merging!=null)merging.cancel(false);merging=null;mergeRevision=-1;cache.clear();chunks.clear();minimum.clear();blocked.clear();pressure.clear();candidates=List.of();candidateSet=Set.of();bytes=0;queryRevision=-1;total=0;view=List.of();dirty=true;revision++;}
    private void rebuildView(){
        var pieces=new ArrayList<List<HighlightCuboids.Box>>();for(var key:candidates){var value=cache.get(key);if(value!=null&&!value.boxes().isEmpty())pieces.add(value.boxes());}
        view=new BoxView(pieces);dirty=false;revision++;
    }
    @Override public void close(){closed=true;reset();}
    private static final class BoxView extends AbstractList<HighlightCuboids.Box> {
        private final List<List<HighlightCuboids.Box>> pieces;private final int[] ends;private final int size;
        BoxView(List<List<HighlightCuboids.Box>> value){pieces=List.copyOf(value);ends=new int[pieces.size()];int total=0;for(int i=0;i<ends.length;i++){total=Math.addExact(total,pieces.get(i).size());ends[i]=total;}size=total;}
        @Override public int size(){return size;}
        @Override public HighlightCuboids.Box get(int index){Objects.checkIndex(index,size);int p=Arrays.binarySearch(ends,index+1);if(p<0)p=-p-1;return pieces.get(p).get(index-(p==0?0:ends[p-1]));}
        @Override public Iterator<HighlightCuboids.Box> iterator(){return new Iterator<>(){int piece,index;public boolean hasNext(){return piece<pieces.size();}public HighlightCuboids.Box next(){if(!hasNext())throw new NoSuchElementException();var value=pieces.get(piece).get(index++);if(index==pieces.get(piece).size()){piece++;index=0;}return value;}};}
    }
}
