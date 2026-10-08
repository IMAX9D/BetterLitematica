package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

/** Revision tests without GPU resources, Minecraft or wall-clock timing. */
public final class MeshRefreshChecks {
    private MeshRefreshChecks(){}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static int run(){
        int checks=0;var changes=new MeshRefresh<String>(2);long original=changes.revision("a");
        check(!changes.stale("a",original),"Unchanged mesh stays current");checks++;
        for(int i=0;i<1000;i++)changes.changed("a");
        check(changes.pending()==1&&changes.stale("a",original),"Repeated updates coalesce without changing resident mesh");checks++;
        long building=changes.revision("a");changes.changed("a");changes.completed("a",building);
        check(changes.stale("a",building)&&changes.pending()==1,"An update during a build survives its completion");checks++;
        long replacement=changes.revision("a");changes.completed("a",replacement);
        check(!changes.stale("a",replacement)&&changes.pending()==0,"Latest complete replacement consumes its marker");checks++;
        changes.changed("a");changes.changed("b");changes.changed("c");
        check(changes.pending()==0&&changes.stale("a",replacement)&&changes.stale("unlisted",0),"Overflow becomes a bounded global invalidation");checks++;
        long afterOverflow=changes.revision("a");changes.changed("a");changes.completed("a",afterOverflow);
        check(changes.stale("a",afterOverflow),"Local update after global revision is not consumed by an older build");checks++;
        long rebuilding=changes.revision("a");changes.allChanged();changes.completed("a",rebuilding);
        check(changes.stale("a",rebuilding)&&changes.pending()==0,"Full invalidation survives an in-flight build");checks++;
        long fresh=changes.revision("a");changes.completed("a",fresh);
        check(!changes.stale("a",fresh)&&changes.stale("b",0),"Refreshing one mesh never refreshes unrelated old meshes");checks++;
        changes.clear();check(changes.revision("a")==0&&changes.pending()==0,"Owner reset clears revision state after dropping resources");checks++;
        var region=new Region("negative anchor",new Vec3i(-32,-16,-48),new Vec3i(48,32,64),new Vec3i(15,15,15));
        for(int turn=0;turn<4;turn++)for(int mirror=0;mirror<4;mirror++){
            var transform=new PlacementTransform(new Vec3i(30,72,-60),turn,(mirror&1)!=0,(mirror&2)!=0);
            var part=new PlacementLayout.Part(0,region,transform,PlacementBounds.clipped(region,transform,LayerRange.ALL));
            for(int offset:new int[]{0,15,16,31,32,47}){
                Vec3i local=region.min().add(new Vec3i(offset,15,31)),world=transform.apply(local);var affected=MeshRefresh.sourceSections(part,MeshRefresh.padded(new PlacementBounds(world,world)));
                check(affected!=null&&PlacementLayout.inside(affected,new Vec3i(offset>>4,0,1)),"Changed cell survives inverse rotation/mirror and negative region origin");checks++;
                for(Vec3i neighbor:List.of(local.add(new Vec3i(1,0,0)),local.add(new Vec3i(-1,0,0)),local.add(new Vec3i(0,1,0)),local.add(new Vec3i(0,0,1)))){
                    var relative=neighbor.subtract(region.min());if(relative.x()<0||relative.x()>=region.size().x())continue;
                    check(PlacementLayout.inside(affected,new Vec3i(relative.x()>>4,relative.y()>>4,relative.z()>>4)),"Neighbor section shares refresh across transformed boundary");checks++;
                }
            }
            var far=transform.apply(region.min().add(new Vec3i(-100,-100,-100)));
            check(MeshRefresh.sourceSections(part,new PlacementBounds(far,far))==null,"Outside events do not enumerate source sections");checks++;
        }
        return checks;
    }
    public static void main(String[] args){System.out.println("PASS MeshRefreshChecks: "+run()+" checks");}
}
