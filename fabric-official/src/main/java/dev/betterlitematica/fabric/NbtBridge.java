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
    static Object plain(Tag value) { return plain(value,new Budget(4L*1024*1024),0); }
    private static Object plain(Tag value,Budget budget,int depth) {
        long bytes=switch(value.getId()){
            case 7->((ByteArrayTag)value).getAsByteArray().length;
            case 8->(long)((StringTag)value).value().length()*2;
            case 11->(long)((IntArrayTag)value).getAsIntArray().length*4;
            case 12->(long)((LongArrayTag)value).getAsLongArray().length*8;
            default->16;
        };budget.claim(48+bytes,depth);
        return switch (value.getId()) {
            case 1 -> ((ByteTag)value).byteValue(); case 2 -> ((ShortTag)value).shortValue();
            case 3 -> ((IntTag)value).intValue(); case 4 -> ((LongTag)value).longValue();
            case 5 -> ((FloatTag)value).floatValue(); case 6 -> ((DoubleTag)value).doubleValue();
            case 7 -> ((ByteArrayTag)value).getAsByteArray().clone(); case 8 -> ((StringTag)value).value();
            case 9 -> {
                var source=(ListTag)value;byte type=0;for(Tag entry:source){if(type==0)type=entry.getId();else if(type!=entry.getId()){type=10;break;}}
                List<Object> list=new ArrayList<>();for(Tag entry:source){Object element=plain(entry,budget,depth+1);
                    // Match vanilla's standard NBT wire wrapper, including escaping literal {"": ...} compounds.
                    if(type==10&&(!(entry instanceof CompoundTag compound)||compound.size()==1&&compound.contains(""))){budget.claim(64,depth+1);element=Map.of("",element);}list.add(element);}
                yield list;
            }
            case 10 -> {Map<String,Object> result=new LinkedHashMap<>();CompoundTag compound=(CompoundTag)value;for(String key:compound.keySet()){budget.claim(64L+key.length()*2L,depth);result.put(key,plain(compound.get(key),budget,depth+1));}yield result;}
            case 11 -> ((IntArrayTag)value).getAsIntArray().clone(); case 12 -> ((LongArrayTag)value).getAsLongArray().clone();
            default -> throw new IllegalArgumentException("Unsupported NBT tag");
        };
    }
    static Map<String,Object> compound(CompoundTag value) {return compound(value,new Budget(4L*1024*1024));}
    @SuppressWarnings("unchecked")
    static Map<String,Object> compound(CompoundTag value,Budget budget) {return (Map<String,Object>)plain(value,budget,0);}
    static Tag game(Object value) {return game(value,new Budget(4L*1024*1024),0);}
    private static Tag game(Object value,Budget budget,int depth) {
        long bytes=value instanceof byte[] a?a.length:value instanceof int[] a?4L*a.length:value instanceof long[] a?8L*a.length:value instanceof String s?2L*s.length():16;
        budget.claim(48+bytes,depth);
        if(value instanceof Byte n)return ByteTag.valueOf(n); if(value instanceof Short n)return ShortTag.valueOf(n);
        if(value instanceof Integer n)return IntTag.valueOf(n); if(value instanceof Long n)return LongTag.valueOf(n);
        if(value instanceof Float n)return FloatTag.valueOf(n); if(value instanceof Double n)return DoubleTag.valueOf(n);
        if(value instanceof String n)return StringTag.valueOf(n); if(value instanceof byte[] n)return new ByteArrayTag(n.clone());
        if(value instanceof int[] n)return new IntArrayTag(n.clone()); if(value instanceof long[] n)return new LongArrayTag(n.clone());
        if(value instanceof List<?> list){ListTag result=new ListTag();for(Object e:list){var element=game(e,budget,depth+1);result.addAndUnwrap(element);}return result;}
        if(value instanceof Map<?,?> map){CompoundTag result=new CompoundTag();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("Non-string NBT key");budget.claim(64L+key.length()*2L,depth);result.put(key,game(e.getValue(),budget,depth+1));}return result;}
        throw new IllegalArgumentException("Unsupported NBT value");
    }
}
