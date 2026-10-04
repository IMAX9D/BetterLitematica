package dev.betterlitematica.core;

import java.util.*;

/** Immutable transformed region domains, shared by rendering, verification and construction. */
public final class PlacementLayout {
    public record Part(int index,Region region,PlacementTransform transform,PlacementBounds bounds) {
        public boolean contains(Vec3i p){return inside(bounds,p);}
        public Vec3i local(Vec3i p){return transform.inverse(p);}
        public SectionKey section(Vec3i local){var p=local.subtract(region.min());return new SectionKey(index,p.x()>>4,p.y()>>4,p.z()>>4);}
        public int cell(Vec3i local){var p=local.subtract(region.min());return PackedSection.index(p.x()&15,p.y()&15,p.z()&15);}
    }
    public record Cell(Part part,Vec3i local,int state){public boolean unknown(){return state<0;}}
    public interface Source {int state(Part part,Vec3i local);BlockStateSpec spec(int region,int state);}
    private record Node(PlacementBounds bounds,Node a,Node b,Part part){}
    private final Placement placement;private final Part[] parts;private final Node root;
    public PlacementLayout(Placement placement,List<Region> regions){
        this.placement=placement;parts=new Part[regions.size()];var enabled=new ArrayList<Part>();
        for(int i=0;i<parts.length;i++){var r=regions.get(i);var t=placement.transformFor(r);parts[i]=new Part(i,r,t,PlacementBounds.clipped(r,t,LayerRange.ALL));if(placement.enabled()&&placement.region(r).enabled())enabled.add(parts[i]);}
        root=tree(enabled);
    }
    public Placement placement(){return placement;}public Part part(int i){return parts[i];}public int size(){return parts.length;}
    public boolean enabled(int i){return placement.enabled()&&placement.region(parts[i].region()).enabled();}
    public boolean intersects(PlacementBounds box){return intersectsAny(root,box);}
    private static boolean intersectsAny(Node node,PlacementBounds box){return node!=null&&intersects(node.bounds(),box)&&(node.part()!=null||intersectsAny(node.a(),box)||intersectsAny(node.b(),box));}
    public List<Part> overlapping(PlacementBounds box){var result=new ArrayList<Part>();collect(root,box,result);result.sort(Comparator.comparingInt(Part::index));return List.copyOf(result);}
    public List<Part> at(Vec3i position){return overlapping(new PlacementBounds(position,position));}
    /** Null reports result/node-budget overflow, including broad overlapping bounds with few hits. */
    public List<Part> at(Vec3i position,int limit){
        if(limit<1)throw new IllegalArgumentException("Positive overlap limit required");
        var result=new ArrayList<Part>();if(!collectLimited(root,new PlacementBounds(position,position),result,limit,new int[]{(int)Math.min(8192L,Math.max(64L,limit*32L))}))return null;
        result.sort(Comparator.comparingInt(Part::index));return List.copyOf(result);
    }
    private static boolean collectLimited(Node node,PlacementBounds bounds,List<Part> output,int limit,int[] remaining){
        if(node==null)return true;if(remaining[0]--<=0)return false;
        if(!intersects(node.bounds(),bounds))return true;
        if(node.part()!=null){if(output.size()==limit)return false;output.add(node.part());return true;}
        return collectLimited(node.a(),bounds,output,limit,remaining)&&collectLimited(node.b(),bounds,output,limit,remaining);
    }
    public Cell sample(Vec3i world,Source source){return sample(at(world),world,source);}
    public Cell sample(List<Part> candidates,Vec3i world,Source source){
        Cell result=null;boolean air=true;
        for(var part:candidates){if(!part.contains(world))continue;var local=part.local(world);int id=source.state(part,local);var rule=placement.overlapRule();
            if(id<0){if(rule==ReplaceRule.NONE&&result!=null&&!result.unknown()&&!air)continue;result=new Cell(part,local,-1);continue;}
            var spec=source.spec(part.index(),id);if(spec.name().equals("minecraft:structure_void"))continue;
            if(rule==ReplaceRule.NON_AIR&&spec.isAir())continue;
            if(rule==ReplaceRule.NONE&&result!=null&&(result.unknown()||!air))continue;
            result=new Cell(part,local,id);air=spec.isAir();
        }
        return result;
    }
    public static boolean inside(PlacementBounds b,Vec3i p){return p.x()>=b.min().x()&&p.x()<=b.max().x()&&p.y()>=b.min().y()&&p.y()<=b.max().y()&&p.z()>=b.min().z()&&p.z()<=b.max().z();}
    public static boolean intersects(PlacementBounds a,PlacementBounds b){return a.min().x()<=b.max().x()&&a.max().x()>=b.min().x()&&a.min().y()<=b.max().y()&&a.max().y()>=b.min().y()&&a.min().z()<=b.max().z()&&a.max().z()>=b.min().z();}
    private static PlacementBounds union(PlacementBounds a,PlacementBounds b){return new PlacementBounds(new Vec3i(Math.min(a.min().x(),b.min().x()),Math.min(a.min().y(),b.min().y()),Math.min(a.min().z(),b.min().z())),new Vec3i(Math.max(a.max().x(),b.max().x()),Math.max(a.max().y(),b.max().y()),Math.max(a.max().z(),b.max().z())));}
    private static Node tree(List<Part> values){
        if(values.isEmpty())return null;if(values.size()==1){var p=values.get(0);return new Node(p.bounds(),null,null,p);}
        var box=values.get(0).bounds();for(int i=1;i<values.size();i++)box=union(box,values.get(i).bounds());long x=(long)box.max().x()-box.min().x(),y=(long)box.max().y()-box.min().y(),z=(long)box.max().z()-box.min().z();int axis=x>=y&&x>=z?0:y>=z?1:2;
        values.sort(Comparator.comparingLong(p->center(p.bounds(),axis)));int mid=values.size()/2;return new Node(box,tree(new ArrayList<>(values.subList(0,mid))),tree(new ArrayList<>(values.subList(mid,values.size()))),null);
    }
    private static long center(PlacementBounds b,int axis){return switch(axis){case 0->(long)b.min().x()+b.max().x();case 1->(long)b.min().y()+b.max().y();default->(long)b.min().z()+b.max().z();};}
    private static void collect(Node node,PlacementBounds bounds,List<Part> output){if(node==null||!intersects(node.bounds(),bounds))return;if(node.part()!=null)output.add(node.part());else{collect(node.a(),bounds,output);collect(node.b(),bounds,output);}}
}
