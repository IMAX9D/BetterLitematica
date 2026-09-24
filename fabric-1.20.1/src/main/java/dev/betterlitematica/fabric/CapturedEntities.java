package dev.betterlitematica.fabric;
import java.util.*;
import net.minecraft.nbt.*;

/** Capture identity follows UUID, so moving entities and detached passengers cannot be copied twice. */
final class CapturedEntities {
    private final Set<UUID> seen=new HashSet<>();
    boolean accept(NbtCompound root){var added=new HashSet<UUID>();if(!visit(root,added,0))return false;seen.addAll(added);return true;}
    int size(){return seen.size();}
    boolean contains(UUID id){return seen.contains(id);}
    private boolean visit(NbtCompound tag,Set<UUID> added,int depth){
        if(depth>32||!tag.containsUuid("UUID"))throw new IllegalStateException("实体身份或乘客结构无效");var id=tag.getUuid("UUID");if(seen.contains(id))return false;if(!added.add(id))throw new IllegalStateException("实体身份重复");if(seen.size()+added.size()>8192)throw new IllegalStateException("实体超过捕获预算");
        var passengers=tag.getList("Passengers",NbtElement.COMPOUND_TYPE);for(int i=passengers.size()-1;i>=0;i--)if(!visit(passengers.getCompound(i),added,depth+1))passengers.remove(i);return true;
    }
}
