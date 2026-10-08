package dev.betterlitematica.fabric;

import net.minecraft.nbt.*;

/** Typed schematic reads reject a list with an incompatible element type. */
final class NbtAccess {
    static final int NUMERIC=99;
    private NbtAccess(){}
    static boolean contains(NbtCompound compound,String key,int type){
        var value=compound.get(key);
        return value!=null&&(type==NUMERIC?value instanceof AbstractNbtNumber:value.getType()==type);
    }
    static NbtList list(NbtCompound compound,String key,int type){
        var list=compound.getListOrEmpty(key);
        for(var value:list)if(value.getType()!=type)return new NbtList();
        return list;
    }
    static int heldType(NbtList list){
        if(list.isEmpty())return NbtElement.END_TYPE;int type=list.get(0).getType();
        for(var value:list)if(value.getType()!=type)return -1;return type;
    }
    static boolean containsUuid(NbtCompound tag,String key){return tag.getIntArray(key).filter(value->value.length==4).isPresent();}
    static java.util.UUID uuid(NbtCompound tag,String key){
        var value=tag.getIntArray(key).filter(array->array.length==4).orElseThrow(()->new IllegalArgumentException("实体身份无效"));
        return new java.util.UUID(((long)value[0]<<32)|(value[1]&0xffffffffL),((long)value[2]<<32)|(value[3]&0xffffffffL));
    }
}
