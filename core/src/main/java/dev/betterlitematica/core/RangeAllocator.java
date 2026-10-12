package dev.betterlitematica.core;

import java.util.Arrays;

/** First-fit ranges inside one fixed span; freed neighbours coalesce. Single owner, no native resources. */
public final class RangeAllocator {
    private final int capacity;
    // Free ranges sorted by start and never adjacent: adjacent ranges are always merged.
    private int[] starts=new int[8],lengths=new int[8];
    private int count,free;
    public RangeAllocator(int capacity){
        if(capacity<1)throw new IllegalArgumentException("Positive capacity required");
        this.capacity=capacity;starts[0]=0;lengths[0]=capacity;count=1;free=capacity;
    }
    /** Start of a new range of {@code size} units, or -1 when no free range is large enough. */
    public int allocate(int size){
        if(size<1)throw new IllegalArgumentException("Positive size required");
        for(int i=0;i<count;i++)if(lengths[i]>=size){
            int at=starts[i];starts[i]+=size;lengths[i]-=size;if(lengths[i]==0)remove(i);free-=size;return at;
        }
        return -1;
    }
    public void release(int start,int size){
        if(size<1||start<0||(long)start+size>capacity)throw new IllegalArgumentException("Range outside allocator");
        int at=Arrays.binarySearch(starts,0,count,start);if(at>=0)throw new IllegalStateException("Range already free");
        int next=-at-1,previous=next-1;int end=start+size;
        if(previous>=0&&starts[previous]+lengths[previous]>start||next<count&&end>starts[next])throw new IllegalStateException("Range overlaps free space");
        boolean joinPrevious=previous>=0&&starts[previous]+lengths[previous]==start,joinNext=next<count&&end==starts[next];
        if(joinPrevious&&joinNext){lengths[previous]+=size+lengths[next];remove(next);}
        else if(joinPrevious)lengths[previous]+=size;
        else if(joinNext){starts[next]=start;lengths[next]+=size;}
        else insert(next,start,size);
        free+=size;
    }
    private void remove(int i){System.arraycopy(starts,i+1,starts,i,count-i-1);System.arraycopy(lengths,i+1,lengths,i,count-i-1);count--;}
    private void insert(int i,int start,int size){
        if(count==starts.length){starts=Arrays.copyOf(starts,count*2);lengths=Arrays.copyOf(lengths,count*2);}
        System.arraycopy(starts,i,starts,i+1,count-i);System.arraycopy(lengths,i,lengths,i+1,count-i);starts[i]=start;lengths[i]=size;count++;
    }
    public int capacity(){return capacity;}
    public int free(){return free;}
    public int used(){return capacity-free;}
    public boolean empty(){return free==capacity;}
    public int largestFree(){int best=0;for(int i=0;i<count;i++)best=Math.max(best,lengths[i]);return best;}
    /** Number of separate free ranges; one means no fragmentation. */
    public int fragments(){return count;}
}
