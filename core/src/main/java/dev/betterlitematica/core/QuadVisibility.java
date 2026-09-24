package dev.betterlitematica.core;

import java.util.Arrays;
import java.util.Objects;

/** Source-cell ownership and bounded draw ranges, independent of vertex positions. */
public final class QuadVisibility {
    public static final int CELLS=4096,MAX_VERTICES=16384;
    private QuadVisibility(){}
    private static void cell(int value){if(value<0||value>=CELLS)throw new IllegalArgumentException("Source cell out of range");}

    public static final class Mask {
        private final long[] bits=new long[CELLS/Long.SIZE];
        private int count;private long revision;
        public boolean set(int cell,boolean hidden){
            cell(cell);int word=cell>>>6;long bit=1L<<(cell&63);boolean previous=(bits[word]&bit)!=0;
            if(previous==hidden)return false;
            if(hidden){bits[word]|=bit;count++;}else{bits[word]&=~bit;count--;}
            revision++;return true;
        }
        public boolean hidden(int cell){cell(cell);return (bits[cell>>>6]&(1L<<(cell&63)))!=0;}
        public boolean isEmpty(){return count==0;}
        public void clear(){if(count!=0){Arrays.fill(bits,0);count=0;revision++;}}
        public long revision(){return revision;}
    }

    public static final class Builder {
        private final short[] owners=new short[MAX_VERTICES/4];
        private int vertices;
        /** -1 denotes explicitly unowned vertices; a mixed-owner quad also becomes -1. */
        public void append(int cell,int vertexCount){
            if(cell!=-1)cell(cell);
            if(vertexCount<0||vertexCount>MAX_VERTICES-vertices)throw new IllegalArgumentException("Quad ownership vertex budget exceeded");
            int remaining=vertexCount;
            while(remaining>0){int quad=vertices>>>2,offset=vertices&3,n=Math.min(4-offset,remaining);
                if(offset==0)owners[quad]=(short)cell;else if(owners[quad]!=cell)owners[quad]=-1;
                vertices+=n;remaining-=n;
            }
        }
        /** Returns an independent snapshot; callers cannot mutate this builder's state. */
        public short[] build(int expectedVertices){
            if(expectedVertices!=vertices||(vertices&3)!=0)throw new IllegalArgumentException("Quad ownership does not match complete vertices");
            return Arrays.copyOf(owners,vertices/4);
        }
        public void clear(){vertices=0;}
    }

    public static final class Part {
        private final short[] owners;
        private final int[] ranges;
        private Mask cachedMask,cachedTint;private long cachedRevision=Long.MIN_VALUE,cachedTintRevision=Long.MIN_VALUE;private int count,normalCount,tintedCount,offset;
        public Part(short[] ownerCells){
            Objects.requireNonNull(ownerCells);if(ownerCells.length>MAX_VERTICES/4)throw new IllegalArgumentException("Quad ownership budget exceeded");
            for(short owner:ownerCells)if(owner<-1||owner>=CELLS)throw new IllegalArgumentException("Invalid quad owner");
            owners=ownerCells.clone();ranges=new int[owners.length*2];
        }
        public int rangeCount(Mask mask){
            return rangeCount(mask,null,false);
        }
        /** Two disjoint color groups share the existing range storage and never duplicate a quad. */
        public int rangeCount(Mask mask,Mask tint,boolean tinted){
            Objects.requireNonNull(mask);long tintRevision=tint==null?0:tint.revision();
            if(cachedMask!=mask||cachedRevision!=mask.revision()||cachedTint!=tint||cachedTintRevision!=tintRevision){
                count=0;offset=0;
                boolean split=tint!=null&&!tint.isEmpty();
                if(mask.isEmpty()&&!split){if(owners.length>0)range(0,owners.length);}else appendRanges(mask,tint,false);normalCount=count;
                if(split)appendRanges(mask,tint,true);tintedCount=count-normalCount;
                cachedMask=mask;cachedRevision=mask.revision();cachedTint=tint;cachedTintRevision=tintRevision;
            }
            offset=tinted?normalCount:0;count=tinted?tintedCount:normalCount;return count;
        }
        private void appendRanges(Mask mask,Mask tint,boolean tinted){
                int start=-1;
                for(int quad=0;quad<owners.length;quad++){
                    boolean color=owners[quad]>=0&&tint!=null&&tint.hidden(owners[quad]);
                    boolean visible=(owners[quad]<0||!mask.hidden(owners[quad]))&&color==tinted;
                    if(visible){if(start<0)start=quad;}else if(start>=0){range(start,quad-start);start=-1;}
                }
                if(start>=0)range(start,owners.length-start);
        }
        public boolean fullyVisible(Mask mask){return rangeCount(mask)==1&&ranges[0]==0&&ranges[1]==owners.length;}
        private void range(int first,int quads){ranges[count*2]=first;ranges[count*2+1]=quads;count++;}
        private void index(int index){if(index<0||index>=count)throw new IndexOutOfBoundsException(index);}
        public int firstQuad(int index){index(index);return ranges[(offset+index)*2];}
        public int quadCount(int index){index(index);return ranges[(offset+index)*2+1];}
        /** Includes both owned arrays, their headers and conservative object overhead. */
        public long memoryEstimate(){return 128L+owners.length*2L+ranges.length*4L;}
    }
}
