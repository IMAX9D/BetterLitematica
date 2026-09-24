package dev.betterlitematica.core;

import java.util.*;

/** Counts dispatched interactions, including unfinished multi-step actions; polling costs no quota. */
public final class PrinterPacing {
    private final LinkedHashMap<Long,Long> positions=new LinkedHashMap<>();
    private long tick,nextBatch,nextBreak,lastTick=Long.MIN_VALUE,lastExpire=Long.MIN_VALUE;
    private int interval,limit,cooldown,breakInterval,breakLimit,sent,broken;
    private boolean batchReady,breakReady;
    public void begin(long tick,int interval,int limit,int cooldown,int breakInterval,int breakLimit){
        if(interval<0||limit<0||cooldown<0||breakInterval<0||breakLimit<1)throw new IllegalArgumentException("Invalid printer pacing");
        this.tick=tick;this.interval=interval;this.limit=limit;this.cooldown=cooldown;this.breakInterval=breakInterval;this.breakLimit=breakLimit;
        if(tick!=lastTick){lastTick=tick;sent=broken=0;batchReady=tick>=nextBatch;breakReady=tick>=nextBreak;}
    }
    public boolean canRun(boolean breaking){return batchReady&&(limit==0||sent<limit)&&(!breaking||breakReady&&broken<breakLimit);}
    public boolean canDispatch(long position,boolean breaking){if(!canRun(breaking))return false;if(cooldown==0||positions.containsKey(position))return true;if(positions.size()>=8192&&lastExpire!=tick)expire();return positions.size()<8192;}
    public boolean cooling(long position){return positions.getOrDefault(position,Long.MIN_VALUE)>tick;}
    public boolean cooling(long position,long atTick){return positions.getOrDefault(position,Long.MIN_VALUE)>atTick;}
    public void dispatched(long position,boolean breaking){
        if(!canDispatch(position,breaking))throw new IllegalStateException("Printer dispatch exceeds configured budget");
        sent++;nextBatch=tick+interval;if(breaking){broken++;nextBreak=tick+breakInterval;}defer(position,cooldown);
    }
    public void defer(long position,int delay){
        if(delay<0)throw new IllegalArgumentException("Negative cooldown");
        if(delay==0){positions.remove(position);return;}
        // More than the maximum retained work queue, so an eligible queued retry keeps its deadline.
        if(!positions.containsKey(position)&&positions.size()>=8192){if(lastExpire!=tick)expire();if(positions.size()>=8192)return;}
        positions.put(position,tick+delay);
    }
    public void expire(){lastExpire=tick;positions.entrySet().removeIf(e->e.getValue()<=tick);}
    public void reset(){positions.clear();nextBatch=nextBreak=0;lastTick=lastExpire=Long.MIN_VALUE;sent=broken=0;}
}
