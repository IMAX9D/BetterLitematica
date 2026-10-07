package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

final class SceneChangesChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static PlacementLayout layout(Placement p){return new PlacementLayout(p,List.of(new Region("part",Vec3i.ZERO,new Vec3i(16,16,16))));}
    private static PlacementBounds at(int x,int y,int z){var p=new Vec3i(x,y,z);return new PlacementBounds(p,p);}
    static int run(){
        checks=0;Object a=new Object(),b=new Object(),replacement=new Object();
        var p=new Placement(UUID.randomUUID(),"test","test.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
        var old=new SceneChanges.Source(a,layout(p));var stable=new SceneChanges.Source(b,layout(p.placed(new PlacementTransform(new Vec3i(100,0,0),0,false,false))));
        var before=List.of(old,stable);
        check(SceneChanges.between(before,before,b).empty(),"Unchanged scene preserves all meshes");
        var cosmetic=new SceneChanges.Source(a,layout(p.named("renamed").opacity(.9f).renderBlocks(false)));
        check(SceneChanges.between(before,List.of(cosmetic,stable),b).empty(),"Cosmetic changes do not invalidate geometry");
        for(int turn=0;turn<4;turn++)for(boolean mirror:new boolean[]{false,true}){
            var moved=new SceneChanges.Source(a,layout(p.placed(new PlacementTransform(new Vec3i(32,0,0),turn,mirror,false))));
            var changes=SceneChanges.between(before,List.of(moved,stable),b);
            for(int x=-20;x<125;x++)for(int z=-20;z<35;z++){
                var box=at(x,1,z);boolean expected=!old.layout().overlapping(box).isEmpty()||!moved.layout().overlapping(box).isEmpty();
                check(changes.affects(box)==expected,"Only old/new transformed domains change");
            }
            check(SceneChanges.between(before,List.of(moved,stable),a).empty(),"Moved renderer already resets itself");
        }
        var disabled=new SceneChanges.Source(a,layout(p.enabled(false)));
        check(SceneChanges.between(before,List.of(disabled,stable),b).affects(at(1,1,1)),"Disabling restores previous overlap");
        check(SceneChanges.between(List.of(disabled,stable),before,b).affects(at(1,1,1)),"Enabling covers new overlap");
        check(SceneChanges.between(before,List.of(stable),b).affects(at(1,1,1)),"Unloading restores previous overlap");
        check(!SceneChanges.between(before,List.of(stable),b).affects(at(101,1,1)),"Removing earlier source does not invalidate observer");
        check(SceneChanges.between(before,List.of(new SceneChanges.Source(replacement,layout(p)),stable),b).affects(at(1,1,1)),"Reload with identical placement invalidates old source contents");
        check(SceneChanges.between(before,List.of(stable,old),b).affects(at(1,1,1)),"Order changes re-evaluate overlap priority");
        var nearby=new PlacementBounds(new Vec3i(16,0,0),new Vec3i(31,15,15));
        var removed=SceneChanges.between(before,List.of(stable),b);
        check(!removed.affects(nearby)&&removed.affects(MeshRefresh.padded(nearby)),"One-cell dependency pad catches adjacent faces");
        var filtered=p.displayFilter(new BlockDisplayFilter(BlockDisplayFilter.Mode.BLACKLIST,Set.of("minecraft:stone")));
        var after=List.of(new SceneChanges.Source(a,layout(filtered)),stable);
        var filterChanges=SceneChanges.between(before,after,b);
        check(p.sameGeometry(filtered)&&!filterChanges.empty(),"Display-only filtering invalidates render dependencies without changing construction geometry");
        check(filterChanges.affects(at(1,1,1)),"Overlapping renderer rebuilds the filtered source domain");
        check(!filterChanges.affects(at(101,1,1)),"Distant resident meshes survive filter changes on another placement");
        check(!filterChanges.affects(nearby)&&filterChanges.affects(MeshRefresh.padded(nearby)),"Filtered border blocks invalidate precisely the neighboring face dependency");
        check(SceneChanges.between(before,after,a).empty(),"Edited renderer resets itself instead of double-counting scene invalidation");
        check(SceneChanges.between(after,after,b).empty(),"Unchanged filter snapshots do not repeatedly invalidate render geometry");
        check(SceneChanges.between(after,before,b).affects(at(1,1,1)),"Restoring the previous filter restores overlapping visible ownership");
        return checks;
    }
}
