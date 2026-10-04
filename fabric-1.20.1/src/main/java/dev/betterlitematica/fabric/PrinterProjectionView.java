package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import java.util.function.Function;

/** Client-tick view: stable placement order, live source cells and bounded section lookups. */
final class PrinterProjectionView implements Function<Vec3i,ProjectionController.PrinterSample> {
    private static final int MAX_SECTIONS=64,MAX_PARTS=256;
    private static final ProjectionController.PrinterSample UNKNOWN=new ProjectionController.PrinterSample(true,null);
    private static final ProjectionController.PrinterSample OUTSIDE=new ProjectionController.PrinterSample(false,null);
    private record Section(List<ProjectionScene.Part> ready,List<PlacementLayout.Part> pending,boolean overflow){}
    private final List<ProjectionRenderer1201> ready;
    private final List<PlacementLayout> pending;
    private final ProjectionScene scene;
    private final boolean unboundedPending;
    private final Map<Vec3i,Section> sections=new HashMap<>();
    PrinterProjectionView(List<ProjectionController.PrinterSource> sources){
        var ready=new ArrayList<ProjectionRenderer1201>();var pending=new ArrayList<PlacementLayout>();boolean unknown=false;
        for(var source:sources){if(!source.placement().enabled())continue;
            if(source.renderer()!=null)ready.add(source.renderer());
            else if(source.metadata()!=null)pending.add(new PlacementLayout(source.placement(),source.metadata().regions()));
            else unknown=true;
        }
        this.ready=List.copyOf(ready);this.pending=List.copyOf(pending);scene=new ProjectionScene(this.ready);unboundedPending=unknown;
    }
    @Override public ProjectionController.PrinterSample apply(Vec3i world){
        if(unboundedPending)return UNKNOWN;
        var key=new Vec3i(world.x()>>4,world.y()>>4,world.z()>>4);var section=sections.get(key);
        if(section==null){if(sections.size()==MAX_SECTIONS)return UNKNOWN;section=section(key);sections.put(key,section);}
        if(section.overflow())return UNKNOWN;
        for(var part:section.pending())if(part.contains(world))return UNKNOWN;
        var cell=scene.sample(section.ready(),world);
        if(cell==null)return OUTSIDE;
        var renderer=cell.renderer();return cell.unknown()||renderer.resolver(cell.region().index()).unresolved(cell.id())?UNKNOWN:new ProjectionController.PrinterSample(true,renderer.resolve(cell.region().index(),cell.id()));
    }
    private Section section(Vec3i key){
        var min=new Vec3i(key.x()<<4,key.y()<<4,key.z()<<4);var box=new PlacementBounds(min,min.add(new Vec3i(15,15,15)));
        var readyParts=new ArrayList<ProjectionScene.Part>();var pendingParts=new ArrayList<PlacementLayout.Part>();
        // Source count is unrestricted; only local query results and cached sections are bounded.
        for(var renderer:ready){for(var part:renderer.layout().overlapping(box)){if(readyParts.size()+pendingParts.size()==MAX_PARTS)return new Section(List.of(),List.of(),true);readyParts.add(new ProjectionScene.Part(renderer,part));}}
        for(var layout:pending){for(var part:layout.overlapping(box)){if(readyParts.size()+pendingParts.size()==MAX_PARTS)return new Section(List.of(),List.of(),true);pendingParts.add(part);}}
        return new Section(List.copyOf(readyParts),List.copyOf(pendingParts),false);
    }
}
