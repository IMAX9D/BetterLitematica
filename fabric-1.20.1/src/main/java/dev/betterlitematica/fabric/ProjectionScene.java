package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Stable source/region order; air and unavailable data have distinct composition semantics. */
final class ProjectionScene {
    record Part(ProjectionRenderer1201 renderer,PlacementLayout.Part region){}
    record Cell(ProjectionRenderer1201 renderer,PlacementLayout.Part region,Vec3i local,int id){boolean unknown(){return id<0;}}
    private final List<ProjectionRenderer1201> sources;
    ProjectionScene(List<ProjectionRenderer1201> sources){this.sources=List.copyOf(sources);}
    List<Part> overlapping(PlacementBounds box){var parts=new ArrayList<Part>();for(var r:sources)for(var p:r.layout().overlapping(box))parts.add(new Part(r,p));return List.copyOf(parts);}
    Cell sample(Vec3i world){return sample(overlapping(new PlacementBounds(world,world)),world);}
    Cell sample(List<Part> parts,Vec3i world){
        Cell result=null;boolean air=true;
        for(var p:parts){if(!p.region().contains(world))continue;var r=p.renderer();var local=p.region().local(world);int id=r.sampleRaw(p.region().section(local),p.region().cell(local));var rule=r.layout().placement().overlapRule();
            if(id<0){if(rule==ReplaceRule.NONE&&result!=null&&!result.unknown()&&!air)continue;result=new Cell(r,p.region(),local,-1);continue;}
            var spec=r.metadata().palette().get(id);if(spec.name().equals("minecraft:structure_void"))continue;
            if(rule==ReplaceRule.NON_AIR&&spec.isAir())continue;
            if(rule==ReplaceRule.NONE&&result!=null&&(result.unknown()||!air))continue;
            result=new Cell(r,p.region(),local,id);air=spec.isAir();
        }
        return result;
    }
}
