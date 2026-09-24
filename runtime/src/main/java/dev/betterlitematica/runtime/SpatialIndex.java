package dev.betterlitematica.runtime;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.BlueprintCache;
import java.io.*;
import java.util.*;
/** Built once off-thread, queried in blueprint coordinates. No whole-blueprint scan per frame. */
public final class SpatialIndex {
    private record Bucket(int region,int x,int y,int z){}
    private record Candidate(SectionKey key,double distance){}
    private final Map<Bucket,List<SectionKey>> buckets=new HashMap<>();private final BlueprintMetadata metadata;
    public SpatialIndex(BlueprintCache cache,Cancellation cancel)throws IOException{
        metadata=cache.metadata();for(SectionKey key:cache.index().keySet()){
            cancel.check();Vec3i p=origin(key);
            buckets.computeIfAbsent(new Bucket(key.region(),Math.floorDiv(p.x(),64),Math.floorDiv(p.y(),64),Math.floorDiv(p.z(),64)),k->new ArrayList<>()).add(key);
        }
    }
    private Vec3i origin(SectionKey key){return metadata.regions().get(key.region()).sectionOrigin(key);}
    public List<SectionKey> nearest(Vec3i localCamera,int radius,int limit){return nearest(localCamera,radius,limit,null);}
    public List<SectionKey> nearest(Vec3i camera,int radius,int limit,PlacementLayout layout){
        return nearest(camera,radius,limit,layout,List.of());
    }
    /** additions contain only sections absent from the immutable source index. */
    public List<SectionKey> nearest(Vec3i camera,int radius,int limit,PlacementLayout layout,Collection<SectionKey> additions){
        if(radius<1||radius>512||limit<1||limit>32768)throw new IllegalArgumentException("Query outside supported budget");
        PriorityQueue<Candidate> best=new PriorityQueue<>(Comparator.comparingDouble(Candidate::distance).reversed());
        List<PlacementLayout.Part> parts=layout==null?List.of():layout.overlapping(new PlacementBounds(camera.add(new Vec3i(-radius-16,-radius-16,-radius-16)),camera.add(new Vec3i(radius+16,radius+16,radius+16))));
        if(layout==null)for(int i=0;i<metadata.regions().size();i++)query(i,camera,radius,limit,best);
        else for(var p:parts)query(p.index(),p.local(camera),radius,limit,best);
        for(var key:additions){
            if(layout!=null&&!layout.enabled(key.region()))continue;var local=layout==null?camera:layout.part(key.region()).local(camera);var p=origin(key);double dx=p.x()+8.0-local.x(),dy=p.y()+8.0-local.y(),dz=p.z()+8.0-local.z(),distance=dx*dx+dy*dy+dz*dz;
            if(distance>(radius+14.0)*(radius+14.0))continue;var candidate=new Candidate(key,distance);if(best.size()<limit)best.add(candidate);else if(distance<best.peek().distance()){best.poll();best.add(candidate);}
        }
        List<Candidate> result=new ArrayList<>(best);result.sort(Comparator.comparingDouble(Candidate::distance));return result.stream().map(Candidate::key).toList();
    }
    private void query(int region,Vec3i localCamera,int radius,int limit,PriorityQueue<Candidate> best){
        var r=metadata.regions().get(region);
        int x0=Math.floorDiv(Math.max(r.min().x(),localCamera.x()-radius-16),64),x1=Math.floorDiv(Math.min(r.min().x()+r.size().x()-1,localCamera.x()+radius),64);
        int y0=Math.floorDiv(Math.max(r.min().y(),localCamera.y()-radius-16),64),y1=Math.floorDiv(Math.min(r.min().y()+r.size().y()-1,localCamera.y()+radius),64);
        int z0=Math.floorDiv(Math.max(r.min().z(),localCamera.z()-radius-16),64),z1=Math.floorDiv(Math.min(r.min().z()+r.size().z()-1,localCamera.z()+radius),64);
        double max=(radius+14.0)*(radius+14.0);
        for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            List<SectionKey> list=buckets.get(new Bucket(region,x,y,z));if(list==null)continue;
            for(SectionKey key:list){Vec3i p=origin(key);double dx=p.x()+8.0-localCamera.x(),dy=p.y()+8.0-localCamera.y(),dz=p.z()+8.0-localCamera.z();double d=dx*dx+dy*dy+dz*dz;
                if(d>max)continue;Candidate c=new Candidate(key,d);if(best.size()<limit)best.add(c);else if(d<best.peek().distance){best.poll();best.add(c);}
            }
        }
    }
}
