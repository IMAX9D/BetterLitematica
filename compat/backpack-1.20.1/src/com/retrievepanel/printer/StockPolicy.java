package com.retrievepanel.printer;

import java.util.*;
import java.util.function.ToIntFunction;

/** Persistent stock decisions, independent of Minecraft and of packet ownership.
 * Queue counts are hints, never inventory targets or proof of completion.
 */
public final class StockPolicy<K> {
    public enum Cause { NONE, EMERGENCY, BULK, LOW, ADMISSION, EMPTY_HAND }
    /** usable counts ordinary stock/empty capacity; deletable additionally requires all safety checks. */
    public record Slot<K>(int index,K item,int count,boolean usable,boolean deletable) {}
    public record Plan<K>(Cause cause,List<Integer> deletes,List<BatchPlanner.Stack<K>> writes,Set<K> admissions) {
        public boolean actionable(){return !writes.isEmpty()||!deletes.isEmpty();}
    }
    public record Settings(int emptyDivisor,int rotationDivisor,int admissionRounds,int protectionRounds,int absenceRounds) {
        public Settings {if(emptyDivisor<1||rotationDivisor<1||admissionRounds<1||protectionRounds<1||absenceRounds<1)throw new IllegalArgumentException();}
        public static Settings defaults(){return new Settings(4,8,6,6,16);}
    }
    private static final class Entry {
        long seen=-100000,missing=-100000,admitted=-100000,lastUse;
        int weight=1;
    }
    private final LinkedHashMap<K,Entry> entries=new LinkedHashMap<>();
    private final LinkedHashMap<K,Long> waiting=new LinkedHashMap<>();
    private final ToIntFunction<K> stackSize;
    private Settings settings;
    private Map<K,Integer> queued=Map.of();
    private long round,clock=Long.MIN_VALUE,useClock,lastRotation=-100000;
    private boolean rotated;
    private long rotationUse;
    private Map<K,Integer> rotationWork=Map.of();
    public StockPolicy(ToIntFunction<K> stackSize,Settings settings){this.stackSize=stackSize;this.settings=settings;}
    public void settings(Settings value){settings=value;}
    /** Hot path: no insertion, allocation, scans, or claimed consumption. */
    public void attempted(K item){Entry entry=entries.get(item);if(entry!=null)entry.lastUse=++useClock;}
    public void reset(){entries.clear();waiting.clear();queued=Map.of();round=0;clock=Long.MIN_VALUE;useClock=0;lastRotation=-100000;rotated=false;rotationWork=Map.of();rotationUse=0;}
    public int waitingCount(){return waiting.size();}
    public long round(){return round;}
    public void observe(long step,Map<K,Integer> work,Set<K> missing,List<Slot<K>> stock,K urgent){
        // A server snapshot within the same evaluation interval must not age candidates twice.
        if(step!=clock){clock=step;round++;}
        queued=new LinkedHashMap<>(work);
        if(urgent!=null)see(urgent,1,true);
        for(var e:work.entrySet())if(e.getValue()>0)see(e.getKey(),e.getValue(),false);
        for(K key:missing)see(key,1,true);
        Map<K,Integer> counts=counts(stock);
        for(var it=entries.entrySet().iterator();it.hasNext();){
            var e=it.next();K key=e.getKey();Entry v=e.getValue();
            if(counts.getOrDefault(key,0)>0)waiting.remove(key);
            else if(strong(key,v))waiting.putIfAbsent(key,round);
            if(round-Math.max(v.seen,v.missing)>128){waiting.remove(key);it.remove();}
        }
    }
    private void see(K item,int demand,boolean missing){
        if(item==null||stackSize.applyAsInt(item)<1)return;
        Entry e=entries.get(item);
        // Bounded history: actual work is at most 4096 jobs, old observations cannot grow without bound.
        if(e==null){if(entries.size()>=8192)return;e=new Entry();entries.put(item,e);}
        e.seen=round;if(missing)e.missing=round;
        e.weight=Math.max(1,Math.min(4096,demand));
    }
    private boolean strong(K key,Entry e){return queued.getOrDefault(key,0)>0||round-e.missing<=settings.absenceRounds();}
    private boolean residentDemand(K key){Entry e=entries.get(key);return e!=null&&round-e.seen<=settings.absenceRounds();}
    private int size(K key){return Math.max(1,Math.min(64,stackSize.applyAsInt(key)));}
    private static <K> Map<K,Integer> counts(List<Slot<K>> slots){
        Map<K,Integer> result=new HashMap<>();for(var s:slots)if(s.usable()&&s.count()>0)result.merge(s.item(),s.count(),Integer::sum);return result;
    }
    /** Temporary operational vacancy (e.g. repeater adjustment), never an AIR generation. */
    public Plan<K> emptyHandPlan(List<Slot<K>> stock,boolean canTrash){
        for(var s:stock)if(s.usable()&&s.count()==0)return none();
        if(!canTrash)return none();
        var victim=victim(stock,counts(stock),Set.of(),List.of(),true);
        return victim==null?none():new Plan<>(Cause.EMPTY_HAND,List.of(victim.index()),List.of(),Set.of());
    }
    public Plan<K> plan(List<Slot<K>> stock,K urgent,Set<K> active,boolean canTrash){
        Map<K,Integer> totals=counts(stock),remaining=new HashMap<>(totals);
        int capacity=0,empty=0;for(var s:stock)if(s.usable()){capacity++;if(s.count()==0)empty++;}
        boolean emergency=urgent!=null&&totals.getOrDefault(urgent,0)==0;
        int rotation=Math.max(1,capacity/settings.rotationDivisor());
        int threshold=Math.max(1,(capacity+settings.emptyDivisor()-1)/settings.emptyDivisor());
        boolean bulk=empty>=threshold;
        boolean low=false;
        for(K key:active)if(queued.containsKey(key)&&totals.getOrDefault(key,0)<=Math.max(1,size(key)/4))low=true;
        boolean aged=false;
        for(var e:waiting.entrySet())if(eligible(e.getKey())&&round-e.getValue()>=settings.admissionRounds()){aged=true;break;}
        // Don't cycle the entire inventory while placement and demand are both stalled.
        boolean opportunity=!rotated||useClock>rotationUse||!queued.equals(rotationWork);
        boolean rotate=aged&&opportunity&&round-lastRotation>=settings.admissionRounds();
        Cause cause=emergency?Cause.EMERGENCY:bulk?Cause.BULK:low&&empty>0?Cause.LOW:rotate?Cause.ADMISSION:Cause.NONE;
        if(cause==Cause.NONE)return none();

        // Reserve urgent and FIFO admissions BEFORE allocating resident top-ups.
        List<K> incoming=new ArrayList<>();List<Integer> deletes=new ArrayList<>();Set<K> retired=new HashSet<>();
        Set<K> hard=new HashSet<>(emergency?Set.of(urgent):active);
        Set<K> chosen=new HashSet<>();int forced=0;
        Set<K> candidates=new LinkedHashSet<>();if(emergency)candidates.add(urgent);
        // A currently executing material wins a naturally empty slot even when its old stack ran out.
        for(K key:active)if(eligible(key)&&totals.getOrDefault(key,0)==0)candidates.add(key);
        for(K key:waiting.keySet())if(eligible(key))candidates.add(key);
        for(K key:candidates){
            if(totals.getOrDefault(key,0)>0||!chosen.add(key))continue;
            if(incoming.size()>=36)break;
            if(incoming.size()>=empty+deletes.size()){
                boolean required=emergency&&Objects.equals(key,urgent);
                Long first=waiting.get(key);
                if(!canTrash||(!required&&(!rotate||forced>=rotation||first==null||round-first<settings.admissionRounds())))continue;
                Slot<K> victim=victim(stock,remaining,hard,deletes,required);
                if(victim==null){if(required)return none();continue;}
                deletes.add(victim.index());remaining.merge(victim.item(),-victim.count(),Integer::sum);
                if(remaining.get(victim.item())==0)retired.add(victim.item());
                if(!required)forced++;
            }
            incoming.add(key);
        }
        int free=empty+deletes.size();if(free==0)return none();
        List<BatchPlanner.Stack<K>> writes=new ArrayList<>();Set<K> admissions=new LinkedHashSet<>();
        Map<K,Integer> slots=new HashMap<>();
        for(var s:stock)if(s.usable()&&s.count()>0&&!deletes.contains(s.index()))slots.merge(s.item(),1,Integer::sum);
        for(K key:incoming){if(writes.size()==free)break;add(writes,slots,key);admissions.add(key);}
        // Stable existing stock remains eligible during short gaps in the scan. No clearing on absence alone.
        LinkedHashSet<K> residents=new LinkedHashSet<>();
        for(K key:active)if(residentDemand(key)&&!retired.contains(key))residents.add(key);
        if(urgent!=null)residents.add(urgent);
        for(var s:stock)if(s.usable()&&s.count()>0&&residentDemand(s.item())&&!retired.contains(s.item()))residents.add(s.item());
        residents.addAll(admissions);
        // Partial stacks count as occupied slots. Top-ups write FULL stacks to empty slots only.
        while(writes.size()<free){
            K best=null;double score=-1;
            for(K key:residents){
                if(size(key)==1)continue; // no speculative duplicates of buckets/tools/nonstackable items
                Entry e=entries.get(key);double weight=e==null?1:e.weight;
                if(active.contains(key))weight*=2;
                double value=weight/Math.max(1,slots.getOrDefault(key,0));
                if(value>score){best=key;score=value;}
            }
            if(best==null)break;add(writes,slots,best);
        }
        if(writes.isEmpty())return none();
        return new Plan<>(cause,List.copyOf(deletes),List.copyOf(writes),Collections.unmodifiableSet(admissions));
    }
    private boolean eligible(K key){Entry e=entries.get(key);return e!=null&&strong(key,e);}
    private void add(List<BatchPlanner.Stack<K>> writes,Map<K,Integer> slots,K key){writes.add(new BatchPlanner.Stack<>(key,size(key)));slots.merge(key,1,Integer::sum);}
    private Plan<K> none(){return new Plan<>(Cause.NONE,List.of(),List.of(),Set.of());}
    private Slot<K> victim(List<Slot<K>> stock,Map<K,Integer> remaining,Set<K> hard,List<Integer> deleted,boolean emergency){
        Slot<K> best=null;int bestRank=Integer.MAX_VALUE;long bestUse=Long.MAX_VALUE;
        for(var s:stock){
            if(!s.usable()||!s.deletable()||s.count()<1||deleted.contains(s.index()))continue;
            int total=remaining.getOrDefault(s.item(),0),after=total-s.count();
            int necessary=total>=size(s.item())?size(s.item()):1;
            if(hard.contains(s.item())&&after<necessary)continue;
            Entry e=entries.get(s.item());boolean fresh=e!=null&&round-e.admitted<settings.protectionRounds();
            boolean duplicate=after>=Math.min(total,size(s.item()));
            if(!emergency&&fresh&&!duplicate)continue;
            int rank=!residentDemand(s.item())?0:duplicate?1:2;
            long use=e==null?0:e.lastUse;
            if(best==null||rank<bestRank||rank==bestRank&&(use<bestUse||use==bestUse&&s.count()<best.count())){
                best=s;bestRank=rank;bestUse=use;
            }
        }
        return best;
    }
    /** Only called after every generation and deletion is confirmed. Never on prediction or send. */
    public void committed(Plan<K> plan){
        for(K key:plan.admissions()){
            Entry e=entries.get(key);if(e!=null)e.admitted=round;
            waiting.remove(key); // re-entry, if later evicted/consumed, joins the FIFO tail
        }
        if(!plan.admissions().isEmpty())lastRotation=round;
        if(!plan.deletes().isEmpty()){rotated=true;rotationUse=useClock;rotationWork=new LinkedHashMap<>(queued);}
    }
}
