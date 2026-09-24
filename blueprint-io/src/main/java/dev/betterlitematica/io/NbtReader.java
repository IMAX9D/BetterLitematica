package dev.betterlitematica.io;

import dev.betterlitematica.core.Cancellation;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Independent, bounded Java-edition NBT reader. No Minecraft NBT classes. */
public final class NbtReader {
    public record Limits(long maxUncompressedBytes,long maxAllocatedBytes,int maxDepth,int maxNodes) {
        public static final Limits DEFAULT=new Limits(256L<<20,384L<<20,64,1_000_000);
        public Limits { if(maxUncompressedBytes<1||maxAllocatedBytes<1||maxDepth<1||maxNodes<1)throw new IllegalArgumentException(); }
    }
    private final Limits limits; private final Cancellation cancel;
    private long allocated; private int nodes;
    private final java.util.function.BiPredicate<List<String>,Integer> omitted;private final ArrayList<String> path=new ArrayList<>();private byte[] skipBuffer;
    private NbtReader(Limits limits,Cancellation cancel){this(limits,cancel,(p,t)->false);}
    private NbtReader(Limits limits,Cancellation cancel,java.util.function.BiPredicate<List<String>,Integer> omitted){this.limits=limits;this.cancel=cancel;this.omitted=omitted;}
    public static Map<String,Object> read(Path path,Limits limits,Cancellation cancel)throws IOException {
        return readPartial(path,limits,cancel,p->false);
    }
    /** Omitted branches still consume byte, depth and node budgets; their large arrays are never allocated. */
    public static Map<String,Object> readPartial(Path path,Limits limits,Cancellation cancel,java.util.function.Predicate<List<String>> omitted)throws IOException {return readPartial(path,limits,cancel,(p,t)->omitted.test(p));}
    public static Map<String,Object> readPartial(Path path,Limits limits,Cancellation cancel,java.util.function.BiPredicate<List<String>,Integer> omitted)throws IOException {
        try(InputStream file=new BufferedInputStream(Files.newInputStream(path),64*1024)){
            file.mark(2);int a=file.read(),b=file.read();file.reset();
            InputStream decoded=(a==0x1f&&b==0x8b)?new GZIPInputStream(file,64*1024):file;
            return new NbtReader(limits,cancel,omitted).root(decoded);
        }
    }
    private Map<String,Object> root(InputStream input)throws IOException {
        try(DataInputStream in=new DataInputStream(new LimitedInput(new BufferedInputStream(input,64*1024),limits.maxUncompressedBytes,cancel))){
            if(in.readUnsignedByte()!=10)throw new IOException("NBT root must be a compound");
            in.readUTF(); Map<String,Object> result=compound(in,0);
            if(in.read()!=-1)throw new IOException("Trailing NBT payload"); return result;
        }
    }
    private void allocate(long bytes)throws IOException {
        allocated=Math.addExact(allocated,bytes);
        if(allocated>limits.maxAllocatedBytes)throw new IOException("NBT allocation budget exceeded");
    }
    private int length(DataInputStream in,int bytesPerElement)throws IOException {
        int n=in.readInt();if(n<0)throw new IOException("Negative NBT array/list length");
        allocate(32L+(long)n*bytesPerElement);return n;
    }
    private Object payload(DataInputStream in,int type,int depth)throws IOException {
        cancel.check();if(depth>limits.maxDepth||++nodes>limits.maxNodes)throw new IOException("NBT nesting/node limit exceeded");
        return switch(type){
            case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt();case 4 -> in.readLong();
            case 5 -> in.readFloat();case 6 -> in.readDouble();
            case 7 -> {byte[] v=new byte[length(in,1)];in.readFully(v);yield v;}
            case 8 -> {String v=in.readUTF();allocate(48L+v.length()*2L);yield v;}
            case 9 -> {
                int elementType=in.readUnsignedByte(),n=length(in,8);
                if(n>limits.maxNodes-nodes)throw new IOException("NBT list node limit exceeded");
                if(elementType>12 || (elementType==0&&n!=0))throw new IOException("Invalid NBT list type");
                List<Object> values=new ArrayList<>(n);for(int i=0;i<n;i++)values.add(payload(in,elementType,depth+1));yield values;
            }
            case 10 -> compound(in,depth+1);
            case 11 -> {int[] v=new int[length(in,4)];for(int i=0;i<v.length;i++){if((i&8191)==0)cancel.check();v[i]=in.readInt();}yield v;}
            case 12 -> {long[] v=new long[length(in,8)];for(int i=0;i<v.length;i++){if((i&8191)==0)cancel.check();v[i]=in.readLong();}yield v;}
            default -> throw new IOException("Unsupported NBT tag type: "+type);
        };
    }
    private Map<String,Object> compound(DataInputStream in,int depth)throws IOException {
        if(depth>limits.maxDepth)throw new IOException("NBT nesting limit exceeded");
        allocate(64);Map<String,Object> map=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        for(;;){
            cancel.check();int type=in.readUnsignedByte();if(type==0)return map;
            String key=in.readUTF();allocate(80L+key.length()*2L);
            if(!seen.add(key))throw new IOException("Duplicate NBT key: "+key);
            path.add(key);try{if(omitted.test(path,type))skip(in,type,depth);else map.put(key,payload(in,type,depth));}finally{path.remove(path.size()-1);}
        }
    }
    private void skip(DataInputStream in,int type,int depth)throws IOException{
        cancel.check();if(depth>limits.maxDepth||++nodes>limits.maxNodes)throw new IOException("NBT nesting/node limit exceeded");
        switch(type){
            case 1->discard(in,1);case 2->discard(in,2);case 3,5->discard(in,4);case 4,6->discard(in,8);case 8->discard(in,in.readUnsignedShort());
            case 7,11,12->{int n=in.readInt();if(n<0)throw new IOException("Negative NBT array length");discard(in,(long)n*(type==7?1:type==11?4:8));}
            case 9->{int kind=in.readUnsignedByte(),n=in.readInt();if(n<0||n>limits.maxNodes-nodes||kind>12||kind==0&&n!=0)throw new IOException("Invalid NBT list");for(int i=0;i<n;i++)skip(in,kind,depth+1);}
            case 10->{for(;;){int kind=in.readUnsignedByte();if(kind==0)break;discard(in,in.readUnsignedShort());skip(in,kind,depth+1);}}
            default->throw new IOException("Unsupported NBT tag type: "+type);
        }
    }
    private void discard(DataInputStream in,long bytes)throws IOException{if(bytes>limits.maxUncompressedBytes)throw new IOException("NBT omitted branch exceeds byte limit");if(skipBuffer==null)skipBuffer=new byte[65536];while(bytes>0){cancel.check();int n=(int)Math.min(bytes,skipBuffer.length);in.readFully(skipBuffer,0,n);bytes-=n;}}
    private static final class LimitedInput extends FilterInputStream {
        private long count;private final long limit;private final Cancellation cancel;
        LimitedInput(InputStream in,long limit,Cancellation cancel){super(in);this.limit=limit;this.cancel=cancel;}
        private void add(long n)throws IOException{cancel.check();if(n>0&&(count+=n)>limit)throw new IOException("NBT decompression limit exceeded");}
        @Override public int read()throws IOException{int b=in.read();add(b<0?0:1);return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);add(n);return n;}
    }
    public static Map<String,Object> compound(Object value,String field)throws IOException {
        if(!(value instanceof Map<?,?> raw))throw new IOException("Expected compound: "+field);
        Map<String,Object> result=new LinkedHashMap<>();
        for(var e:raw.entrySet()){if(!(e.getKey() instanceof String key))throw new IOException("Invalid compound key");result.put(key,e.getValue());}
        return result;
    }
    public static int integer(Map<String,Object> tag,String key)throws IOException {
        Object v=tag.get(key);if(!(v instanceof Number n)||v instanceof Float||v instanceof Double)throw new IOException("Expected integer: "+key);
        long x=n.longValue();if(x<Integer.MIN_VALUE||x>Integer.MAX_VALUE)throw new IOException("Integer overflow: "+key);return (int)x;
    }
    public static String string(Map<String,Object> tag,String key,String fallback)throws IOException{
        Object v=tag.get(key);if(v==null)return fallback;if(!(v instanceof String s))throw new IOException("Expected string: "+key);return s;
    }
}
