package dev.betterlitematica.core;
import java.util.*;
/** Short-lived interaction feedback, deliberately separate from verification results. */
public final class ActionHighlights {
    public enum Kind {PLACE,ADJUST,BREAK,FAILED}
    public record Mark(long position,Kind kind,long started,long expires){}
    private final LinkedHashMap<Long,Mark> entries=new LinkedHashMap<>();private final int limit;
    public ActionHighlights(int limit){if(limit<1||limit>256)throw new IllegalArgumentException();this.limit=limit;}
    public void add(long position,Kind kind,long now,long duration){if(duration<1||duration>10_000_000_000L)throw new IllegalArgumentException();entries.remove(position);while(entries.size()>=limit)entries.remove(entries.keySet().iterator().next());entries.put(position,new Mark(position,kind,now,now+duration));}
    public Collection<Mark> live(long now){entries.values().removeIf(m->now>=m.expires());return Collections.unmodifiableCollection(entries.values());}
    public void clear(){entries.clear();}
}
