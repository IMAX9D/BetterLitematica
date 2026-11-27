package dev.betterlitematica.core;
import java.io.*;
import java.util.*;

public final class PackedSection {
    public static final int VOLUME=4096;
    private final int[] palette;
    private final PackedBits indices;

    private final long[] occupied;
    private PackedSection(int[] palette,PackedBits indices,long[] occupied){this.palette=palette;this.indices=indices;this.occupied=occupied;}
    public static int index(int x,int y,int z) {
        if((x|y|z)<0||x>15||y>15||z>15)throw new IndexOutOfBoundsException(); return x+(z<<4)+(y<<8);
    }
    public static PackedSection fromGlobalIds(int[] blocks) {
        if(blocks.length!=VOLUME)throw new IllegalArgumentException("Expected 4096 cells");
        LinkedHashMap<Integer,Integer> map=new LinkedHashMap<>(); int[] ids=new int[VOLUME];long[] occupied=new long[VOLUME/64];
        for(int i=0;i<VOLUME;i++) {
            if(blocks[i]<0)throw new IllegalArgumentException("Negative palette id");
            if(blocks[i]!=0)occupied[i>>>6]|=1L<<(i&63);
            Integer id=map.get(blocks[i]); if(id==null){id=map.size();map.put(blocks[i],id);} ids[i]=id;
        }
        int[] palette=map.keySet().stream().mapToInt(Integer::intValue).toArray();
        int bits=Math.max(1,32-Integer.numberOfLeadingZeros(palette.length-1));
        return new PackedSection(palette,PackedBits.pack(bits,ids),occupied);
    }

    public static final class Encoder {
        private final int[] localCodes;
        private final int[] palette=new int[VOLUME];
        private final int[] indices=new int[VOLUME];

        public Encoder(int assetPaletteSize){
            if(assetPaletteSize<1||assetPaletteSize>65536)throw new IllegalArgumentException("Asset palette size");
            localCodes=new int[assetPaletteSize];
        }
        public PackedSection encode(int[] blocks){
            if(blocks.length!=VOLUME)throw new IllegalArgumentException("Expected 4096 cells");
            int size=0;
            long[] occupied=new long[VOLUME/64];
            try{
                for(int i=0;i<VOLUME;i++){
                    int global=blocks[i];
                    int code=localCodes[global];
                    if(code==0){
                        palette[size++]=global;
                        localCodes[global]=code=size;
                    }
                    indices[i]=code-1;
                    if(global!=0)occupied[i>>>6]|=1L<<(i&63);
                }
                int bits=Math.max(1,32-Integer.numberOfLeadingZeros(size-1));
                return new PackedSection(Arrays.copyOf(palette,size),PackedBits.pack(bits,indices),occupied);
            }finally{
                for(int i=0;i<size;i++)localCodes[palette[i]]=0;
            }
        }
    }
    public int globalId(int index){return palette[indices.get(index)];}
    public int globalId(int x,int y,int z){return globalId(index(x,y,z));}

    public int nextNonZero(int from){
        if(from<0||from>VOLUME)throw new IndexOutOfBoundsException();
        if(from==VOLUME)return VOLUME;
        int word=from>>>6;long bits=occupied[word]&(-1L<<(from&63));
        while(bits==0){if(++word==occupied.length)return VOLUME;bits=occupied[word];}
        return (word<<6)+Long.numberOfTrailingZeros(bits);
    }
    public boolean isCanonicalAir(){return palette.length==1&&palette[0]==0;}

    public int paletteSize(){return palette.length;}
    public int paletteId(int index){return palette[index];}
    public long estimatedBytes(){return 104L+4L*palette.length+indices.estimatedBytes()+16L+8L*occupied.length;}
    public void write(DataOutput out)throws IOException{
        out.writeInt(palette.length);for(int v:palette)out.writeInt(v);out.writeByte(indices.bits());
        long[] words=indices.copyWords();out.writeInt(words.length);for(long v:words)out.writeLong(v);
    }
    public static PackedSection read(DataInput in,int assetPaletteSize)throws IOException{
        int n=in.readInt();if(n<1||n>VOLUME)throw new IOException("Invalid section palette length");
        int[] palette=new int[n];Set<Integer> seen=new HashSet<>();
        for(int i=0;i<n;i++){int v=in.readInt();if(v<0||v>=assetPaletteSize||!seen.add(v))throw new IOException("Invalid section palette entry");palette[i]=v;}
        int bits=in.readUnsignedByte();if(bits!=Math.max(1,32-Integer.numberOfLeadingZeros(n-1)))throw new IOException("Invalid section bit width");
        int count=in.readInt();if(count!=PackedBits.wordCount(bits,VOLUME))throw new IOException("Invalid section words");
        long[] words=new long[count];for(int i=0;i<count;i++)words[i]=in.readLong();
        PackedBits packed=PackedBits.takeOwnership(bits,VOLUME,words);
        long[] occupied=new long[VOLUME/64];
        if(n==1){
            if(packed.nextNonZero(0,VOLUME)!=VOLUME)throw new IOException("Section index outside palette");
            if(palette[0]!=0)Arrays.fill(occupied,-1L);
        }else if(n==2){
            for(int w=0;w<occupied.length;w++)occupied[w]=palette[0]==0?words[w]:palette[1]==0?~words[w]:-1L;
        }else{
            for(int i=0;i<VOLUME;){
                int local=packed.get(i);
                if(local>=n)throw new IOException("Section index outside palette");
                if(local==0&&palette[0]==0){
                    i=packed.nextNonZero(i+1,VOLUME);
                    continue;
                }
                if(palette[local]!=0)occupied[i>>>6]|=1L<<(i&63);
                i++;
            }
        }
        return new PackedSection(palette,packed,occupied);
    }
}
