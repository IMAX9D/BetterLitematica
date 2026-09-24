package dev.betterlitematica.core;

/** One owned placement transaction. Only authoritative observations authorize the destructive step. */
public final class IceWaterPlan {
    public enum Observation {ICE,WATER,AIR,OTHER}
    public enum Step {WAIT,BREAK,DONE,ABORT}
    private enum Phase {PLACE,READY,WATER,DONE,CANCELLED}
    private final long generation,position;private final int deadline;private Phase phase=Phase.PLACE;private boolean breakingSent;
    public IceWaterPlan(long generation,long position,int tick){this.generation=generation;this.position=position;deadline=tick+200;}
    public boolean owns(long generation,long position){return phase!=Phase.CANCELLED&&this.generation==generation&&this.position==position;}
    public void confirm(Observation observation){
        if(phase==Phase.CANCELLED)return;
        if(observation==Observation.ICE){if(phase==Phase.PLACE)phase=Phase.READY;else if(phase!=Phase.READY)phase=Phase.CANCELLED;}
        else if(observation==Observation.WATER){phase=breakingSent&&(phase==Phase.READY||phase==Phase.WATER||phase==Phase.DONE)?Phase.DONE:Phase.CANCELLED;}
        else if(observation==Observation.AIR&&breakingSent&&(phase==Phase.READY||phase==Phase.WATER))phase=Phase.WATER;
        else phase=Phase.CANCELLED;
    }
    public void breakingSent(){if(phase==Phase.READY)breakingSent=true;}
    public boolean requiresBreaking(){return phase==Phase.READY;}
    public Step next(int tick,Observation local,boolean eligible){
        if(phase==Phase.CANCELLED||tick>=deadline||!eligible)return Step.ABORT;
        if(phase==Phase.DONE)return local==Observation.WATER?Step.DONE:Step.ABORT;
        if(phase==Phase.READY){if(local==Observation.ICE)return Step.BREAK;if(!breakingSent)return Step.ABORT;phase=Phase.WATER;}
        return Step.WAIT;
    }
    public void cancel(){phase=Phase.CANCELLED;}
}
