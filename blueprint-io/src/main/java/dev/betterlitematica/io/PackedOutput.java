package dev.betterlitematica.io;
import dev.betterlitematica.core.PackedBits;
import java.io.IOException;
/** Mutable local construction buffer; only its finished NBT owner receives the array. */
final class PackedOutput {
    final long[] words;private final int bits;
    PackedOutput(int bits,int size,long inputBytes)throws IOException{long count=PackedBits.wordCount(bits,size);if(count*8>512L<<20||inputBytes+count*8>1024L<<20)throw new IOException("格式转换超过内存预算");this.bits=bits;words=new long[(int)count];}
    void put(int index,int value){long bit=(long)index*bits;int word=(int)(bit>>>6),shift=(int)(bit&63);words[word]|=(long)value<<shift;if(shift+bits>64)words[word+1]|=(long)value>>>(64-shift);}
}
