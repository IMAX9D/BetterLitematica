package dev.betterlitematica.core;

import java.util.*;

/**
 * Immutable spatial index for highlight cuboids. Boxes are bucketed by the 16³ cell of their minimum corner
 * and stored bucket-contiguously as flat int arrays, so a frame visits only nearby, visible buckets and never
 * allocates or sorts the whole ledger. A bucket's bounds are the union of its boxes, so bucket tests are exact
 * supersets of their members.
 */
public final class HighlightBoxIndex {
    /** Coordinates are world-space with exclusive maxima (a cell at x spans [x, x+1)). */
    public interface BoundsTest {
        /** {@link #OUTSIDE}, {@link #INTERSECTS} or {@link #INSIDE}; called once per candidate bucket. */
        int test(double minX,double minY,double minZ,double maxX,double maxY,double maxZ);
        /** Per-box visibility inside a partially visible bucket; implementations may make this cheaper than {@link #test}. */
        default boolean intersects(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){return test(minX,minY,minZ,maxX,maxY,maxZ)!=OUTSIDE;}
    }
    public interface Visitor { void visit(int slot,double distanceSquared); }
    public static final int OUTSIDE=0,INTERSECTS=1,INSIDE=2;
    public static final HighlightBoxIndex EMPTY=build(List.of());
    private final HighlightCuboids.Box[] boxes;
    private final int[] coords,source,start,bounds;
    private double[] bucketKey=new double[0],mergeKey=new double[0];private int[] bucketOrder=new int[0],mergeOrder=new int[0];
    private long[] heap=new long[0];private double[] heapDistance=new double[0];

    private HighlightBoxIndex(HighlightCuboids.Box[] boxes,int[] coords,int[] source,int[] start,int[] bounds){this.boxes=boxes;this.coords=coords;this.source=source;this.start=start;this.bounds=bounds;}

    /** Two-pass counting sort: O(n) time, no boxing, one primitive hash table sized to the bucket count. */
    public static HighlightBoxIndex build(Collection<HighlightCuboids.Box> input){
        int n=input.size();var source=input.toArray(new HighlightCuboids.Box[0]);
        int capacity=Integer.highestOneBit(Math.max(4,n*2-1))<<1;long[] keys=new long[capacity];int[] ids=new int[capacity];Arrays.fill(ids,-1);
        int[] bucketOf=new int[n];int buckets=0;int[] counts=new int[Math.max(1,Math.min(n,1024))];
        for(int i=0;i<n;i++){
            var min=source[i].min();long key=cell(min.x()>>4,min.y()>>4,min.z()>>4);int slot=(int)mix(key)&(capacity-1);
            while(ids[slot]>=0&&keys[slot]!=key)slot=(slot+1)&(capacity-1);
            if(ids[slot]<0){keys[slot]=key;ids[slot]=buckets++;if(buckets>counts.length)counts=Arrays.copyOf(counts,counts.length*2);}
            bucketOf[i]=ids[slot];counts[bucketOf[i]]++;
        }
        int[] start=new int[buckets+1];for(int b=0;b<buckets;b++)start[b+1]=start[b]+counts[b];
        int[] fill=Arrays.copyOf(start,buckets);var boxes=new HighlightCuboids.Box[n];int[] coords=new int[n*6],order=new int[n];
        int[] bounds=new int[buckets*6];for(int b=0;b<buckets;b++){Arrays.fill(bounds,b*6,b*6+3,Integer.MAX_VALUE);Arrays.fill(bounds,b*6+3,b*6+6,Integer.MIN_VALUE);}
        for(int i=0;i<n;i++){
            var box=source[i];int b=bucketOf[i],p=fill[b]++;boxes[p]=box;order[p]=i;
            int x0=Math.min(box.min().x(),box.max().x()),y0=Math.min(box.min().y(),box.max().y()),z0=Math.min(box.min().z(),box.max().z());
            int x1=Math.max(box.min().x(),box.max().x()),y1=Math.max(box.min().y(),box.max().y()),z1=Math.max(box.min().z(),box.max().z());
            int c=p*6;coords[c]=x0;coords[c+1]=y0;coords[c+2]=z0;coords[c+3]=x1;coords[c+4]=y1;coords[c+5]=z1;
            int o=b*6;bounds[o]=Math.min(bounds[o],x0);bounds[o+1]=Math.min(bounds[o+1],y0);bounds[o+2]=Math.min(bounds[o+2],z0);
            bounds[o+3]=Math.max(bounds[o+3],x1);bounds[o+4]=Math.max(bounds[o+4],y1);bounds[o+5]=Math.max(bounds[o+5],z1);
        }
        return new HighlightBoxIndex(boxes,coords,order,start,bounds);
    }
    public int size(){return boxes.length;}
    public int buckets(){return start.length-1;}
    public HighlightCuboids.Box box(int slot){return boxes[slot];}
    /** Position of the slot's box in the list the index was built from. */
    public int sourceIndex(int slot){return source[slot];}
    public int minX(int slot){return coords[slot*6];}public int minY(int slot){return coords[slot*6+1];}public int minZ(int slot){return coords[slot*6+2];}
    /** Inclusive cell maxima; draw extents are these plus one. */
    public int maxX(int slot){return coords[slot*6+3];}public int maxY(int slot){return coords[slot*6+4];}public int maxZ(int slot){return coords[slot*6+5];}
    public long bytes(){return 64L+boxes.length*(8L+28L)+start.length*4L+bounds.length*4L;}

