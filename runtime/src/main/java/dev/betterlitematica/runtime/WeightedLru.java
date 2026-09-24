package dev.betterlitematica.runtime;
import java.util.*;
import java.util.function.*;
/** Single-owner cache with an explicit logical-byte budget. Eviction also owns resource release. */
public final class WeightedLru<K,V> implements AutoCloseable {
    private final long capacity;private final int maxEntries;private long used;
    private final LinkedHashMap<K,V> map=new LinkedHashMap<>(16,0.75f,true);
    private final ToLongFunction<V> weight;private final Consumer<V> release;
    public WeightedLru(long capacity,ToLongFunction<V> weight,Consumer<V> release){this(capacity,Integer.MAX_VALUE,weight,release);}
    public WeightedLru(long capacity,int maxEntries,ToLongFunction<V> weight,Consumer<V> release){if(capacity<1||maxEntries<1)throw new IllegalArgumentException();this.capacity=capacity;this.maxEntries=maxEntries;this.weight=weight;this.release=release;}
    public V get(K key){return map.get(key);}
    /** Values must not mutate cache membership during this visit. */
    public void forEach(BiConsumer<K,V> consumer){map.forEach(consumer);}
    public boolean contains(K key){return map.containsKey(key);}
    public boolean canPutProtecting(long size,Set<K> protectedKeys){
        if(size<=0||size>capacity)return false;
        long free=capacity-used;int slots=maxEntries-map.size();
        if(size<=free&&slots>0)return true;
        for(var entry:map.entrySet())if(!protectedKeys.contains(entry.getKey())){free+=weight.applyAsLong(entry.getValue());slots++;if(size<=free&&slots>0)return true;}
        return false;
    }
    public boolean canPutWithoutEviction(long size){return size>0&&size<=capacity-used&&map.size()<maxEntries;}
    /** A replacement reuses its entry slot; reserve its complete size before releasing the old value. */
    public boolean canReplaceProtecting(K key,long size,Set<K> protectedKeys){
        var previous=map.get(key);if(previous==null)return canPutProtecting(size,protectedKeys);
        if(size<=0||size>capacity)return false;long free=capacity-used+weight.applyAsLong(previous);
        if(size<=free)return true;
        for(var entry:map.entrySet())if(!Objects.equals(entry.getKey(),key)&&!protectedKeys.contains(entry.getKey())){free+=weight.applyAsLong(entry.getValue());if(size<=free)return true;}
        return false;
    }
    public boolean canReplaceWithoutEviction(K key,long size){
        var previous=map.get(key);return previous==null?canPutWithoutEviction(size):size>0&&size<=capacity-used+weight.applyAsLong(previous);
    }
    /** Admission without evicting another visible mesh. Caller owns rejected values. */
    public boolean tryPutWithoutEviction(K key,V value){
        long size=weight.applyAsLong(value);if(size<=0)throw new IllegalArgumentException("Cache weight must be positive");
        if(map.containsKey(key)||size>capacity-used||map.size()>=maxEntries)return false;
        map.put(key,value);used+=size;return true;
    }
    /** Evict only cold/unprotected entries under actual pressure. Rejected values remain caller-owned. */
    public boolean tryPutProtecting(K key,V value,Set<K> protectedKeys){
        long size=weight.applyAsLong(value);if(size<=0)throw new IllegalArgumentException("Cache weight must be positive");
        if(map.containsKey(key)||size>capacity)return false;
        if(size<=capacity-used&&map.size()<maxEntries){map.put(key,value);used+=size;return true;}
        long reclaim=0;int slots=0;
        for(var entry:map.entrySet())if(!protectedKeys.contains(entry.getKey())){reclaim+=weight.applyAsLong(entry.getValue());slots++;}
        if(size>capacity-used+reclaim||map.size()-slots>=maxEntries)return false;
        var it=map.entrySet().iterator();
        while((size>capacity-used||map.size()>=maxEntries)&&it.hasNext()){
            var entry=it.next();if(protectedKeys.contains(entry.getKey()))continue;
            it.remove();used-=weight.applyAsLong(entry.getValue());release.accept(entry.getValue());
        }
        map.put(key,value);used+=size;return true;
    }
    public void retainKeys(Set<K> keys){var it=map.entrySet().iterator();while(it.hasNext()){var entry=it.next();if(!keys.contains(entry.getKey())){it.remove();used-=weight.applyAsLong(entry.getValue());release.accept(entry.getValue());}}}
    public boolean put(K key,V value){
        long size=weight.applyAsLong(value);if(size<=0)throw new IllegalArgumentException("Cache weight must be positive");
        if(size>capacity){release.accept(value);return false;}
        V previous=map.remove(key);if(previous!=null){used-=weight.applyAsLong(previous);release.accept(previous);}
        while(used>capacity-size||map.size()>=maxEntries){var it=map.entrySet().iterator();var old=it.next();it.remove();used-=weight.applyAsLong(old.getValue());release.accept(old.getValue());}
        map.put(key,value);used+=size;return true;
    }
    public V remove(K key){V value=map.remove(key);if(value!=null){used-=weight.applyAsLong(value);release.accept(value);}return value;}
    public long usedBytes(){return used;}public int size(){return map.size();}public long capacity(){return capacity;}
    @Override public void close(){var values=new ArrayList<>(map.values());map.clear();used=0;for(V value:values)release.accept(value);}
}
