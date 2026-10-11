package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

/** Outline geometry against shapes whose silhouettes are known exactly. */
public final class HighlightOutlineChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private HighlightOutlineChecks(){}
    private static HighlightCuboids.Box cell(int x,int y,int z,int group){return new HighlightCuboids.Box(new Vec3i(x,y,z),new Vec3i(x,y,z),group);}
    private static int length(List<HighlightOutline.Segment> s){return s.stream().mapToInt(HighlightOutline.Segment::length).sum();}
    public static int run(){
        checks=0;
        var one=HighlightOutline.of(List.of(cell(5,64,-3,0)),1000);
        check(one.size()==12&&length(one)==12,"A single block keeps its twelve edges");
        // A flat slab given as a hundred separate boxes reduces to its outline, not a lattice.
        var slab=new ArrayList<HighlightCuboids.Box>();for(int x=0;x<10;x++)for(int z=0;z<10;z++)slab.add(cell(x,70,z,0));
        var flat=HighlightOutline.of(slab,1000);check(flat.size()==12&&length(flat)==4*10+4*10+4,"A 10x1x10 slab is twelve outline segments: "+flat.size()+" / "+length(flat));
        var cube=new ArrayList<HighlightCuboids.Box>();for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<3;z++)cube.add(cell(x,y,z,1));
        var solid=HighlightOutline.of(cube,1000);check(solid.size()==12&&solid.stream().allMatch(s->s.length()==3),"A solid cube draws only its twelve outer edges");
        check(HighlightOutline.of(List.of(new HighlightCuboids.Box(new Vec3i(0,0,0),new Vec3i(2,2,2),1)),1000).equals(solid),"Merged boxes and single cells outline identically");
        // L tromino one block thick: two six-sided polygons plus six vertical corner edges.
        var tromino=HighlightOutline.of(List.of(cell(0,0,0,0),cell(1,0,0,0),cell(0,0,1,0)),1000);
        check(tromino.size()==18&&length(tromino)==22,"An L tromino outlines as 18 segments, 22 blocks long: "+tromino.size()+" / "+length(tromino));
        // Blocks touching only along an edge share that crease once.
        check(length(HighlightOutline.of(List.of(cell(0,0,0,0),cell(1,1,0,0)),1000))==23,"Diagonal neighbours share one crease edge");
        // Groups (missing, wrong, extra) never merge into each other's outline.
        var groups=HighlightOutline.of(List.of(cell(0,0,0,0),cell(1,0,0,2)),1000);
        check(groups.stream().filter(s->s.group()==0).count()==12&&groups.stream().filter(s->s.group()==2).count()==12,"Each group keeps its own outline");
        check(HighlightOutline.of(slab,99)==null,"Too many cells defers to the per-box fallback");
        // The single block's edge along x at its own corner is pushed away from the block (toward -y, -z).
        var corner=one.stream().filter(s->s.axis()==0&&s.y()==64&&s.z()==-3).findFirst().orElseThrow();
        check(corner.du()==-1&&corner.dv()==-1,"Edges are nudged toward empty space");
        var far=one.stream().filter(s->s.axis()==0&&s.y()==65&&s.z()==-2).findFirst().orElseThrow();
        check(far.du()==1&&far.dv()==1,"The opposite edge is nudged the other way");
        return checks;
    }
}
