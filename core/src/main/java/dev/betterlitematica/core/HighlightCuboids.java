package dev.betterlitematica.core;

import java.util.*;

/** Exact run merging: a box never encloses a missing cell or a different group. */
public final class HighlightCuboids {
    private HighlightCuboids() {}
    public record Cell(Vec3i position,int group) {}
    public record Box(Vec3i min,Vec3i max,int group) {}
    public static List<Box> merge(Collection<Cell> cells) {
        var unique=new HashSet<>(cells);var boxes=new ArrayList<Box>(unique.size());
        for(var cell:unique)boxes.add(new Box(cell.position(),cell.position(),cell.group()));
        return coalesce(boxes);
    }
    public static List<Box> coalesce(Collection<Box> input) {
        var boxes=new ArrayList<>(input);
        for(int axis=0;axis<3;axis++) {
            final int a=axis,b=(axis+1)%3,c=(axis+2)%3;
            boxes.sort(Comparator.comparingInt(Box::group)
                .thenComparingInt(v->axis(v.min(),b)).thenComparingInt(v->axis(v.max(),b))
                .thenComparingInt(v->axis(v.min(),c)).thenComparingInt(v->axis(v.max(),c))
                .thenComparingInt(v->axis(v.min(),a)));
            var merged=new ArrayList<Box>();Box previous=null;
            for(var box:boxes) {
                if(previous!=null&&previous.group()==box.group()
                    &&axis(previous.min(),b)==axis(box.min(),b)&&axis(previous.max(),b)==axis(box.max(),b)
                    &&axis(previous.min(),c)==axis(box.min(),c)&&axis(previous.max(),c)==axis(box.max(),c)
                    &&(long)axis(previous.max(),a)+1==axis(box.min(),a)) {
                    previous=new Box(previous.min(),replace(previous.max(),a,axis(box.max(),a)),box.group());
                    merged.set(merged.size()-1,previous);
                } else {previous=box;merged.add(box);}
            }
            boxes=merged;
        }
        return List.copyOf(boxes);
    }
    private static int axis(Vec3i p,int a){return a==0?p.x():a==1?p.y():p.z();}
    private static Vec3i replace(Vec3i p,int a,int n){return a==0?new Vec3i(n,p.y(),p.z()):a==1?new Vec3i(p.x(),n,p.z()):new Vec3i(p.x(),p.y(),n);}
}
