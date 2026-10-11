package dev.betterlitematica.core;

import java.util.*;

/**
 * Silhouette of highlighted cells: only edges where the union of same-group cells actually bends. A flat run of
 * missing blocks becomes its outline instead of a lattice of per-box frames. Collinear edges are merged into
 * segments, and each segment remembers which side is empty so it can be nudged off the block faces.
 * Pure data, safe to compute on a worker.
 */
public final class HighlightOutline {
    private HighlightOutline() {}
    /**
     * A line from (x,y,z) to the same point plus {@code length} along {@code axis} (0 x, 1 y, 2 z). {@code du}/{@code dv}
     * (-1, 0, 1) point toward empty space along the next two axes in cyclic order (axis+1, axis+2).
     */
    public record Segment(int axis,int x,int y,int z,int length,int du,int dv,int group) {}

    /** @return the merged silhouette, or {@code null} when the boxes cover more than {@code maxCells} cells. */
    public static List<Segment> of(Collection<HighlightCuboids.Box> boxes,int maxCells){
        var groups=new TreeMap<Integer,List<HighlightCuboids.Box>>();long total=0;
        for(var box:boxes){
            total+=(long)(box.max().x()-box.min().x()+1)*(box.max().y()-box.min().y()+1)*(box.max().z()-box.min().z()+1);
            if(total>maxCells)return null;
            groups.computeIfAbsent(box.group(),g->new ArrayList<>()).add(box);
        }
        var result=new ArrayList<Segment>();
        for(var entry:groups.entrySet())outline(entry.getValue(),entry.getKey(),result);
        return result;
    }

    private static void outline(List<HighlightCuboids.Box> boxes,int group,List<Segment> result){
        int count=0;for(var b:boxes)count+=(b.max().x()-b.min().x()+1)*(b.max().y()-b.min().y()+1)*(b.max().z()-b.min().z()+1);
        var cells=new CellSet(count);
        for(var b:boxes)for(int x=b.min().x();x<=b.max().x();x++)for(int y=b.min().y();y<=b.max().y();y++)for(int z=b.min().z();z<=b.max().z();z++)cells.add(x,y,z);
        // Unit crease edges as (line, position along it); each edge is emitted once, by the first filled cell around it.
        var lines=new LineIds();var edges=new long[256];int edgeCount=0;
        int[] p=new int[3];boolean[] filled=new boolean[4];
        for(int i=0;i<cells.size;i++){
            long cell=cells.at(i);int cx=x(cell),cy=y(cell),cz=z(cell);
            for(int axis=0;axis<3;axis++){int u=(axis+1)%3,v=(axis+2)%3;
                for(int a=0;a<2;a++)for(int b=0;b<2;b++){
                    p[0]=cx;p[1]=cy;p[2]=cz;p[u]+=a;p[v]+=b;
                    int filledCount=0,first=-1;
                    for(int q=0;q<4;q++){int ou=1-(q&1),ov=1-(q>>1);int x=p[0]-(u==0?ou:v==0?ov:0),y=p[1]-(u==1?ou:v==1?ov:0),z=p[2]-(u==2?ou:v==2?ov:0);
                        filled[q]=cells.contains(x,y,z);if(filled[q]){filledCount++;if(first<0)first=q;}}
                    // This cell sits in quadrant (a==0 ? +u : -u, b==0 ? +v : -v).
                    if(first!=((a==0?1:0)|((b==0?1:0)<<1)))continue;
                    int du,dv;
                    if(filledCount==1||filledCount==3){int lone=-1;for(int q=0;q<4;q++)if(filled[q]==(filledCount==1)){lone=q;break;}
                        int su=(lone&1)==0?-1:1,sv=(lone>>1)==0?-1:1;
                        // One filled: push away from it. Three filled: push into the one empty quadrant.
                        du=filledCount==1?-su:su;dv=filledCount==1?-sv:sv;
                    }else if(filledCount==2&&(filled[0]&&filled[3]||filled[1]&&filled[2])){du=0;dv=0;}
                    else continue;
                    int id=lines.id(lineKey(axis,p[u],p[v],du,dv));
                    if(edgeCount==edges.length)edges=Arrays.copyOf(edges,edgeCount*2);
                    edges[edgeCount++]=((long)id<<32)|((p[axis]+0x80000000L)&0xffffffffL);
                }
            }
        }
        Arrays.sort(edges,0,edgeCount);
        for(int i=0;i<edgeCount;){
            int id=(int)(edges[i]>>>32),start=along(edges[i]),end=start;int j=i+1;
            while(j<edgeCount&&(int)(edges[j]>>>32)==id&&along(edges[j])==end+1){end++;j++;}
            long line=lines.key(id);int axis=(int)(line>>>60),u=(axis+1)%3,v=(axis+2)%3;
            int[] q=new int[3];q[axis]=start;q[u]=(int)(line<<4>>38);q[v]=(int)(line<<30>>38);
            result.add(new Segment(axis,q[0],q[1],q[2],end-start+1,(int)((line>>>2)&3)-1,(int)(line&3)-1,group));
            i=j;
        }
    }
    private static int along(long edge){return (int)((edge&0xffffffffL)-0x80000000L);}
    /** axis (4 bits) · u coordinate (26) · v coordinate (26) · du+1 (2) · dv+1 (2) — y fits easily in 26 bits. */
    private static long lineKey(int axis,int u,int v,int du,int dv){return ((long)axis<<60)|((long)(u&0x3ffffff)<<34)|((long)(v&0x3ffffff)<<8)|((long)(du+1)<<2)|(dv+1);}

