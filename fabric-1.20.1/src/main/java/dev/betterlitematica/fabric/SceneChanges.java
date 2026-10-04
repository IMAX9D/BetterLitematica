package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Immutable old/new domains; no enumeration of the schematic's block volume. */
final class SceneChanges {
    record Source(Object owner,PlacementLayout layout) {}
    private final List<PlacementLayout> domains;
    private SceneChanges(List<PlacementLayout> domains){this.domains=List.copyOf(domains);}
    static SceneChanges between(List<Source> before,List<Source> after,Object observer){
        var old=new IdentityHashMap<Object,Source>();var next=new IdentityHashMap<Object,Source>();
        var order=new IdentityHashMap<Object,Integer>();int index=0;
        for(var source:before){old.put(source.owner(),source);order.put(source.owner(),index++);}
        for(var source:after)next.put(source.owner(),source);
        int previous=-1;boolean reordered=false;
        for(var source:after){var rank=order.get(source.owner());if(rank!=null){if(rank<previous)reordered=true;previous=rank;}}
        var changed=new ArrayList<PlacementLayout>();
        for(var source:before){var replacement=next.get(source.owner());
            if(source.owner()!=observer&&(reordered||replacement==null||!same(source,replacement)))changed.add(source.layout());}
        for(var source:after){var previousSource=old.get(source.owner());
            if(source.owner()!=observer&&(reordered||previousSource==null||!same(source,previousSource)))changed.add(source.layout());}
        return new SceneChanges(changed);
    }
    private static boolean same(Source a,Source b){return a.layout().placement().sameGeometry(b.layout().placement());}
    boolean empty(){return domains.isEmpty();}
    boolean affects(PlacementBounds bounds){for(var domain:domains)if(domain.intersects(bounds))return true;return false;}
}
