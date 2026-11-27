package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

public final class PrinterMaterialIndex implements AutoCloseable {
    private static final int MAGIC=0x424d4931,VERSION=1,HEADER=16;
    public record Entry(long offset,int bytes){}
    public record Group(int state,int[] cells){}
    public record Slice(int state,long offset,int count){}
    private final RandomAccessFile file;
    private final Map<SectionKey,Entry> directory;
    private final boolean hit;
    private PrinterMaterialIndex(RandomAccessFile file,Map<SectionKey,Entry> directory,boolean hit){this.file=file;this.directory=Collections.unmodifiableMap(directory);this.hit=hit;}
    public boolean cacheHit(){return hit;}
    public boolean contains(SectionKey key){return directory.containsKey(key);}
    public Set<SectionKey> sections(){return directory.keySet();}
    private static final Comparator<SectionKey> ORDER=Comparator.comparingInt(SectionKey::region).thenComparingInt(SectionKey::y).thenComparingInt(SectionKey::z).thenComparingInt(SectionKey::x);
    public static String fingerprint(BlueprintMetadata metadata,SchematicEdits.Snapshot edits)throws IOException{
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
                out.writeInt(VERSION);out.writeUTF(metadata.sourceSha256());out.writeInt(metadata.palette().size());
                for(var state:metadata.palette())out.writeUTF(state.toString());
                out.writeInt(metadata.regions().size());for(var region:metadata.regions())out.writeUTF(region.toString());
                if(edits!=null){var keys=new ArrayList<>(edits.sections().keySet());keys.sort(ORDER);
                    for(var key:keys){writeKey(out,key);var cells=new TreeMap<>(edits.sections().get(key));out.writeInt(cells.size());for(var cell:cells.entrySet()){out.writeInt(cell.getKey());out.writeInt(cell.getValue().state());}}
                }
            }return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static PrinterMaterialIndex prepare(Path directory,BlueprintCache source,SchematicEdits.Snapshot edits,Cancellation cancel)throws IOException{
        var metadata=edits==null?source.metadata():edits.metadata();Files.createDirectories(directory);
        var target=directory.resolve(fingerprint(metadata,edits)+".bmi");
        if(Files.isRegularFile(target))try{return open(target,true);}catch(IOException corrupt){ }
        var temporary=Files.createTempFile(directory,"material-",".part");
        try{
            var keys=new TreeSet<SectionKey>(ORDER);keys.addAll(source.index().keySet());if(edits!=null)keys.addAll(edits.sections().keySet());
            var entries=new LinkedHashMap<SectionKey,Entry>();
            try(var out=new RandomAccessFile(temporary.toFile(),"rw")){
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(0);
                long[] sorted=new long[PackedSection.VOLUME];
                boolean[] printable=new boolean[metadata.palette().size()];
                for(int i=0;i<printable.length;i++){
                    var spec=metadata.palette().get(i);
                    printable[i]=!spec.isAir()&&!spec.name().equals("minecraft:structure_void");
                }
                for(var key:keys){
                    cancel.check();
                    var section=source.read(key);
                    var patches=edits==null?null:edits.sections().get(key);
                    var size=metadata.regions().get(key.region()).size();
                    int width=Math.min(16,size.x()-16*key.x()),height=Math.min(16,size.y()-16*key.y()),depth=Math.min(16,size.z()-16*key.z());
                    int count=0;
                    if(section!=null){
                        for(int cell=section.nextNonZero(0);cell<PackedSection.VOLUME;cell=section.nextNonZero(cell+1)){
                            if(!inBounds(cell,width,height,depth)||(patches!=null&&patches.containsKey(cell)))continue;
                            int state=section.globalId(cell);
                            if(printable[state])sorted[count++]=((long)state<<32)|cell;
                        }
                    }

                    if(patches!=null)for(var entry:patches.entrySet()){
                        int cell=entry.getKey(),state=entry.getValue().state();
                        if(inBounds(cell,width,height,depth)&&printable[state])sorted[count++]=((long)state<<32)|cell;
                    }
                    if(count==0)continue;Arrays.sort(sorted,0,count);long offset=out.getFilePointer();
                    int groups=1;for(int i=1;i<count;i++)if(sorted[i]>>>32!=sorted[i-1]>>>32)groups++;

                    var payload=ByteBuffer.allocate(4+8*groups+2*count);payload.putInt(groups);
                    for(int start=0;start<count;){int end=start+1,state=(int)(sorted[start]>>>32);while(end<count&&(int)(sorted[end]>>>32)==state)end++;
                        payload.putInt(state).putInt(end-start);for(int i=start;i<end;i++)payload.putShort((short)sorted[i]);start=end;
                    }
                    out.write(payload.array());entries.put(key,new Entry(offset,payload.capacity()));
                }
                long table=out.getFilePointer();out.writeInt(entries.size());var records=ByteBuffer.allocate(28*4096);
                for(var entry:entries.entrySet()){
                    if(records.remaining()<28){out.write(records.array(),0,records.position());records.clear();}
                    var key=entry.getKey();records.putInt(key.region()).putInt(key.x()).putInt(key.y()).putInt(key.z()).putLong(entry.getValue().offset()).putInt(entry.getValue().bytes());
                }if(records.position()>0)out.write(records.array(),0,records.position());
                out.seek(8);out.writeLong(table);out.getFD().sync();
            }
            cancel.check();try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            return open(target,false);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static boolean inBounds(int cell,int width,int height,int depth){
        return (cell&15)<width&&(cell>>>8)<height&&((cell>>>4)&15)<depth;
    }
    private static void writeKey(DataOutput out,SectionKey key)throws IOException{out.writeInt(key.region());out.writeInt(key.x());out.writeInt(key.y());out.writeInt(key.z());}
    private static PrinterMaterialIndex open(Path path,boolean hit)throws IOException{
        var in=new RandomAccessFile(path.toFile(),"r");try{
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Material index format");long table=in.readLong();
            if(table<HEADER||table>in.length()-4)throw new IOException("Material index directory");in.seek(table);int count=in.readInt();
            if(count<0||table+4L+28L*count!=in.length())throw new IOException("Material index length");
            var entries=new LinkedHashMap<SectionKey,Entry>();long previous=HEADER;
            byte[] records=new byte[28*Math.min(4096,count)];ByteBuffer directory=ByteBuffer.wrap(records);directory.limit(0);
            for(int i=0;i<count;i++){Cancellation.THREAD.check();if(!directory.hasRemaining()){int bytes=Math.min(4096,count-i)*28;in.readFully(records,0,bytes);directory.clear().limit(bytes);}
                var key=new SectionKey(directory.getInt(),directory.getInt(),directory.getInt(),directory.getInt());long offset=directory.getLong();int bytes=directory.getInt();
                if(key.region()<0||key.x()<0||key.y()<0||key.z()<0||offset!=previous||bytes<14||bytes>40964||offset+bytes>table||entries.put(key,new Entry(offset,bytes))!=null)throw new IOException("Material index entry");previous=offset+bytes;
            }if(previous!=table)throw new IOException("Material index payload");return new PrinterMaterialIndex(in,entries,hit);
        }catch(IOException|RuntimeException e){in.close();throw e;}
    }

    public List<Slice> slices(SectionKey key)throws IOException{
        var entry=directory.get(key);if(entry==null)return List.of();byte[] data=new byte[entry.bytes()];
        synchronized(file){file.seek(entry.offset());file.readFully(data);}
        var input=ByteBuffer.wrap(data);int n=input.getInt(),total=0,previous=-1;var result=new ArrayList<Slice>();
        if(n<1||n>4096)throw new IOException("Material group count");
        for(int i=0;i<n;i++){
            if(input.remaining()<8)throw new IOException("Material slice header");int state=input.getInt(),count=input.getInt();
            if(state<=previous||count<1||count>4096||(total+=count)>4096||2*count>input.remaining())throw new IOException("Material slice");
            result.add(new Slice(state,entry.offset()+input.position(),count));previous=state;input.position(input.position()+2*count);
        }if(input.hasRemaining())throw new IOException("Material slice length");return result;
    }
    public int[] cells(Slice slice)throws IOException{
        byte[] data=new byte[2*slice.count()];synchronized(file){file.seek(slice.offset());file.readFully(data);}
        var input=ByteBuffer.wrap(data);int[] cells=new int[slice.count()];int previous=-1;
        for(int i=0;i<cells.length;i++){int cell=Short.toUnsignedInt(input.getShort());if(cell<=previous||cell>=4096)throw new IOException("Material cell");cells[i]=cell;previous=cell;}
        return cells;
    }
    public List<Group> read(SectionKey key)throws IOException{
        var entry=directory.get(key);if(entry==null)return List.of();byte[] bytes=new byte[entry.bytes()];synchronized(file){file.seek(entry.offset());file.readFully(bytes);}
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){int n=in.readInt();if(n<1||n>4096)throw new IOException("Material group count");var groups=new ArrayList<Group>(n);int cells=0,previous=-1;
            for(int i=0;i<n;i++){int state=in.readInt(),count=in.readInt();if(state<=previous||count<1||count>4096||(cells+=count)>4096)throw new IOException("Material group");previous=state;int[] values=new int[count];int last=-1;
                for(int j=0;j<count;j++){int cell=in.readUnsignedShort();if(cell<=last||cell>=4096)throw new IOException("Material cell");values[j]=cell;last=cell;}groups.add(new Group(state,values));
            }if(in.read()!=-1)throw new IOException("Material trailing data");return groups;
        }
    }
    @Override public void close()throws IOException{file.close();}
}
