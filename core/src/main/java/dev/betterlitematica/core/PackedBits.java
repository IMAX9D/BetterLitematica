package dev.betterlitematica.core;
import java.util.Objects;

public final class PackedBits {
    private final int bits, size;
    private final long[] words;
    private final long mask;
    public PackedBits(int bits, int size, long[] words) { this(bits,size,words,true); }

    public static PackedBits takeOwnership(int bits,int size,long[] words) { return new PackedBits(bits,size,words,false); }
    private PackedBits(int bits,int size,long[] words,boolean copy) {
        if(bits<1||bits>31||size<0) throw new IllegalArgumentException("Invalid packed size/bits");
        if(words.length != wordCount(bits,size)) throw new IllegalArgumentException("Packed word count mismatch");
        this.bits=bits; this.size=size; this.words=copy?words.clone():words; this.mask=(1L<<bits)-1;
    }
    public static int wordCount(int bits,int size) {
        if(bits<1||bits>31||size<0) throw new IllegalArgumentException();
        return Math.toIntExact(((long)bits*size+63)/64);
    }
    public static PackedBits pack(int bits,int[] values) {
        Objects.requireNonNull(values); long[] words=new long[wordCount(bits,values.length)]; long mask=(1L<<bits)-1;
        for(int i=0;i<values.length;i++) {
            long value=values[i]; if(value<0 || value>mask) throw new IllegalArgumentException("Value outside bit width");
            long bit=(long)i*bits; int word=(int)(bit>>>6), shift=(int)(bit&63);
            words[word]|=value<<shift;
            if(shift+bits>64) words[word+1]|=value>>>(64-shift);
        }
        return new PackedBits(bits,values.length,words,false);
    }
    public static PackedBits generate(int bits,int size,java.util.function.IntUnaryOperator source) {
        long[] words=new long[wordCount(bits,size)];long mask=(1L<<bits)-1;
        for(int i=0;i<size;i++) {
            long value=source.applyAsInt(i);if(value<0||value>mask)throw new IllegalArgumentException("Value outside bit width");
            long bit=(long)i*bits;int word=(int)(bit>>>6),shift=(int)(bit&63);
            words[word]|=value<<shift;if(shift+bits>64)words[word+1]|=value>>>(64-shift);
        }
        return new PackedBits(bits,size,words,false);
    }
    public int get(int index) {
        Objects.checkIndex(index,size); long bit=(long)index*bits; int w=(int)(bit>>>6),s=(int)(bit&63);
        long value=words[w]>>>s;
        if(s+bits>64) value|=words[w+1]<<(64-s);
        return (int)(value&mask);
    }

    public int nextNonZero(int from,int end) {
        Objects.checkFromToIndex(from,end,size);
        if(from==end)return end;
        long startBit=(long)from*bits, endBit=(long)end*bits;
        int word=(int)(startBit>>>6), last=(int)((endBit-1)>>>6);
        long value=words[word]&(-1L<<(int)(startBit&63));
        while(value==0 && word<last)value=words[++word];
        if(value==0)return end;
        long bit=((long)word<<6)+Long.numberOfTrailingZeros(value);
        return bit<endBit?(int)(bit/bits):end;
    }
    public int bits(){return bits;} public int size(){return size;}
    public long[] copyWords(){return words.clone();}
    public long estimatedBytes(){return 48L+words.length*8L;}
}
