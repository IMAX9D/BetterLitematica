package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

final class ProjectionScene {
    record Part(ProjectionRenderer1201 renderer,PlacementLayout.Part region){}
    record Cell(ProjectionRenderer1201 renderer,PlacementLayout.Part region,Vec3i local,int id){boolean unknown(){return id<0;}}
    private final List<ProjectionRenderer1201> sources;
    private final List<SceneChanges.Source> snapshots;
    ProjectionScene(List<ProjectionRenderer1201> sources){this.sources=List.copyOf(sources);snapshots=sources.stream().map(r->new SceneChanges.Source(r,r.layout())).toList();}
    SceneChanges changesFrom(ProjectionScene previous,ProjectionRenderer1201 observer){return SceneChanges.between(previous==null?List.of():previous.snapshots,snapshots,observer);}
    List<Part> overlapping(PlacementBounds box){var parts=new ArrayList<Part>();for(var r:sources)for(var p:r.layout().overlapping(box))parts.add(new Part(r,p));return List.copyOf(parts);}
    Cell sample(Vec3i world){return sample(overlapping(new PlacementBounds(world,world)),world);}
    Cell sample(List<Part> parts,Vec3i world){
        return sample(parts,world,false);
    }
    Cell sampleDisplayed(List<Part> parts,Vec3i world){return sample(parts,world,true);}
    private Cell sample(List<Part> parts,Vec3i world,boolean displayOnly){
        Cell result=null,placement=null;ProjectionRenderer1201 current=null;boolean air=true;

        for(var p:parts){
            var r=p.renderer();
            if(r!=current){result=merge(result,placement,displayOnly);placement=null;air=true;current=r;}
            if(!p.region().contains(world))continue;
            var local=p.region().local(world);int id=r.sampleRaw(p.region().section(local),p.region().cell(local));var rule=r.layout().placement().overlapRule();
            if(id<0){if(rule==ReplaceRule.NONE&&placement!=null&&!placement.unknown()&&!air)continue;placement=new Cell(r,p.region(),local,-1);continue;}
            var spec=r.metadata().palette().get(id);if(spec.name().equals("minecraft:structure_void"))continue;
            if(rule==ReplaceRule.NON_AIR&&spec.isAir())continue;
            if(rule==ReplaceRule.NONE&&placement!=null&&(placement.unknown()||!air))continue;
            placement=new Cell(r,p.region(),local,id);air=spec.isAir();
        }
        return merge(result,placement,displayOnly);
    }
    private static Cell merge(Cell previous,Cell next,boolean displayOnly){
        if(next==null)return previous;

        if(displayOnly&&!next.unknown()&&!next.renderer().displays(next.id()))return previous;

        if(!next.unknown()&&next.renderer().metadata().palette().get(next.id()).isAir())return previous==null?next:previous;
        if(next.renderer().layout().placement().overlapRule()==ReplaceRule.NONE&&previous!=null
            &&(previous.unknown()||!previous.renderer().metadata().palette().get(previous.id()).isAir()))return previous;
        return next;
    }
}