    /** Squared distance from a point to a box of whole cells, the same metric the overlays sort by. */
    public static double distanceSquared(double px,double py,double pz,int x0,int y0,int z0,int x1,int y1,int z1){
        double x=Math.max(x0-px,Math.max(0,px-x1-1)),y=Math.max(y0-py,Math.max(0,py-y1-1)),z=Math.max(z0-pz,Math.max(0,pz-z1-1));return x*x+y*y+z*z;
    }
    private double slotDistance(int slot,double px,double py,double pz){int c=slot*6;return distanceSquared(px,py,pz,coords[c],coords[c+1],coords[c+2],coords[c+3],coords[c+4],coords[c+5]);}
    private double bucketDistance(int b,double px,double py,double pz){int o=b*6;return distanceSquared(px,py,pz,bounds[o],bounds[o+1],bounds[o+2],bounds[o+3],bounds[o+4],bounds[o+5]);}
    private int bucketTest(int b,BoundsTest test){int o=b*6;return test.test(bounds[o],bounds[o+1],bounds[o+2],bounds[o+3]+1.0,bounds[o+4]+1.0,bounds[o+5]+1.0);}
    private boolean slotVisible(int slot,BoundsTest test){int c=slot*6;return test.intersects(coords[c],coords[c+1],coords[c+2],coords[c+3]+1.0,coords[c+4]+1.0,coords[c+5]+1.0);}

    /**
     * Visits every box within range whose bounds pass {@code test}, bucket by bucket from near to far. Buckets
     * outside range or view are skipped whole; buckets entirely inside the view skip per-box tests.
     * Not thread-safe: reuses scratch arrays.
     */
    public void forEach(double px,double py,double pz,double maxDistanceSquared,BoundsTest test,Visitor visitor){
        int candidates=candidateBuckets(px,py,pz,maxDistanceSquared);
        for(int i=0;i<candidates;i++){
            int b=bucketOrder[i],view=bucketTest(b,test);if(view==OUTSIDE)continue;
            for(int s=start[b],end=start[b+1];s<end;s++){double d=slotDistance(s,px,py,pz);if(d<=maxDistanceSquared&&(view==INSIDE||slotVisible(s,test)))visitor.visit(s,d);}
        }
    }