    /** Dense ids for line keys, open-addressed. */
    private static final class LineIds {
        private long[] table=new long[1024],keys=new long[256];private int[] ids=new int[1024];private int size;
        LineIds(){Arrays.fill(table,Long.MIN_VALUE);}
        int id(long key){
            if(size*2>=table.length)grow();int mask=table.length-1,i=slot(key,mask);
            while(table[i]!=Long.MIN_VALUE){if(table[i]==key)return ids[i];i=(i+1)&mask;}
            table[i]=key;ids[i]=size;if(size==keys.length)keys=Arrays.copyOf(keys,size*2);keys[size]=key;return size++;
        }
        long key(int id){return keys[id];}
        private void grow(){var oldTable=table;var oldIds=ids;table=new long[oldTable.length*2];ids=new int[table.length];Arrays.fill(table,Long.MIN_VALUE);int mask=table.length-1;
            for(int j=0;j<oldTable.length;j++)if(oldTable[j]!=Long.MIN_VALUE){int i=slot(oldTable[j],mask);while(table[i]!=Long.MIN_VALUE)i=(i+1)&mask;table[i]=oldTable[j];ids[i]=oldIds[j];}}
        private static int slot(long k,int mask){k^=k>>>33;k*=0xc4ceb9fe1a85ec53L;k^=k>>>33;return (int)k&mask;}
    }
    /** Open-addressed set of packed cells; no boxing on the hot path. */
    private static final class CellSet {
        private final long[] table;private final long[] order;private final int mask;int size;
        private static final long EMPTY=Long.MIN_VALUE;
        CellSet(int expected){int capacity=Integer.highestOneBit(Math.max(16,expected*2)-1)<<1;table=new long[capacity];Arrays.fill(table,EMPTY);mask=capacity-1;order=new long[Math.max(1,expected)];}
        void add(int x,int y,int z){long k=key(x,y,z);int i=slot(k);while(table[i]!=EMPTY){if(table[i]==k)return;i=(i+1)&mask;}table[i]=k;order[size++]=k;}
        boolean contains(int x,int y,int z){long k=key(x,y,z);int i=slot(k);while(table[i]!=EMPTY){if(table[i]==k)return true;i=(i+1)&mask;}return false;}
        long at(int i){return order[i];}
        private int slot(long k){k^=k>>>33;k*=0xff51afd7ed558ccdL;k^=k>>>33;return (int)k&mask;}
    }
    private static long key(int x,int y,int z){return ((long)(x&0x3ffffff)<<38)|((long)(z&0x3ffffff)<<12)|(y&0xfff);}
    private static int x(long key){return (int)(key>>38);}
    private static int z(long key){return (int)(key<<26>>38);}
    private static int y(long key){return (int)(key<<52>>52);}
}
