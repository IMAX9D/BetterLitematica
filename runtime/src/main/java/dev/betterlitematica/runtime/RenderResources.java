package dev.betterlitematica.runtime;

import java.util.*;

/** Render-thread ownership of resident, in-flight and retired GPU allocations. */
public final class RenderResources {
    public static final int COLD=0,NEARBY=1,VISIBLE=2;
    private final long capacity;
    private final int maxObjects,maxGroups;
    private final long normalCapacity;
    private final int normalObjects,normalGroups;
    private long used,pendingBytes,revision,frame,wakeFrame=Long.MAX_VALUE;
    private int objects,pendingObjects,groups,pendingGroups;
    private Group builder;
    private final List<LinkedHashSet<Group>> lanes=List.of(new LinkedHashSet<>(),new LinkedHashSet<>(),new LinkedHashSet<>());
    private final ArrayDeque<Group> retired=new ArrayDeque<>();
    public RenderResources(long capacity,int maxObjects,int maxGroups){
        this(capacity,maxObjects,maxGroups,0,0,0);
    }
    /** Replacement headroom is part of the hard budget, never additional capacity. */
    public RenderResources(long capacity,int maxObjects,int maxGroups,long reservedBytes,int reservedObjects,int reservedGroups){
        if(capacity<1||maxObjects<1||maxGroups<1)throw new IllegalArgumentException();
        if(reservedBytes<0||reservedBytes>=capacity||reservedObjects<0||reservedObjects>=maxObjects||reservedGroups<0||reservedGroups>=maxGroups)throw new IllegalArgumentException("Invalid replacement reserve");
        this.capacity=capacity;this.maxObjects=maxObjects;this.maxGroups=maxGroups;
        normalCapacity=capacity-reservedBytes;normalObjects=maxObjects-reservedObjects;normalGroups=maxGroups-reservedGroups;
    }
    public final class Lease {
        private final int bytes;private Runnable release;private boolean released;
        private Lease(int bytes){this.bytes=bytes;}
        public void attach(Runnable release){if(this.release!=null||released)throw new IllegalStateException("Allocation already assigned");this.release=Objects.requireNonNull(release);}
        private void dispose(){if(released)return;if(release!=null)release.run();released=true;release=null;used-=bytes;pendingBytes-=bytes;objects--;pendingObjects--;revision++;}
    }
    public final class Group implements AutoCloseable {
        private final ArrayList<Lease> allocations=new ArrayList<>();
        private long bytes,lastVisible=-1000;private int priority=COLD,cursor;
        private boolean building=true,closed;private Runnable evicted;
        private final boolean replacement;
        private Group(boolean replacement){this.replacement=replacement;}
        /** All-or-nothing reservation, before creating any native resource. */
        public List<Lease> reserve(int[] sizes,int demand){
            if(closed||!building)throw new IllegalStateException("Group is no longer building");
            if(demand<COLD||demand>VISIBLE)throw new IllegalArgumentException();
            long total=0;for(int size:sizes){if(size<=0)throw new IllegalArgumentException();total=Math.addExact(total,size);}
            if(bytes+total>8L<<20)throw new IllegalArgumentException("Mesh resource limit exceeded");
            if(total>capacity||sizes.length>maxObjects)throw new IllegalArgumentException("Allocation exceeds total budget");
            long byteLimit=replacement?capacity:normalCapacity;int objectLimit=replacement?maxObjects:normalObjects;
            if(total>byteLimit||sizes.length>objectLimit)return null;
            if(total>byteLimit-used||sizes.length>objectLimit-objects){request(total,sizes.length,0,demand,replacement);return null;}
            var result=new ArrayList<Lease>(sizes.length);
            for(int size:sizes){var lease=new Lease(size);allocations.add(lease);result.add(lease);}
            bytes+=total;used+=total;objects+=sizes.length;return result;
        }
        public void finish(int priority,Runnable evicted){if(closed||!building)throw new IllegalStateException();this.evicted=Objects.requireNonNull(evicted);building=false;builder=null;revision++;this.priority=priority;lanes.get(priority).add(this);if(priority==VISIBLE)lastVisible=frame;}
        public void priority(int value){
            if(value<COLD||value>VISIBLE)throw new IllegalArgumentException();if(closed||building)return;
            if(value==VISIBLE||priority==VISIBLE)lastVisible=frame;
            if(priority!=value){lanes.get(priority).remove(this);lanes.get(value).add(this);priority=value;revision++;}
        }
        public boolean closed(){return closed;}
        @Override public void close(){
            if(closed)return;closed=true;if(!building)lanes.get(priority).remove(this);
            if(building){builder=null;revision++;}
            if(allocations.isEmpty()){groups--;revision++;}
            else {pendingBytes+=bytes;pendingObjects+=allocations.size();pendingGroups++;retired.add(this);}
            // Detach the cache entry now; native deletion remains incremental and charged.
            var callback=evicted;evicted=null;if(callback!=null)callback.run();
        }
    }
    public Group begin(int demand){
        return begin(demand,false);
    }
    /** Build a new mesh while its old resident mesh is still visible and charged. */
    public Group beginReplacement(int demand){
        return begin(demand,true);
    }
    private Group begin(int demand,boolean replacement){
        if(demand<COLD||demand>VISIBLE)throw new IllegalArgumentException();
        if(builder!=null)return null;
        int groupLimit=replacement?maxGroups:normalGroups;
        if(groups>=groupLimit){request(0,0,1,demand,replacement);if(groups>=groupLimit)return null;}groups++;builder=new Group(replacement);return builder;
    }
    public void nextFrame(){frame++;if(frame>=wakeFrame){wakeFrame=Long.MAX_VALUE;revision++;}}
    private void request(long bytes,int count,int groupCount,int demand,boolean replacement){
        int checked=0,removed=0;
        long byteLimit=replacement?capacity:normalCapacity;int objectLimit=replacement?maxObjects:normalObjects,groupLimit=replacement?maxGroups:normalGroups;
        // Pending deletions already promise space: don't retire the whole cache repeatedly.
        for(int lane=0;lane<demand&&checked<128&&removed<8;lane++){
            var values=lanes.get(lane);int available=values.size();
            while(available-->0&&!values.isEmpty()&&checked++<128&&removed<8&&
                    (bytes>byteLimit-(used-pendingBytes)||count>objectLimit-(objects-pendingObjects)||groupCount>groupLimit-(groups-pendingGroups))){
                var candidate=values.iterator().next();
                if(lane==NEARBY&&frame-candidate.lastVisible<=10){wakeFrame=Math.min(wakeFrame,candidate.lastVisible+11);values.remove(candidate);values.add(candidate);continue;}
                candidate.close();removed++;
            }
        }
    }
    /** Actual releases, including zero-byte mesh records, have a work and time budget. */
    public int drain(int limit,long deadline){
        if(limit<1)throw new IllegalArgumentException();int work=0;
        while(!retired.isEmpty()&&work<limit&&System.nanoTime()<deadline){
            var group=retired.peek();
            if(group.cursor<group.allocations.size()){group.allocations.get(group.cursor).dispose();group.cursor++;work++;}
            else {retired.remove();group.allocations.clear();groups--;pendingGroups--;revision++;work++;}
        }return work;
    }
    public boolean warm(){return retired.isEmpty()&&used<capacity*3/4&&objects<maxObjects*3/4&&groups<maxGroups*3/4;}
    public long usedBytes(){return used;}public int objects(){return objects;}public int groups(){return groups;}
    public long pendingBytes(){return pendingBytes;}public long revision(){return revision;}
}
