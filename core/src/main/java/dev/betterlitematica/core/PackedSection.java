package dev.betterlitematica.core;
import java.io.*;
import java.util.*;
/** Immutable 16^3 section with a LOCAL palette referencing the asset palette. */
public final class PackedSection {
    public static final int VOLUME=4096;
    private final int[] palette;
    private final PackedBits indices;
    // Derived on construction/decode, never serialized. Asset palette zero is canonical air.
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
    public int globalId(int index){return palette[indices.get(index)];}
    public int globalId(int x,int y,int z){return globalId(index(x,y,z));}
    /** First nonzero asset ID at/after from, or VOLUME. Other air variants still need a palette check. */
    public int nextNonZero(int from){
        if(from<0||from>VOLUME)throw new IndexOutOfBoundsException();
        if(from==VOLUME)return VOLUME;
        int word=from>>>6;long bits=occupied[word]&(-1L<<(from&63));
        while(bits==0){if(++word==occupied.length)return VOLUME;bits=occupied[word];}
        return (word<<6)+Long.numberOfTrailingZeros(bits);
    }
    public boolean isCanonicalAir(){return palette.length==1&&palette[0]==0;}
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
        PackedBits packed=new PackedBits(bits,VOLUME,words);
        long[] occupied=new long[VOLUME/64];
        for(int i=0;i<VOLUME;i++){
            int local=packed.get(i);if(local>=n)throw new IOException("Section index outside palette");
            if(palette[local]!=0)occupied[i>>>6]|=1L<<(i&63);
        }
        return new PackedSection(palette,packed,occupied);
    }
}
