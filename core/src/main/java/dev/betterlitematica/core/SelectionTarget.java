package dev.betterlitematica.core;

/** Analytic selection picking; work is bounded by the 128-box selection limit. */
public record SelectionTarget(Part part,String name,double distance) {
    public enum Part {BOX,FIRST,SECOND,ORIGIN}
    public static SelectionTarget ray(AreaSelection selection,double x,double y,double z,double dx,double dy,double dz,double reach){
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);if(length<1e-9||!Double.isFinite(length)||reach<=0||reach>512)return null;dx/=length;dy/=length;dz/=length;
        SelectionTarget best=null;for(var box:selection.boxes()){
            var region=box.region();double hit=intersect(x,y,z,dx,dy,dz,region.min().x(),region.min().y(),region.min().z(),(double)region.min().x()+region.size().x(),(double)region.min().y()+region.size().y(),(double)region.min().z()+region.size().z());
            if(hit>=0&&hit<=reach&&(best==null||hit<best.distance()))best=new SelectionTarget(Part.BOX,box.name(),hit);
            Vec3i[] corners={box.first(),box.second()};for(int i=0;i<2;i++){var p=corners[i];hit=intersect(x,y,z,dx,dy,dz,p.x()-0.1,p.y()-0.1,p.z()-0.1,p.x()+1.1,p.y()+1.1,p.z()+1.1);if(hit>=0&&hit<=reach&&(best==null||hit<best.distance()))best=new SelectionTarget(i==0?Part.FIRST:Part.SECOND,box.name(),hit);}
        }
        if(!selection.boxes().isEmpty()){var p=selection.origin();double hit=intersect(x,y,z,dx,dy,dz,p.x()-0.2,p.y()-0.2,p.z()-0.2,p.x()+0.2,p.y()+0.2,p.z()+0.2);if(hit>=0&&hit<=reach&&(best==null||hit<best.distance()))best=new SelectionTarget(Part.ORIGIN,"",hit);}
        return best;
    }
    private static double intersect(double x,double y,double z,double dx,double dy,double dz,double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        double lo=Double.NEGATIVE_INFINITY,hi=Double.POSITIVE_INFINITY;double[] origin={x,y,z},direction={dx,dy,dz},min={minX,minY,minZ},max={maxX,maxY,maxZ};for(int a=0;a<3;a++){if(Math.abs(direction[a])<1e-12){if(origin[a]<min[a]||origin[a]>max[a])return -1;}else{double first=(min[a]-origin[a])/direction[a],second=(max[a]-origin[a])/direction[a];lo=Math.max(lo,Math.min(first,second));hi=Math.min(hi,Math.max(first,second));if(lo>hi)return -1;}}double hit=lo>=0?lo:hi;return hit>=0&&Double.isFinite(hit)?hit:-1;
    }
    public AreaSelection translate(AreaSelection selection,Vec3i offset){if(part==Part.ORIGIN)return selection.origin(selection.origin().add(offset));var selected=selection.select(name);var box=selected.current();var moved=switch(part){case FIRST->new SelectionBox(name,box.first().add(offset),box.second());case SECOND->new SelectionBox(name,box.first(),box.second().add(offset));default->box.translate(offset);};moved.region();return selected.put(moved);}
}
