package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Tests production chunk hints against independently enumerated world cells. No client required. */
public final class CommandWakeupChecks {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static int run(){checks=0;
        var region=new Region("source",new Vec3i(-17,-39,-18),new Vec3i(33,17,35));
        for(int turn=0;turn<4;turn++)for(boolean mirror:new boolean[]{false,true}){
            var placement=new Placement(UUID.randomUUID(),"check","source.litematic",new PlacementTransform(new Vec3i(7,3,-11),turn,mirror,false),true,false);
            var layout=new PlacementLayout(placement,List.of(region));var transform=layout.part(0).transform();
            var layer=new LayerRange(LayerRange.Axis.Y,-35,-25);int chunkX=-1,chunkZ=-1;
            var expected=new HashSet<SectionKey>();
            for(int y=0;y<region.size().y();y++)for(int z=0;z<region.size().z();z++)for(int x=0;x<region.size().x();x++){
                var at=transform.apply(region.min().add(new Vec3i(x,y,z)));
                if(layer.contains(at)&&(at.x()>>4)==chunkX&&(at.z()>>4)==chunkZ)expected.add(new SectionKey(0,x>>4,y>>4,z>>4));
            }
            var sections=new DeferredSections(List.of(region));var hints=new CommandChunkWakeups(layout,layer,-64,320,100,100,0);
            hints.loaded(chunkX,chunkZ);var actual=new HashSet<SectionKey>();
            for(int slice=0;slice<100;slice++)hints.drain(sections,(x,z)->x==chunkX&&z==chunkZ,k->{actual.add(k);return 1;},1,Long.MAX_VALUE);
            check(actual.equals(expected),"One-cell sliced wake matches negative, unaligned, rotated/mirrored source and layer");
            int count=sections.pending();hints.loaded(chunkX,chunkZ);hints.loaded(chunkX,chunkZ);
            hints.drain(sections,(x,z)->true,k->1,128,Long.MAX_VALUE);check(sections.pending()==count,"Repeated chunk notification does not duplicate source work");
        }
        var big=new Region("ring",new Vec3i(-160,-64,-160),new Vec3i(320,16,320));
        var placement=new Placement(UUID.randomUUID(),"ring","source.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
        var layout=new PlacementLayout(placement,List.of(big));var ring=new CommandChunkWakeups(layout,LayerRange.ALL,-64,320,-2,3,3);
        var seen=new ArrayList<String>();var sections=new DeferredSections(List.of(big));
        ring.drain(sections,(x,z)->{seen.add(x+","+z);return false;},k->1,500,Long.MAX_VALUE);
        check(seen.size()==49&&new HashSet<>(seen).size()==49,"Startup rings visit all 7x7 chunks exactly once");
        check(seen.get(0).equals("-2,3"),"Startup begins with player's existing loaded center chunk");
        for(String value:seen){String[] fields=value.split(",");check(Math.abs(Integer.parseInt(fields[0])+2)<=3&&Math.abs(Integer.parseInt(fields[1])-3)<=3,"Startup ring stays within configured radius");}
        ring.loaded(-2,3);ring.drain(sections,(x,z)->true,k->1,10,0);check(sections.pending()==0,"Expired deadline cannot consume queued wakeup");
        ring.drain(sections,(x,z)->true,k->1,100,Long.MAX_VALUE);check(sections.pending()==1,"Explicit load wakes previously unknown chunk after startup ring ends");
        var work=sections.poll(0);work.retryAt=100;work.done.set(7);sections.defer(work);ring.loaded(-2,3);
        ring.drain(sections,(x,z)->true,k->1,100,Long.MAX_VALUE);check(sections.poll(0)==work&&work.done.get(7),"Load immediately wakes delayed partial work without losing completed cells");
        var disabled=new CommandChunkWakeups(new PlacementLayout(placement.enabled(false),List.of(big)),LayerRange.ALL,-64,320,0,0,0);
        disabled.drain(new DeferredSections(List.of(big)),(x,z)->true,k->{throw new AssertionError("Disabled placement cannot be woken");},100,Long.MAX_VALUE);checks++;
        var crowded=new Region("crowded",Vec3i.ZERO,new Vec3i((DeferredSections.LIMIT+1)*16,1,1));
        var full=new DeferredSections(List.of(crowded));full.admit(DeferredSections.LIMIT);
        var pendingHint=new CommandChunkWakeups(new PlacementLayout(placement,List.of(crowded)),LayerRange.ALL,-64,320,-1,-1,0);
        pendingHint.loaded(DeferredSections.LIMIT,0);
        pendingHint.drain(full,(x,z)->true,k->1,100,Long.MAX_VALUE);
        check(full.pending()==DeferredSections.LIMIT,"Loaded hint respects full source queue budget");
        full.unavailable(full.poll(0));
        pendingHint.drain(full,(x,z)->true,k->1,100,Long.MAX_VALUE);
        check(full.poll(0).key.equals(new SectionKey(0,DeferredSections.LIMIT,0,0)),"Capacity-blocked hint resumes same key when a slot opens, without a source-wide scan");
        return checks;
    }
    public static void main(String[] args){System.out.println("CommandWakeupChecks: "+run()+" checks");}
}
