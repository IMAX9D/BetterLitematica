package dev.betterlitematica.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Explicit type checks preserve the schematic format's typed NBT contracts. */
final class NbtAccess {
    private NbtAccess() {}
    static boolean contains(CompoundTag compound,String key,int type) {
        Tag value=compound.get(key);
        return value!=null&&(type==99?value instanceof NumericTag:value.getId()==type);
    }
    static ListTag list(CompoundTag compound,String key,int type) {
        var list=compound.getListOrEmpty(key);
        for(Tag value:list)if(value.getId()!=type)return new ListTag();
        return list;
    }
}
