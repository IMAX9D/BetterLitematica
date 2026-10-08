package dev.betterlitematica.fabric;
import java.util.*;
import net.minecraft.nbt.*;

/** Capture identity follows UUID, so moving entities and detached passengers cannot be copied twice. */
final class CapturedEntities {
    private final Set<UUID> seen=new HashSet<>();
    boolean accept(CompoundTag root){var added=new HashSet<UUID>();if(!visit(root,added,0))return false;seen.addAll(added);return true;}
    int size(){return seen.size();}
    boolean contains(UUID id){return seen.contains(id);}
    private boolean visit(CompoundTag tag,Set<UUID> added,int depth){
        if(depth>32||!tag.read("UUID",net.minecraft.core.UUIDUtil.CODEC).isPresent())throw new IllegalStateException("实体身份或乘客结构无效");var id=tag.read("UUID",net.minecraft.core.UUIDUtil.CODEC).orElseThrow();if(seen.contains(id))return false;if(!added.add(id))throw new IllegalStateException("实体身份重复");if(seen.size()+added.size()>8192)throw new IllegalStateException("实体超过捕获预算");
        var passengers=NbtAccess.list(tag,"Passengers",Tag.TAG_COMPOUND);for(int i=passengers.size()-1;i>=0;i--)if(!visit(passengers.getCompoundOrEmpty(i),added,depth+1))passengers.remove(i);return true;
    }
}