    /**
     * The {@code limit} nearest visible boxes within range, nearest first, ties in source-list order: exactly the
     * prefix a stable distance sort of the whole list followed by the same filters would produce. Buckets are visited by their lower-bound distance and the search stops as soon as
     * no unvisited bucket can beat the current {@code limit}-th candidate. Not thread-safe: reuses scratch arrays.
     * @return number of slots written to {@code out}
     */
    public int nearest(double px,double py,double pz,double maxDistanceSquared,int limit,BoundsTest test,int[] out){
        if(limit<=0||boxes.length==0)return 0;limit=Math.min(limit,Math.min(out.length,boxes.length));
        int candidates=candidateBuckets(px,py,pz,maxDistanceSquared);
        if(heap.length<limit){heap=new long[limit];heapDistance=new double[limit];}
        int size=0;
        for(int i=0;i<candidates;i++){
            double lower=bucketKey[i];if(size==limit&&lower>heapDistance[0])break;
            int b=bucketOrder[i],view=-1;
            for(int s=start[b],end=start[b+1];s<end;s++){
                double d=slotDistance(s,px,py,pz);if(d>maxDistanceSquared||size==limit&&!before(d,source[s],heapDistance[0],source[(int)heap[0]]))continue;
                if(view<0){view=bucketTest(b,test);if(view==OUTSIDE)break;}
                if(view!=INSIDE&&!slotVisible(s,test))continue;
                if(size<limit){heap[size]=s;heapDistance[size]=d;siftUp(size++);}else{heap[0]=s;heapDistance[0]=d;siftDown(0,size);}
            }
        }
        // Heap sort in place: repeatedly move the farthest to the end, leaving nearest first.
        for(int end=size-1;end>0;end--){swap(0,end);siftDown(0,end);}
        for(int i=0;i<size;i++)out[i]=(int)heap[i];
        return size;
    }
    /** Buckets within range, ordered by their lower-bound distance, in {@code bucketKey}/{@code bucketOrder}. */
    private int candidateBuckets(double px,double py,double pz,double maxDistanceSquared){
        int buckets=buckets(),candidates=0;
        if(bucketKey.length<buckets){bucketKey=new double[buckets];bucketOrder=new int[buckets];}
        for(int b=0;b<buckets;b++){double d=bucketDistance(b,px,py,pz);if(d<=maxDistanceSquared){bucketKey[candidates]=d;bucketOrder[candidates++]=b;}}
        sortBuckets(candidates);return candidates;
    }
    /** Total order (distance, source index); the heap root is the greatest, i.e. the first to be displaced. */
    private static boolean before(double d,int s,double otherD,int otherS){return d<otherD||d==otherD&&s<otherS;}
    private boolean less(int a,int b){return before(heapDistance[a],source[(int)heap[a]],heapDistance[b],source[(int)heap[b]]);}
    private void siftUp(int i){while(i>0){int parent=(i-1)>>1;if(!less(parent,i))break;swap(i,parent);i=parent;}}
    private void siftDown(int i,int size){
        while(true){int l=i*2+1,r=l+1,largest=i;
            if(l<size&&less(largest,l))largest=l;
            if(r<size&&less(largest,r))largest=r;
            if(largest==i)return;swap(i,largest);i=largest;}
    }
    private void swap(int a,int b){long h=heap[a];heap[a]=heap[b];heap[b]=h;double d=heapDistance[a];heapDistance[a]=heapDistance[b];heapDistance[b]=d;}
    /** Primitive merge sort of candidate buckets by lower bound; buckets are few compared to boxes. Reuses scratch. */
    private void sortBuckets(int n){
        if(n<2)return;if(mergeKey.length<n){mergeKey=new double[n];mergeOrder=new int[n];}
        sortRange(0,n,mergeKey,mergeOrder);
    }
    private void sortRange(int from,int to,double[] tmpKey,int[] tmpOrder){
        if(to-from<=24){for(int i=from+1;i<to;i++){double k=bucketKey[i];int v=bucketOrder[i];int j=i-1;while(j>=from&&bucketKey[j]>k){bucketKey[j+1]=bucketKey[j];bucketOrder[j+1]=bucketOrder[j];j--;}bucketKey[j+1]=k;bucketOrder[j+1]=v;}return;}
        int mid=(from+to)>>>1;sortRange(from,mid,tmpKey,tmpOrder);sortRange(mid,to,tmpKey,tmpOrder);
        if(bucketKey[mid-1]<=bucketKey[mid])return;
        int i=from,j=mid,k=0;while(i<mid&&j<to){if(bucketKey[j]<bucketKey[i]){tmpKey[k]=bucketKey[j];tmpOrder[k++]=bucketOrder[j++];}else{tmpKey[k]=bucketKey[i];tmpOrder[k++]=bucketOrder[i++];}}
        while(i<mid){tmpKey[k]=bucketKey[i];tmpOrder[k++]=bucketOrder[i++];}while(j<to){tmpKey[k]=bucketKey[j];tmpOrder[k++]=bucketOrder[j++];}
        System.arraycopy(tmpKey,0,bucketKey,from,k);System.arraycopy(tmpOrder,0,bucketOrder,from,k);
    }
    private static long cell(int x,int y,int z){return ((long)(x&0x3fffff)<<42)|((long)(y&0xfffff)<<22)|(z&0x3fffffL);}
    private static long mix(long v){v^=v>>>33;v*=0xff51afd7ed558ccdL;v^=v>>>33;v*=0xc4ceb9fe1a85ec53L;return v^(v>>>33);}
}
