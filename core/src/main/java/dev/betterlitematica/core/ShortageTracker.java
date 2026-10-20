package dev.betterlitematica.core;

import java.util.*;
import java.util.function.Predicate;

/** Records actual supply failures; stock recovery never waits for a spatial scan. */
public final class ShortageTracker<T> {
    private final LinkedHashMap<T,Integer> entries=new LinkedHashMap<>();
    private final int limit;
    public ShortageTracker(int limit){if(limit<1)throw new IllegalArgumentException();this.limit=limit;}
    public void failed(T item){Objects.requireNonNull(item);if(entries.size()==limit&&!entries.containsKey(item))entries.remove(entries.keySet().iterator().next());entries.put(item,0);}
    public void refresh(Predicate<T> available,Predicate<T> needed,boolean completeSurvey){
        for(var it=entries.entrySet().iterator();it.hasNext();){var entry=it.next();boolean recovered=available.test(entry.getKey())||completeSurvey&&!needed.test(entry.getKey());int age=recovered?entry.getValue()+1:0;if(age>=2)it.remove();else entry.setValue(age);}
    }
    public List<T> items(){return List.copyOf(entries.keySet());}
    public boolean isEmpty(){return entries.isEmpty();}
    public void clear(){entries.clear();}
}
