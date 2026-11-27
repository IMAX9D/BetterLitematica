package dev.betterlitematica.fabric;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

final class PrinterRetryWindow {
 private final Long2ObjectLinkedOpenHashMap<Entry> entries=new Long2ObjectLinkedOpenHashMap<>();
 private static final class Entry {int expected,observed,reason,count;long generation,tick;}
 int failed(long position,int expected,int observed,long generation,int reason,long tick){
  var e=entries.get(position);
  if(e==null){if(entries.size()>=8192)entries.removeFirst();e=new Entry();entries.put(position,e);}
  boolean same=e.count>0&&e.expected==expected&&e.observed==observed&&e.generation==generation&&e.reason==reason&&tick-e.tick<=200;
  e.count=same?Math.min(6,e.count+1):1;e.expected=expected;e.observed=observed;e.generation=generation;e.reason=reason;e.tick=tick;
  return Math.min(40,1<<e.count);
 }
 boolean forget(long position){return entries.remove(position)!=null;}
 boolean empty(){return entries.isEmpty();}
 int size(){return entries.size();}
 void clear(){entries.clear();}
}
