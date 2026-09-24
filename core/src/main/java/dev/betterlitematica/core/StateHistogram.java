package dev.betterlitematica.core;

/** Sparse complete-source counts for one region, independent of render residency and the world. */
public final class StateHistogram {
    private final int[] ids;private final long[] counts;
    public StateHistogram(int[] ids,long[] counts){if(ids.length!=counts.length)throw new IllegalArgumentException();this.ids=ids.clone();this.counts=counts.clone();int previous=-1;for(int i=0;i<ids.length;i++){if(ids[i]<=previous||counts[i]<1)throw new IllegalArgumentException("Invalid histogram");previous=ids[i];}}
    public static StateHistogram from(long[] values){int n=0;for(long value:values)if(value>0)n++;int[] ids=new int[n];long[] counts=new long[n];int next=0;for(int i=0;i<values.length;i++)if(values[i]>0){ids[next]=i;counts[next++]=values[i];}return new StateHistogram(ids,counts);}
    public int size(){return ids.length;}public int id(int i){return ids[i];}public long count(int i){return counts[i];}
    public long total(){long n=0;for(long c:counts)n=Math.addExact(n,c);return n;}
    public void addTo(long[] totals){for(int i=0;i<ids.length;i++)totals[ids[i]]=Math.addExact(totals[ids[i]],counts[i]);}
}
