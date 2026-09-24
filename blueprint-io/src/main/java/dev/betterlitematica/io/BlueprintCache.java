package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** BPC v1: immutable metadata, individually compressed sections, and an on-disk directory. */
public final class BlueprintCache implements AutoCloseable {
    private static final int MAGIC=0x42504331,VERSION=2,MAX_SECTIONS=1_000_000,MAX_RECORD_BYTES=65536;
    public record Entry(SectionKey key,long offset,int compressedBytes,int rawBytes,int crc32) {}
    private final RandomAccessFile file;
    private final BlueprintMetadata metadata;
    private final Map<SectionKey,Entry> index;
    private final long[] counts;
    private final List<StateHistogram> regionCounts;
    private boolean closed;
    private BlueprintCache(RandomAccessFile file,BlueprintMetadata metadata,Map<SectionKey,Entry> index,long[] counts,List<StateHistogram> regionCounts){
        this.file=file;this.metadata=metadata;this.index=Collections.unmodifiableMap(index);this.counts=counts;this.regionCounts=List.copyOf(regionCounts);
    }
    public static BlueprintCache open(Path path)throws IOException {
        RandomAccessFile in=new RandomAccessFile(path.toFile(),"r");
        try{
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported BPC cache format");
            long directory=in.readLong();BlueprintMetadata meta=readMetadata(in);long payloadStart=in.getFilePointer();
            if(directory<payloadStart||directory>in.length()-4)throw new IOException("Invalid BPC directory offset");
            in.seek(directory);int n=bounded(in.readInt(),0,MAX_SECTIONS,"section count");
            long expectedEnd=directory+4L+36L*n+8L*meta.palette().size();
            if(expectedEnd+4L*meta.regions().size()>in.length())throw new IOException("Truncated BPC directory");
            Map<SectionKey,Entry> entries=new LinkedHashMap<>();long previousEnd=payloadStart;
            for(int i=0;i<n;i++){
                Cancellation.THREAD.check();SectionKey key=new SectionKey(in.readInt(),in.readInt(),in.readInt(),in.readInt());
                long offset=in.readLong();int compressed=bounded(in.readInt(),1,MAX_RECORD_BYTES,"compressed section size");
                int raw=bounded(in.readInt(),1,MAX_RECORD_BYTES,"raw section size"),crc=in.readInt();validateKey(meta,key);
                if(offset!=previousEnd||offset+compressed>directory)throw new IOException("Overlapping/out-of-bounds BPC section");
                previousEnd=offset+compressed;
                if(entries.put(key,new Entry(key,offset,compressed,raw,crc))!=null)throw new IOException("Duplicate BPC section");
            }
            if(previousEnd!=directory)throw new IOException("Unindexed BPC payload");
            long[] counts=new long[meta.palette().size()];long total=0;
            for(int i=0;i<counts.length;i++){counts[i]=in.readLong();if(counts[i]<0)throw new IOException("Negative material count");total=Math.addExact(total,counts[i]);}
            long volume=0;for(Region r:meta.regions())volume=Math.addExact(volume,r.volume());
            if(total!=volume)throw new IOException("BPC count/domain mismatch");
            List<StateHistogram> regionCounts=new ArrayList<>();long entriesRead=0;long[] sum=new long[counts.length];
            for(var region:meta.regions()){int size=bounded(in.readInt(),1,counts.length,"region histogram");entriesRead+=size;if(entriesRead>1_000_000)throw new IOException("Regional histogram budget");int[] ids=new int[size];long[] values=new long[size];for(int i=0;i<size;i++){ids[i]=bounded(in.readInt(),0,counts.length-1,"histogram state");values[i]=in.readLong();}var histogram=new StateHistogram(ids,values);if(histogram.total()!=region.volume())throw new IOException("Region histogram/domain mismatch");histogram.addTo(sum);regionCounts.add(histogram);}
            if(!Arrays.equals(sum,counts)||in.getFilePointer()!=in.length())throw new IOException("Invalid regional counts or trailing cache data");
            return new BlueprintCache(in,meta,entries,counts,regionCounts);
        }catch(IOException|RuntimeException e){in.close();if(e instanceof IOException x)throw x;throw new IOException("Malformed BPC cache",e);}
    }
    private static int bounded(int n,int min,int max,String label)throws IOException{if(n<min||n>max)throw new IOException("Invalid "+label);return n;}
    public BlueprintMetadata metadata(){return metadata;}
    public Map<SectionKey,Entry> index(){return index;}
    public long[] copyBlockStateCounts(){return counts.clone();}
    public List<StateHistogram> regionCounts(){return regionCounts;}
    public PackedSection read(SectionKey key)throws IOException{
        Entry e;byte[] compressed;
        // Only the shared file cursor is serialized. Inflate, CRC and palette validation
        // run independently on the bounded decode workers, using their own byte arrays.
        synchronized(this){
            if(closed)throw new IOException("Cache already closed");
            e=index.get(key);if(e==null)return null;
            Cancellation.THREAD.check();compressed=new byte[e.compressedBytes];file.seek(e.offset);file.readFully(compressed);
        }
        byte[] raw;
        try(InputStream inflater=new InflaterInputStream(new ByteArrayInputStream(compressed))){
            raw=inflater.readNBytes(e.rawBytes+1);
        }
        if(raw.length!=e.rawBytes)throw new IOException("Section decompression size mismatch");
        Cancellation.THREAD.check();
        CRC32 crc=new CRC32();crc.update(raw);if((int)crc.getValue()!=e.crc32)throw new IOException("Section CRC mismatch");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw))){
            PackedSection section=PackedSection.read(in,metadata.palette().size());
            if(in.read()!=-1)throw new IOException("Trailing section payload");return section;
        }
    }
    @Override public synchronized void close()throws IOException{if(!closed){closed=true;file.close();}}
    private static void validateKey(BlueprintMetadata metadata,SectionKey key)throws IOException{
        if(key.region()>=metadata.regions().size())throw new IOException("Section region index invalid");
        Region r=metadata.regions().get(key.region());
        if((long)key.x()*16>=r.size().x()||(long)key.y()*16>=r.size().y()||(long)key.z()*16>=r.size().z())throw new IOException("Section outside region");
    }
    private static final class MetadataStrings {
        private long remaining=16L<<20;
        private void account(String value)throws IOException{remaining-=48L+2L*value.length();if(remaining<0)throw new IOException("Cache metadata string budget exceeded");}
        String read(DataInput in)throws IOException{String value=in.readUTF();account(value);return value;}
        void write(DataOutput out,String value)throws IOException{account(value);out.writeUTF(value);}
    }
    private static void writeMetadata(DataOutput out,BlueprintMetadata m)throws IOException {
        MetadataStrings strings=new MetadataStrings();
        strings.write(out,m.name());out.writeInt(m.dataVersion());strings.write(out,m.sourceSha256());
        out.writeInt(m.palette().size());for(BlockStateSpec p:m.palette())strings.write(out,p.toString());
        out.writeInt(m.regions().size());for(Region r:m.regions()){
            strings.write(out,r.name());out.writeInt(r.min().x());out.writeInt(r.min().y());out.writeInt(r.min().z());
            out.writeInt(r.size().x());out.writeInt(r.size().y());out.writeInt(r.size().z());
            out.writeInt(r.anchor().x());out.writeInt(r.anchor().y());out.writeInt(r.anchor().z());
        }
        out.writeInt(m.warnings().size());for(String w:m.warnings())strings.write(out,w);
    }
    private static BlueprintMetadata readMetadata(DataInput in)throws IOException {
        MetadataStrings strings=new MetadataStrings();
        String name=strings.read(in);int version=in.readInt();String hash=strings.read(in);
        int n=bounded(in.readInt(),1,65536,"palette size");List<BlockStateSpec> palette=new ArrayList<>(n);
        for(int i=0;i<n;i++)palette.add(BlockStateSpec.parse(strings.read(in)));
        n=bounded(in.readInt(),1,1024,"region count");List<Region> regions=new ArrayList<>(n);
        for(int i=0;i<n;i++)regions.add(new Region(strings.read(in),new Vec3i(in.readInt(),in.readInt(),in.readInt()),new Vec3i(in.readInt(),in.readInt(),in.readInt()),new Vec3i(in.readInt(),in.readInt(),in.readInt())));
        n=bounded(in.readInt(),0,1024,"warning count");List<String>warnings=new ArrayList<>(n);for(int i=0;i<n;i++)warnings.add(strings.read(in));
        return new BlueprintMetadata(name,version,hash,palette,regions,warnings);
    }
    public static final class Writer implements AutoCloseable {
        private final Path temporary,target;private final RandomAccessFile out;private final BlueprintMetadata metadata;
        private final LinkedHashMap<SectionKey,Entry> entries=new LinkedHashMap<>();private final long[] counts;
        private final List<StateHistogram> regionCounts=new ArrayList<>();private final long[] activeCounts;private int activeRegion;
        private boolean committed,closed;
        public Writer(Path target,BlueprintMetadata metadata)throws IOException {
            this.target=target.toAbsolutePath();this.metadata=metadata;counts=new long[metadata.palette().size()];activeCounts=new long[counts.length];
            Files.createDirectories(this.target.getParent());temporary=Files.createTempFile(this.target.getParent(),"bpc-",".part");
            RandomAccessFile handle=null;
            try{handle=new RandomAccessFile(temporary.toFile(),"rw");handle.writeInt(MAGIC);handle.writeInt(VERSION);handle.writeLong(0);writeMetadata(handle,metadata);out=handle;}
            catch(IOException|RuntimeException e){if(handle!=null)handle.close();Files.deleteIfExists(temporary);throw e;}
        }
        public void count(int globalId){count(0,globalId);}
        public void count(int region,int globalId){if(region<activeRegion||region>=metadata.regions().size())throw new IllegalArgumentException("Regions must be counted in order");while(activeRegion<region)finishRegion();counts[globalId]=Math.addExact(counts[globalId],1);activeCounts[globalId]=Math.addExact(activeCounts[globalId],1);}
        private void finishRegion(){regionCounts.add(StateHistogram.from(activeCounts));Arrays.fill(activeCounts,0);activeRegion++;}
        public void add(SectionKey key,PackedSection section)throws IOException{
            if(closed)throw new IOException("Writer closed");validateKey(metadata,key);
            if(section.isCanonicalAir())return;
            if(entries.size()>=MAX_SECTIONS||entries.containsKey(key))throw new IOException("Section limit/duplicate");
            ByteArrayOutputStream rawBuffer=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(rawBuffer)){section.write(data);}
            byte[] raw=rawBuffer.toByteArray();CRC32 crc=new CRC32();crc.update(raw);
            ByteArrayOutputStream packed=new ByteArrayOutputStream();
            try(DeflaterOutputStream deflater=new DeflaterOutputStream(packed)){deflater.write(raw);}
            byte[] compressed=packed.toByteArray();if(raw.length>MAX_RECORD_BYTES||compressed.length>MAX_RECORD_BYTES)throw new IOException("Section record too large");
            long offset=out.getFilePointer();out.write(compressed);entries.put(key,new Entry(key,offset,compressed.length,raw.length,(int)crc.getValue()));
        }
        public void commit(Cancellation cancel)throws IOException {
            if(closed)throw new IOException("Writer closed");cancel.check();long directory=out.getFilePointer();out.writeInt(entries.size());
            for(Entry e:entries.values()){
                cancel.check();out.writeInt(e.key.region());out.writeInt(e.key.x());out.writeInt(e.key.y());out.writeInt(e.key.z());
                out.writeLong(e.offset);out.writeInt(e.compressedBytes);out.writeInt(e.rawBytes);out.writeInt(e.crc32);
            }
            long total=0;for(long c:counts){out.writeLong(c);total=Math.addExact(total,c);}long volume=0;for(Region r:metadata.regions())volume=Math.addExact(volume,r.volume());
            if(total!=volume)throw new IOException("Cannot commit: counts do not cover region domain");
            while(activeRegion<metadata.regions().size())finishRegion();long histogramEntries=0;
            for(int region=0;region<regionCounts.size();region++){var histogram=regionCounts.get(region);if(histogram.total()!=metadata.regions().get(region).volume())throw new IOException("Region counts do not cover domain");histogramEntries+=histogram.size();if(histogramEntries>1_000_000)throw new IOException("Regional histogram budget");out.writeInt(histogram.size());for(int i=0;i<histogram.size();i++){out.writeInt(histogram.id(i));out.writeLong(histogram.count(i));}}
            out.seek(8);out.writeLong(directory);out.getFD().sync();out.close();closed=true;cancel.check();
            // Same-directory atomic publish. Do not silently publish partially written data.
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);committed=true;
        }
        @Override public void close()throws IOException {
            try{if(!closed){closed=true;out.close();}}finally{if(!committed)Files.deleteIfExists(temporary);}
        }
    }
}
