package dev.betterlitematica.fabric;

import java.util.HashMap;
import java.util.Map;

/** Independent target retries and a bounded per-tick budget for frame-rate mouse strokes. */
final class PlacementStroke {
    static final int MAX_PER_TICK=8,RETRY_TICKS=4;
    private final Map<Long,Integer> retryAt=new HashMap<>();
    private int lastTick=Integer.MIN_VALUE,attempts;
    boolean allow(long position,int tick){
        if(tick!=lastTick){lastTick=tick;attempts=0;retryAt.values().removeIf(until->until<=tick);}
        if(attempts>=MAX_PER_TICK||retryAt.getOrDefault(position,Integer.MIN_VALUE)>tick)return false;
        attempts++;retryAt.put(position,tick+RETRY_TICKS);return true;
    }
    void clear(){retryAt.clear();lastTick=Integer.MIN_VALUE;attempts=0;}
}
