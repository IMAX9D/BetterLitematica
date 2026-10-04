package dev.betterlitematica.core;
import java.util.*;

/** Lazy bounded worklist. One bitmap per pending section, no per-voxel pending objects. */
public final class DeferredSections {
    public static final int LIMIT=32768;
    public static final class Work {
        public final SectionKey key;public final BitSet done=new BitSet(4096);
        public int cursor;public long retryAt;
        private final int ordinal;
        private Work previous,next;
        Work(SectionKey key,int ordinal){this.key=key;this.ordinal=ordinal;}
        public boolean finished(){return done.cardinality()==4096;}
    }
    private final List<Region> regions;private final boolean columns;
    private final Map<Integer,Work> queued=new HashMap<>();
    private Work head,tail;private boolean cleared;
    private int region,x,y,z,ordinal,claimedCount;private final int total;private final BitSet claimed=new BitSet(),parked=new BitSet();
    private final int[] offsets,sectionOffsets;private final Map<Integer,long[]> progress=new HashMap<>();
    public DeferredSections(List<Region> regions){this(regions,false);}
    public DeferredSections(List<Region> regions,boolean columns){this.regions=List.copyOf(regions);this.columns=columns;offsets=new int[regions.size()];sectionOffsets=new int[regions.size()];long count=0,cells=0;for(int i=0;i<regions.size();i++){var r=regions.get(i);offsets[i]=Math.toIntExact(cells);sectionOffsets[i]=Math.toIntExact(count);cells+=r.volume();count+=(long)((r.size().x()+15)/16)*((r.size().y()+15)/16)*((r.size().z()+15)/16);}if(cells>536_870_912L||count>67_108_864)throw new IllegalArgumentException("Section progress exceeds source budget");total=(int)count;}
    public void admit(int limit){admit(limit,key->true);}
    /** Unavailable sections occupy one discovery bit, never a queue slot that could starve later loaded work. */
    public void admit(int limit,java.util.function.Predicate<SectionKey> available){admit(limit,available,Long.MAX_VALUE);}
    public void admit(int limit,java.util.function.Predicate<SectionKey> available,long deadline){discover(limit,key->available.test(key)?1:-1,deadline);}
    /** -2 holds discovery at this key, -1 waits without a slot, 0 completes, 1 admits work. */
    public void discover(int limit,java.util.function.ToIntFunction<SectionKey> disposition,long deadline){int inspected=0;while(limit-->0&&inspected++<total&&queued.size()<LIMIT&&claimedCount<total&&System.nanoTime()<deadline){
        var key=new SectionKey(region,x,y,z);
        if(!claimed.get(ordinal)){int state=disposition.applyAsInt(key);if(state==-2)break;if(state>=0)admit(key,ordinal,state,false);}
        var size=regions.get(region).size();
        if(columns){if(++y==(size.y()+15)/16){y=0;if(++x==(size.x()+15)/16){x=0;if(++z==(size.z()+15)/16){z=0;region++;}}}}
        else if(++x==(size.x()+15)/16){x=0;if(++z==(size.z()+15)/16){z=0;if(++y==(size.y()+15)/16){y=0;region++;}}}
        if(++ordinal==total){ordinal=0;region=0;}
    }}
    /** Wakes one section in O(1). False only means queue capacity requires retrying this hint later. */
    public boolean prioritize(SectionKey key,java.util.function.ToIntFunction<SectionKey> disposition){
        int index=ordinal(key);if(cleared)return true;var work=queued.get(index);
        if(work!=null){work.retryAt=0;enqueue(work,true);return true;}
        // Claimed but absent from the queue means completed or currently owned by the caller.
        if(claimed.get(index))return true;if(queued.size()>=LIMIT)return false;
        int state=disposition.applyAsInt(key);if(state>=0)admit(key,index,state,true);
        return true;
    }
    private int ordinal(SectionKey key){
        if(key.region()>=regions.size())throw new IllegalArgumentException("Unknown source region");
        var size=regions.get(key.region()).size();int sx=(size.x()+15)/16,sy=(size.y()+15)/16,sz=(size.z()+15)/16;
        if(key.x()>=sx||key.y()>=sy||key.z()>=sz)throw new IllegalArgumentException("Section outside source region");
        return sectionOffsets[key.region()]+(columns?key.y()+sy*(key.x()+sx*key.z()):key.x()+sx*(key.z()+sz*key.y()));
    }
    private void admit(SectionKey key,int index,int state,boolean first){
        if(state>0){var work=new Work(key,index);if(parked.get(index)){transfer(work,false);parked.clear(index);}enqueue(work,first);}
        claimed.set(index);claimedCount++;
    }
    private void unlink(Work work){
        if(work.previous==null)head=work.next;else work.previous.next=work.next;
        if(work.next==null)tail=work.previous;else work.next.previous=work.previous;
        work.previous=work.next=null;queued.remove(work.ordinal);
    }
    private void enqueue(Work work,boolean first){
        if(cleared)return;var existing=queued.get(work.ordinal);if(existing!=null)unlink(existing);
        if(first){work.next=head;if(head!=null)head.previous=work;else tail=work;head=work;}
        else{work.previous=tail;if(tail!=null)tail.next=work;else head=work;tail=work;}
        queued.put(work.ordinal,work);
    }
    /** Unloaded work releases admission slots; partial progress uses at most one bit per real source cell. */
    public void unavailable(Work work){if(cleared||work.finished()||!claimed.get(work.ordinal))return;var existing=queued.get(work.ordinal);if(existing!=null)unlink(existing);if(!work.done.isEmpty()){transfer(work,true);parked.set(work.ordinal);}claimed.clear(work.ordinal);claimedCount--;}
    private void transfer(Work work,boolean store){
        var size=regions.get(work.key.region()).size();
        for(int i=0;i<4096;i++){
            if(store&&!work.done.get(i))continue;
            int sx=work.key.x()*16+(i&15),sy=work.key.y()*16+(i>>>8),sz=work.key.z()*16+((i>>>4)&15);
            if(sx>=size.x()||sy>=size.y()||sz>=size.z())continue;
            int cell=offsets[work.key.region()]+sx+sz*size.x()+sy*size.x()*size.z(),page=cell>>>12,word=(cell&4095)>>>6;long mask=1L<<(cell&63);
            if(store)progress.computeIfAbsent(page,key->new long[64])[word]|=mask;
            else {long[] bits=progress.get(page);if(bits!=null&&(bits[word]&mask)!=0){work.done.set(i);bits[word]&=~mask;}}
        }
    }
    public Work poll(long tick){int attempts=Math.min(128,queued.size());while(attempts-->0){var work=head;unlink(work);if(work.retryAt<=tick)return work;enqueue(work,false);}return null;}
    public void defer(Work work){if(!work.finished())enqueue(work,false);}
    public void resume(Work work){if(!work.finished())enqueue(work,true);}
    public int pending(){return queued.size();}
    public int total(){return total;}
    public int completed(){return claimedCount-queued.size();}
    public boolean finished(){return claimedCount==total&&queued.isEmpty();}
    public void clear(){queued.clear();head=tail=null;claimed.clear();parked.clear();progress.clear();claimedCount=total;cleared=true;}
}
