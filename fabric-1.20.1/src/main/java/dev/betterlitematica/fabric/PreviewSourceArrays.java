package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Cancellation;
import dev.betterlitematica.io.*;
import java.io.*;
import java.util.*;

/** Frozen edit exports may stream packed words. Materialize them once for preview and file output. */
final class PreviewSourceArrays {
    private PreviewSourceArrays(){}
    static void materialize(Map<String,Object> root,Cancellation cancel)throws IOException{
        var regions=NbtReader.compound(root.get("Regions"),"Regions");boolean changed=false;long bytes=0;
        for(var entry:regions.entrySet()){
            cancel.check();var tag=NbtReader.compound(entry.getValue(),"Region");Object value=tag.get("BlockStates");
            long length=value instanceof long[] array?array.length:value instanceof NbtWriter.StreamLongs stream?stream.length():0;
            if(length<0||(bytes+=length*8)>SchematicImporter.SOURCE_LIMITS.maxAllocatedBytes())throw new IOException("投影方块数据超过预览读取上限");
            if(value instanceof NbtWriter.StreamLongs stream){
                var sink=new LongSink(new long[stream.length()]);stream.write(new DataOutputStream(sink),cancel);
                if(sink.count!=sink.words.length||sink.used!=0)throw new IOException("投影方块数据不完整");
                tag.put("BlockStates",sink.words);entry.setValue(tag);changed=true;
            }
        }
        cancel.check();if(changed)root.put("Regions",regions);
    }
    private static final class LongSink extends OutputStream {
        private final long[] words;private int count,used;private long pending;
        LongSink(long[] words){this.words=words;}
        @Override public void write(int value)throws IOException{
            if(count>=words.length)throw new IOException("投影方块数据长度不符");pending=(pending<<8)|(value&255L);
            if(++used==8){words[count++]=pending;pending=0;used=0;}
        }
        @Override public void write(byte[] b,int off,int len)throws IOException{
            if(len==8&&used==0){
                if(count>=words.length)throw new IOException("投影方块数据长度不符");
                words[count++]=((long)b[off]<<56)|((b[off+1]&255L)<<48)|((b[off+2]&255L)<<40)|((b[off+3]&255L)<<32)|((b[off+4]&255L)<<24)|((b[off+5]&255L)<<16)|((b[off+6]&255L)<<8)|(b[off+7]&255L);
            }else for(int i=off;i<off+len;i++)write(b[i]);
        }
    }
}
