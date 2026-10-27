package com.retrievepanel.printer;
import java.util.*;

/** Constant-time use tracking; no inventory scan in the placement loop. */
public final class MaterialQueue<K> {
    private final LinkedHashMap<K,Long> recency=new LinkedHashMap<>();
    private K last;private long clock;
    public void touch(K key){if(key==null||key==last)return;last=key;recency.remove(key);recency.put(key,++clock);if(recency.size()>256)recency.remove(recency.keySet().iterator().next());}
    public long usedAt(K key){return recency.getOrDefault(key,0L);}
    public void clear(){recency.clear();last=null;clock=0;}
    public static <K> LinkedHashMap<K,Integer> window(K urgent,Map<K,Integer> queued,Map<K,Integer> nearby,int kinds,int fallback){
        LinkedHashMap<K,Integer> result=new LinkedHashMap<>();
        if(urgent!=null)result.put(urgent,Math.max(1,queued.getOrDefault(urgent,nearby.getOrDefault(urgent,fallback))));
        for(var e:queued.entrySet())if(result.size()<kinds&&e.getValue()>0)result.putIfAbsent(e.getKey(),e.getValue());
        // The real work queue owns order. Nearby-only materials are used only before it is populated.
        if(queued.isEmpty())nearby.entrySet().stream().sorted(Map.Entry.<K,Integer>comparingByValue().reversed()).forEach(e->{if(result.size()<kinds&&e.getValue()>0)result.putIfAbsent(e.getKey(),e.getValue());});
        return result;
    }
}
