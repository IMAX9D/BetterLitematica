package dev.betterlitematica.fabric;

/** Prevent a global delay from returning while keeping repeated packets bounded. */
public final class PlacementStrokeCheck {
    public static void main(String[] args){
        var stroke=new PlacementStroke();
        for(int i=0;i<PlacementStroke.MAX_PER_TICK;i++)check(stroke.allow(i,10),"Different targets in one tick must be immediate");
        check(!stroke.allow(100,10),"Tick budget must be bounded");
        check(!stroke.allow(0,11),"Same target must await confirmation");
        check(stroke.allow(100,11),"Another target must not inherit its delay");
        check(!stroke.allow(0,13),"Same target retry came too early");
        check(stroke.allow(0,14),"Same target must eventually retry");
        stroke.clear();check(stroke.allow(0,14),"A new world or session must clear prior attempts");
        for(int tick=20;tick<1020;tick++){int accepted=0;for(long pos=1000;pos<1100;pos++)if(stroke.allow(pos,tick))accepted++;check(accepted==PlacementStroke.MAX_PER_TICK,"Long strokes must remain bounded without stalling");}
        System.out.println("Placement stroke: 1014 checks passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
