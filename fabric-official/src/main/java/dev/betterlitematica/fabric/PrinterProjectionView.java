package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import java.util.function.Function;

final class PrinterProjectionView implements Function<Vec3i,ProjectionController.PrinterSample> {
    private static final int MAX_SECTIONS=64,MAX_PARTS=256;
    private static final ProjectionController.PrinterSample UNKNOWN=new ProjectionController.PrinterSample(true,null);
    private static final ProjectionController.PrinterSample OUTSIDE=new ProjectionController.PrinterSample(false,null);
    private record Section(List<ProjectionScene.Part> ready,List<PlacementLayout.Part> pending,boolean overflow){}
    private final List<ProjectionRenderer1201> ready;
    private final List<PlacementLayout> pending;
    private final ProjectionScene scene;
    private final boolean unboundedPending;
    private final Map<Vec3i,Section> sections=new LinkedHashMap<>();
    private int lastX,lastY,lastZ;
    private Section lastSection;
    private ProjectionController.PrinterSample lastKnown;
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
        int x=world.x()>>4,y=world.y()>>4,z=world.z()>>4;
        Section section=lastSection;
        if(section==null||x!=lastX||y!=lastY||z!=lastZ){
            var key=new Vec3i(x,y,z);
            section=sections.get(key);

            if(section==null){
                if(sections.size()==MAX_SECTIONS)sections.remove(sections.keySet().iterator().next());
                section=section(key);sections.put(key,section);
            }
            lastSection=section;lastX=x;lastY=y;lastZ=z;
        }
        if(section.overflow())return UNKNOWN;
        for(var part:section.pending())if(part.contains(world))return UNKNOWN;
        var cell=scene.sample(section.ready(),world);
        if(cell==null){

            for(var part:section.ready()){
                var r=part.renderer();if(r.metadata().compatibility().skippedBlockTypes()==0||!part.region().contains(world))continue;
                var local=part.region().local(world);int id=r.sampleRaw(part.region().section(local),part.region().cell(local));
                if(id>=0&&r.metadata().palette().get(id).equals(dev.betterlitematica.io.SourceVersions.SKIPPED))return null;
            }
            return OUTSIDE;
        }
        if(cell.unknown())return UNKNOWN;
        var resolver=cell.renderer().resolver(cell.region().index());
        if(resolver.unresolved(cell.id()))return UNKNOWN;
        var state=resolver.resolve(cell.id());
        if(lastKnown==null||lastKnown.state()!=state)lastKnown=new ProjectionController.PrinterSample(true,state);
        return lastKnown;
    }
    private Section section(Vec3i key){
        var min=new Vec3i(key.x()<<4,key.y()<<4,key.z()<<4);var box=new PlacementBounds(min,min.add(new Vec3i(15,15,15)));
        var readyParts=new ArrayList<ProjectionScene.Part>();var pendingParts=new ArrayList<PlacementLayout.Part>();

        for(var renderer:ready){for(var part:renderer.layout().overlapping(box)){if(readyParts.size()+pendingParts.size()==MAX_PARTS)return new Section(List.of(),List.of(),true);readyParts.add(new ProjectionScene.Part(renderer,part));}}
        for(var layout:pending){for(var part:layout.overlapping(box)){if(readyParts.size()+pendingParts.size()==MAX_PARTS)return new Section(List.of(),List.of(),true);pendingParts.add(part);}}
        return new Section(List.copyOf(readyParts),List.copyOf(pendingParts),false);
    }
}
