package com.retrievepanel.printer;
import java.util.*;

/** Whole-stack reclamation, protecting the active material window and caller-protected slots. */
public final class EvictionPlanner {
    public record Slot<K>(int index,K item,int count,boolean safe,boolean protectedSlot){}
    public static <K> List<Integer> plan(List<Slot<K>> slots,Map<K,Integer> keep,Set<K> future,
                                        MaterialQueue<K> queue,int required){
        if(required<=0)return List.of();
        Map<K,Integer> remaining=new HashMap<>();for(var s:slots)remaining.merge(s.item(),s.count(),Integer::sum);
        List<Slot<K>> candidates=new ArrayList<>(slots);
        candidates.sort(Comparator.<Slot<K>>comparingInt(s->keep.containsKey(s.item())?2:future.contains(s.item())?1:0)
            .thenComparingLong(s->queue.usedAt(s.item())).thenComparingInt(Slot::index));
        List<Integer> result=new ArrayList<>();
        for(var s:candidates){
            if(result.size()>=required)break;
            if(!s.safe()||s.protectedSlot()||s.count()<=0)continue;
            int after=remaining.getOrDefault(s.item(),0)-s.count();
            if(after<keep.getOrDefault(s.item(),0))continue;
            result.add(s.index());remaining.put(s.item(),after);
        }
        return List.copyOf(result);
    }
}
