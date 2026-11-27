package dev.betterlitematica.fabric;

import java.util.*;

final class PrinterInteractionTracker<K> {
    private static final int MAX_MATERIALS=128;
    private static final long LIFETIME=5_000_000_000L;
    private final Map<K,Flight<K>> flights=new LinkedHashMap<>();
    private final Set<K> suspects=new HashSet<>(),blocked=new HashSet<>();
    private Flight<K> probe;
    private long nextExpiry=Long.MAX_VALUE;
    private int overflowSequence=-1;
    private static final class Flight<K> {final K item;long pos,until;Object block;int sequence;Flight(K item){this.item=item;}}
    record Menu<K>(boolean owned,K blockedItem,boolean diagnosing){}
    boolean blocked(K item){return blocked.contains(item);}
    boolean waiting(long now){expire(now);return probe!=null;}
    boolean allowed(K item,long now){expire(now);return !blocked(item)&&probe==null&&(!suspects.contains(item)||flights.isEmpty());}
    boolean tracking(){return !flights.isEmpty()||overflowSequence>=0;}
    int trackedMaterials(){return flights.size();}
    void sent(K item,long pos,Object block,int sequence,long now){
        expire(now);
        if(overflowSequence>=0){overflowSequence=sequence;return;}
        boolean diagnosing=suspects.contains(item)&&flights.isEmpty();
        var f=flights.get(item);
        if(f==null){
            if(flights.size()>=MAX_MATERIALS){forget();return;}
            f=new Flight<>(item);flights.put(item,f);
        }
        f.pos=pos;f.block=block;f.sequence=sequence;f.until=now+LIFETIME;nextExpiry=Math.min(nextExpiry,f.until);
        if(diagnosing)probe=f;
    }
    void acknowledged(int sequence,long now){
        expire(now);if(overflowSequence>=0){if(sequence>=overflowSequence)overflowSequence=-1;return;}
        for(var it=flights.values().iterator();it.hasNext();){var f=it.next();if(f.sequence>sequence)continue;
            if(probe==f){suspects.remove(f.item);probe=null;}it.remove();
        }
        if(flights.isEmpty())nextExpiry=Long.MAX_VALUE;
    }
    void confirmed(long pos,Object block,long now){
        expire(now);if(suspects.isEmpty())return;
        for(var it=flights.values().iterator();it.hasNext();){var f=it.next();if(f.pos==pos&&f.block==block){suspects.remove(f.item);if(probe==f)probe=null;it.remove();}}
    }
    void confirmedLegacy(long pos,Object block,long now){
        expire(now);
        for(var it=flights.values().iterator();it.hasNext();){
            var flight=it.next();
            if(flight.pos==pos&&flight.block==block){if(probe==flight)probe=null;suspects.remove(flight.item);it.remove();}
        }
        if(flights.isEmpty())nextExpiry=Long.MAX_VALUE;
    }
    Menu<K> opened(long now){
        expire(now);if(flights.isEmpty()||overflowSequence>=0)return new Menu<>(false,null,false);
        K item=probe!=null?probe.item:flights.size()==1?flights.keySet().iterator().next():null;
        if(item!=null){boolean added=blocked.add(item);suspects.remove(item);probe=null;return new Menu<>(true,added?item:null,false);}
        boolean changed=false;for(K key:flights.keySet())if(!blocked.contains(key))changed|=suspects.add(key);
        return new Menu<>(true,null,changed);
    }
    void forget(){flights.clear();probe=null;nextExpiry=Long.MAX_VALUE;overflowSequence=-1;}
    void clear(){forget();suspects.clear();blocked.clear();}
    private void expire(long now){
        if(now<nextExpiry)return;nextExpiry=Long.MAX_VALUE;
        for(var it=flights.values().iterator();it.hasNext();){var f=it.next();if(now>=f.until){if(probe==f)probe=null;it.remove();}else nextExpiry=Math.min(nextExpiry,f.until);}
    }
}
