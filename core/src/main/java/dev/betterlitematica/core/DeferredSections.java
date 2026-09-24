package dev.betterlitematica.core;
import java.util.*;

/** Lazy bounded worklist. One bitmap per pending section, no per-voxel pending objects. */
public final class DeferredSections {
    public static final int LIMIT=32768;
    public static final class Work {
        public final SectionKey key;public final BitSet done=new BitSet(4096);
        public int cursor;public long retryAt;
        private final int ordinal;
        Work(SectionKey key,int ordinal){this.key=key;this.ordinal=ordinal;}
        public boolean finished(){return done.cardinality()==4096;}
    }
    private final List<Region> regions;
    private final ArrayDeque<Work> queue=new ArrayDeque<>();
    private int region,x,y,z,ordinal,claimedCount;private final int total;private final BitSet claimed=new BitSet(),parked=new BitSet();
    private final int[] offsets;private final Map<Integer,long[]> progress=new HashMap<>();
    public DeferredSections(List<Region> regions){this.regions=List.copyOf(regions);offsets=new int[regions.size()];long count=0,cells=0;for(int i=0;i<regions.size();i++){var r=regions.get(i);offsets[i]=Math.toIntExact(cells);cells+=r.volume();count+=(long)((r.size().x()+15)/16)*((r.size().y()+15)/16)*((r.size().z()+15)/16);}if(cells>536_870_912L||count>67_108_864)throw new IllegalArgumentException("Section progress exceeds source budget");total=(int)count;}
    public void admit(int limit){admit(limit,key->true);}
    /** Unavailable sections occupy one discovery bit, never a queue slot that could starve later loaded work. */
    public void admit(int limit,java.util.function.Predicate<SectionKey> available){admit(limit,available,Long.MAX_VALUE);}
    public void admit(int limit,java.util.function.Predicate<SectionKey> available,long deadline){discover(limit,key->available.test(key)?1:-1,deadline);}
    /** -1 waits without a slot, 0 completes an irrelevant section, 1 admits work. */
    public void discover(int limit,java.util.function.ToIntFunction<SectionKey> disposition,long deadline){int inspected=0;while(limit-->0&&inspected++<total&&queue.size()<LIMIT&&claimedCount<total&&System.nanoTime()<deadline){
        var key=new SectionKey(region,x,y,z);
        if(!claimed.get(ordinal)){int state=disposition.applyAsInt(key);if(state>=0){if(state>0){var work=new Work(key,ordinal);if(parked.get(ordinal)){transfer(work,false);parked.clear(ordinal);}queue.addLast(work);}claimed.set(ordinal);claimedCount++;}}
        var size=regions.get(region).size();
        if(++x==(size.x()+15)/16){x=0;if(++z==(size.z()+15)/16){z=0;if(++y==(size.y()+15)/16){y=0;region++;}}}
        if(++ordinal==total){ordinal=0;region=0;}
    }}
    /** Unloaded work releases admission slots; partial progress uses at most one bit per real source cell. */
    public void unavailable(Work work){if(work.finished())return;if(!work.done.isEmpty()){transfer(work,true);parked.set(work.ordinal);}claimed.clear(work.ordinal);claimedCount--;}
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
    public Work poll(long tick){int attempts=Math.min(128,queue.size());while(attempts-->0){var work=queue.removeFirst();if(work.retryAt<=tick)return work;queue.addLast(work);}return null;}
    public void defer(Work work){if(!work.finished())queue.addLast(work);}
    public void resume(Work work){if(!work.finished())queue.addFirst(work);}
    public int pending(){return queue.size();}
    public boolean finished(){return claimedCount==total&&queue.isEmpty();}
    public void clear(){queue.clear();claimed.clear();parked.clear();progress.clear();claimedCount=total;}
}
