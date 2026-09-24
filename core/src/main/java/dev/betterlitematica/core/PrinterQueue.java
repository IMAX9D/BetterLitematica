package dev.betterlitematica.core;

import java.util.*;

/** Bounded material batches. Prefer the held material without starving another queued material. */
public final class PrinterQueue {
    public enum Kind { PLACE, ADJUST, BREAK, FILL, FLUID, BEDROCK }
    public record Job(long generation,long position,int expected,int observed,Kind kind) {}
    private record Key(long position,Kind kind) {}
    private final int limit;
    private final Map<Integer,ArrayDeque<Job>> buckets=new LinkedHashMap<>();
    private final LinkedHashSet<Integer> turns=new LinkedHashSet<>();
    private final Set<Key> keys=new HashSet<>();
    private int lastMaterial=Integer.MIN_VALUE,burst;
    private int batchMaterial,batchRemaining;
    private boolean batchStarted;
    public PrinterQueue(int limit){if(limit<1||limit>65536)throw new IllegalArgumentException();this.limit=limit;}
    public boolean offer(Job job){return offer(job,job.expected());}
    public boolean offer(Job job,int material){
        var key=new Key(job.position(),job.kind());if(keys.size()>=limit||!keys.add(key))return false;
        var queue=buckets.get(material);
        if(queue==null){queue=new ArrayDeque<>();buckets.put(material,queue);turns.add(material);}queue.addLast(job);return true;
    }
    public Job poll(){
        resetBatch();
        return turns.isEmpty()?null:take(turns.iterator().next());
    }
    public Job poll(int heldMaterial,int batchLimit){
        if(batchLimit<1)throw new IllegalArgumentException("Invalid material batch");resetBatch();if(turns.isEmpty())return null;
        int material=turns.iterator().next();
        if(buckets.containsKey(heldMaterial)&&(heldMaterial!=lastMaterial||burst<batchLimit||turns.size()==1))material=heldMaterial;
        else if(material==lastMaterial&&burst>=batchLimit&&turns.size()>1)for(int next:turns)if(next!=lastMaterial){material=next;break;}
        return take(material);
    }
    /**
     * Consume a finite material snapshot across calls/ticks. Later offers never extend the
     * active batch. Only the first batch of an uninterrupted queue prefers the held item;
     * subsequent batches use FIFO turns, so late materials also receive their turn.
     * Mixing a legacy poll method ends the snapshot and starts a new scheduling boundary.
     */
    public Job pollBatch(int heldMaterial){
        if(turns.isEmpty()){resetBatch();return null;}
        if(batchRemaining==0){
            batchMaterial=!batchStarted&&buckets.containsKey(heldMaterial)?heldMaterial:turns.iterator().next();
            batchRemaining=buckets.get(batchMaterial).size();batchStarted=true;
        }
        var job=take(batchMaterial);batchRemaining--;
        if(turns.isEmpty())resetBatch();
        return job;
    }
    private void resetBatch(){batchStarted=false;batchRemaining=0;}
    private Job take(int material){
        turns.remove(material);var queue=buckets.get(material);var job=queue.removeFirst();keys.remove(new Key(job.position(),job.kind()));
        if(queue.isEmpty())buckets.remove(material);else turns.add(material);
        burst=material==lastMaterial?burst+1:1;lastMaterial=material;return job;
    }
    public int size(){return keys.size();}
    public int remaining(){return limit-size();}
    public void clear(){keys.clear();turns.clear();buckets.clear();lastMaterial=Integer.MIN_VALUE;burst=0;resetBatch();}
}
