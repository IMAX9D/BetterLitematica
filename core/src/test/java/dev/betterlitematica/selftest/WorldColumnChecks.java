package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

public final class WorldColumnChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){
        checks=0;var changes=new WorldColumnChanges(2048);
        // A 65-column frontier in a tall world previously overflowed the section queue.
        for(int z=-32;z<=32;z++)changes.chunk(-7,z,-64,960);
        check(changes.pending()==65&&!changes.takeAll(),"Tall-world flight retains a local frontier instead of invalidating the entire projection");
        var seen=new HashSet<PlacementBounds>();
        while(changes.pending()>0){var batch=changes.drain(8);check(batch.size()<=8,"One frame consumes bounded columns");seen.addAll(batch);}
        check(seen.size()==65,"Partial drains retain every distinct column");
        check(seen.contains(new PlacementBounds(new Vec3i(-112,-64,-512),new Vec3i(-97,959,-497))),"Negative coordinates and inclusive height remain exact");
        changes.chunk(-1,2,0,320);changes.chunk(-1,2,-64,256);changes.chunk(-1,2,0,1024);
        check(changes.pending()==1,"Repeated load/unload notifications coalesce");
        check(changes.drain(1).get(0).equals(new PlacementBounds(new Vec3i(-16,-64,32),new Vec3i(-1,1023,47))),"Coalescing preserves the union of changed heights");
        changes.chunk(1,-1,0,1);changes.chunk(-1,1,0,1);check(changes.pending()==2,"Column keys do not alias when coordinate signs are reversed");changes.clear();
        for(int i=0;i<=2048;i++)changes.chunk(i,0,0,1024);
        check(changes.pending()==0&&changes.takeAll()&&!changes.takeAll(),"True column overflow still requests exactly one full refresh");
        changes.chunk(0,0,10,10);check(changes.pending()==0,"Empty height emits no invalidation");
        changes.chunk(0,0,0,1024);check(changes.drain(0).isEmpty()&&changes.pending()==1,"Zero drain retains pending work");changes.clear();
        check(changes.pending()==0&&!changes.takeAll(),"Disconnect clears all queued work");
        return checks;
    }
}
