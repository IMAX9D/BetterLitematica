package dev.betterlitematica.fabric;

import net.minecraft.nbt.*;
import java.util.*;

/** Type-preserving bridge at the version adapter boundary. */
final class NbtBridge {
    private NbtBridge() {}
    static final class Budget {
        private long remaining;private int nodes;
        Budget(long bytes){remaining=bytes;}
        void claim(long bytes,int depth){if(depth>64||++nodes>1_000_000||(remaining-=bytes)<0)throw new IllegalArgumentException("附加 NBT 超过深度、节点或内存预算");}
    }
    static Object plain(NbtElement value) { return plain(value,new Budget(4L*1024*1024),0); }
    private static Object plain(NbtElement value,Budget budget,int depth) {
        long bytes=switch(value.getType()){
            case 7->((NbtByteArray)value).getByteArray().length;
            case 8->(long)value.asString().orElseThrow().length()*2;
            case 11->(long)((NbtIntArray)value).getIntArray().length*4;
            case 12->(long)((NbtLongArray)value).getLongArray().length*8;
            default->16;
        };budget.claim(48+bytes,depth);
        return switch (value.getType()) {
            case 1 -> ((NbtByte)value).byteValue(); case 2 -> ((NbtShort)value).shortValue();
            case 3 -> ((NbtInt)value).intValue(); case 4 -> ((NbtLong)value).longValue();
            case 5 -> ((NbtFloat)value).floatValue(); case 6 -> ((NbtDouble)value).doubleValue();
            case 7 -> ((NbtByteArray)value).getByteArray().clone(); case 8 -> value.asString().orElseThrow();
            case 9 -> {
                var source=(NbtList)value;byte type=0;for(NbtElement entry:source){if(type==0)type=entry.getType();else if(type!=entry.getType()){type=10;break;}}
                List<Object> list=new ArrayList<>();for(NbtElement entry:source){Object element=plain(entry,budget,depth+1);
                    // Match vanilla's standard NBT wire wrapper, including escaping literal {"": ...} compounds.
                    if(type==10&&(!(entry instanceof NbtCompound compound)||compound.getSize()==1&&compound.contains(""))){budget.claim(64,depth+1);element=Map.of("",element);}list.add(element);}
                yield list;
            }
            case 10 -> {Map<String,Object> result=new LinkedHashMap<>();NbtCompound compound=(NbtCompound)value;for(String key:compound.getKeys()){budget.claim(64L+key.length()*2L,depth);result.put(key,plain(compound.get(key),budget,depth+1));}yield result;}
            case 11 -> ((NbtIntArray)value).getIntArray().clone(); case 12 -> ((NbtLongArray)value).getLongArray().clone();
            default -> throw new IllegalArgumentException("Unsupported NBT tag");
        };
    }
    static Map<String,Object> compound(NbtCompound value) {return compound(value,new Budget(4L*1024*1024));}
    @SuppressWarnings("unchecked")
    static Map<String,Object> compound(NbtCompound value,Budget budget) {return (Map<String,Object>)plain(value,budget,0);}
    static NbtElement game(Object value) {return game(value,new Budget(4L*1024*1024),0);}
    private static NbtElement game(Object value,Budget budget,int depth) {
        long bytes=value instanceof byte[] a?a.length:value instanceof int[] a?4L*a.length:value instanceof long[] a?8L*a.length:value instanceof String s?2L*s.length():16;
        budget.claim(48+bytes,depth);
        if(value instanceof Byte n)return NbtByte.of(n); if(value instanceof Short n)return NbtShort.of(n);
        if(value instanceof Integer n)return NbtInt.of(n); if(value instanceof Long n)return NbtLong.of(n);
        if(value instanceof Float n)return NbtFloat.of(n); if(value instanceof Double n)return NbtDouble.of(n);
        if(value instanceof String n)return NbtString.of(n); if(value instanceof byte[] n)return new NbtByteArray(n.clone());
        if(value instanceof int[] n)return new NbtIntArray(n.clone()); if(value instanceof long[] n)return new NbtLongArray(n.clone());
        if(value instanceof List<?> list){NbtList result=new NbtList();for(Object e:list){var element=game(e,budget,depth+1);result.unwrapAndAdd(element);}return result;}
        if(value instanceof Map<?,?> map){NbtCompound result=new NbtCompound();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("Non-string NBT key");budget.claim(64L+key.length()*2L,depth);result.put(key,game(e.getValue(),budget,depth+1));}return result;}
        throw new IllegalArgumentException("Unsupported NBT value");
    }
}
