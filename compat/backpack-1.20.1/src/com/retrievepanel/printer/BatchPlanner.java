package com.retrievepanel.printer;

import java.util.*;

/** Pure, bounded planning: one stack per material per round; never touch occupied slots. */
public final class BatchPlanner {
    public record Demand<T>(T item, int missing, int stackSize) {}
    public record Stack<T>(T item, int count) {}
    public static <T> List<Stack<T>> plan(List<Demand<T>> demands, int emptySlots, int limit, int reserve) {
        int capacity = Math.min(Math.max(0, limit), Math.max(0, emptySlots - Math.min(reserve, Math.max(0, emptySlots - 1))));
        List<Stack<T>> result = new ArrayList<>(capacity);
        int[] remaining = new int[demands.size()];
        for (int i=0;i<remaining.length;i++) remaining[i]=Math.max(0,demands.get(i).missing());
        boolean progress=true;
        while (result.size()<capacity && progress) {
            progress=false;
            for (int i=0;i<remaining.length && result.size()<capacity;i++) {
                Demand<T> d=demands.get(i);
                if (remaining[i]==0 || d.stackSize()<1) continue;
                int count=Math.min(Math.min(64,d.stackSize()),remaining[i]);
                result.add(new Stack<>(d.item(),count));remaining[i]-=count;progress=true;
            }
        }
        return result;
    }
}
