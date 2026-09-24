package dev.betterlitematica.runtime;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Serial disk-backed section contributions. Main thread submits immutable data, never performs file IO. */
public final class VerificationLedger implements AutoCloseable {
    public record Replacement(SectionKey key,VerificationSection before,VerificationSection after,long revision){}
    public record SpatialQuery(List<SectionKey> keys,int total,long revision){}
    public record SpatialSection(long revision,List<HighlightCuboids.Box> boxes,PlacementBounds bounds){}
    private record Record(long offset,int bytes,PlacementBounds bounds,long revision){}
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"betterlitematica-verifier-ledger");t.setDaemon(true);return t;});
    private final Map<SectionKey,Record> records=new LinkedHashMap<>();
    private final WeightedLru<SectionKey,VerificationSection> cache=new WeightedLru<>(16L<<20,4096,VerificationSection::estimatedBytes,v->{});
    private Path path;private RandomAccessFile file;private long liveBytes;private volatile long revision;private volatile boolean closed;
    private final Set<CompletableFuture<?>> jobs=Collections.synchronizedSet(new HashSet<>());private final CompletableFuture<Void> closedFuture=new CompletableFuture<>();
    private <T> CompletableFuture<T> track(CompletableFuture<T> future){jobs.add(future);future.whenComplete((v,e)->jobs.remove(future));return future;}
    public CompletableFuture<Void> closedFuture(){return closedFuture;}
    public CompletableFuture<List<SectionKey>> knownSections(){var result=track(new CompletableFuture<List<SectionKey>>());if(closed){result.cancel(false);return result;}try{worker.execute(()->result.complete(List.copyOf(records.keySet())));}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;}
    public CompletableFuture<Replacement> replace(SectionKey key,VerificationSection next){
        var future=track(new CompletableFuture<Replacement>());if(closed){future.completeExceptionally(new CancellationException());return future;}
        try{worker.execute(()->{try{if(closed)throw new CancellationException();open();var previous=read(key);byte[] data=encode(next);
            if(previous.equals(next)){var old=records.get(key);future.complete(new Replacement(key,previous,next,old==null?0:old.revision()));return;}
            if(!records.containsKey(key)&&records.size()>=1_000_000)throw new IOException("Verification section index exceeds budget");
            if(file.length()+data.length>(1L<<30))compact();if(file.length()+data.length>(1L<<30))throw new IOException("Verification ledger exceeds 1 GiB");
            long offset=file.length();file.seek(offset);file.write(data);long version=++revision;var old=records.put(key,new Record(offset,data.length,bounds(next.positions()),version));liveBytes+=data.length-(old==null?0:old.bytes());cache.put(key,next);
            future.complete(new Replacement(key,previous,next,version));
            if(!closed&&file.length()>(16L<<20)+liveBytes*3)compact();
        }catch(Exception e){future.completeExceptionally(e);}});}catch(RejectedExecutionException e){future.completeExceptionally(e);}return future;
    }
    public long revision(){return revision;}
    private void dispatch(CompletableFuture<?> result,Runnable action){
        Runnable task=()->{if(!result.isCancelled())action.run();};
        result.whenComplete((value,error)->{if(result.isCancelled())worker.remove(task);});
        worker.execute(task);
        if(result.isCancelled())worker.remove(task);
    }
    public CompletableFuture<List<HighlightCuboids.Box>> coalesce(List<HighlightCuboids.Box> boxes){
        if(boxes.size()>600000)throw new IllegalArgumentException("Highlight merge memory budget");
        var result=track(new CompletableFuture<List<HighlightCuboids.Box>>());if(closed){result.cancel(false);return result;}
        try{dispatch(result,()->{try{if(closed||result.isCancelled())throw new CancellationException();result.complete(HighlightCuboids.coalesce(boxes));}catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;
    }
    /** The index contains every committed spatial error; only the nearby working set is returned. */
    public CompletableFuture<SpatialQuery> nearby(Vec3i center,int radius,int limit){
        if(radius<1||radius>256||limit<1||limit>32768)throw new IllegalArgumentException("Spatial query budget");
        var result=track(new CompletableFuture<SpatialQuery>());if(closed){result.cancel(false);return result;}
        try{dispatch(result,()->{try{
            record Hit(SectionKey key,double distance){}
            var nearest=new PriorityQueue<Hit>((a,b)->Double.compare(b.distance(),a.distance()));int total=0,visited=0;double radius2=(double)radius*radius;
            for(var entry:records.entrySet()){
                if((visited++&511)==0&&(closed||result.isCancelled()))throw new CancellationException();
                var box=entry.getValue().bounds();if(box==null)continue;double distance=distanceSquared(box,center);if(distance>radius2)continue;total++;
                if(nearest.size()<limit)nearest.add(new Hit(entry.getKey(),distance));else if(distance<nearest.peek().distance()){nearest.remove();nearest.add(new Hit(entry.getKey(),distance));}
            }
            var sorted=new ArrayList<>(nearest);sorted.sort((a,b)->Double.compare(a.distance(),b.distance()));var selected=new ArrayList<SectionKey>();for(var hit:sorted)selected.add(hit.key());result.complete(new SpatialQuery(List.copyOf(selected),total,revision));
        }catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;
    }
    public CompletableFuture<Map<SectionKey,SpatialSection>> spatial(Collection<SectionKey> keys,Set<VerificationReport.Key> ignored){
        if(keys.size()>8)throw new IllegalArgumentException("Spatial read batch exceeds eight sections");
        var requested=List.copyOf(keys);var skip=Set.copyOf(ignored);var result=track(new CompletableFuture<Map<SectionKey,SpatialSection>>());if(closed){result.cancel(false);return result;}
        try{dispatch(result,()->{try{var output=new LinkedHashMap<SectionKey,SpatialSection>();for(var key:requested){if(closed||result.isCancelled())throw new CancellationException();var record=records.get(key);if(record==null){output.put(key,new SpatialSection(0,List.of(),null));continue;}var section=read(key);var cells=new ArrayList<HighlightCuboids.Cell>();for(var sample:section.positions())if(!skip.contains(sample.key()))cells.add(new HighlightCuboids.Cell(sample.position(),sample.key().type().ordinal()));output.put(key,new SpatialSection(record.revision(),HighlightCuboids.merge(cells),record.bounds()));}result.complete(Collections.unmodifiableMap(output));}catch(Exception e){result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;
    }
    private static PlacementBounds bounds(List<VerificationReport.Sample> positions){if(positions.isEmpty())return null;int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;for(var sample:positions){var p=sample.position();x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}return new PlacementBounds(new Vec3i(x0,y0,z0),new Vec3i(x1,y1,z1));}
    private static double distanceSquared(PlacementBounds box,Vec3i point){double x=Math.max(0,Math.max((double)box.min().x()-point.x(),(double)point.x()-box.max().x())),y=Math.max(0,Math.max((double)box.min().y()-point.y(),(double)point.y()-box.max().y())),z=Math.max(0,Math.max((double)box.min().z()-point.z(),(double)point.z()-box.max().z()));return x*x+y*y+z*z;}
    private void open()throws IOException{if(file==null){path=Files.createTempFile("betterlitematica-verification-",".bin");file=new RandomAccessFile(path.toFile(),"rw");}}
    private VerificationSection read(SectionKey key)throws IOException{var value=cache.get(key);if(value!=null)return value;var record=records.get(key);if(record==null)return VerificationSection.EMPTY;byte[] data=new byte[record.bytes()];file.seek(record.offset());file.readFully(data);return decode(data);}
    private void compact()throws IOException{
        Path next=Files.createTempFile("betterlitematica-verification-",".bin");var offsets=new LinkedHashMap<SectionKey,Record>();
        try(var output=new RandomAccessFile(next.toFile(),"rw")){for(var entry:records.entrySet()){if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException();var record=entry.getValue();byte[] data=new byte[record.bytes()];file.seek(record.offset());file.readFully(data);long offset=output.getFilePointer();output.write(data);offsets.put(entry.getKey(),new Record(offset,data.length,record.bounds(),record.revision()));}}
        catch(IOException|RuntimeException e){Files.deleteIfExists(next);throw e;}
        file.close();Files.deleteIfExists(path);path=next;file=new RandomAccessFile(path.toFile(),"rw");records.clear();records.putAll(offsets);
    }
    private static byte[] encode(VerificationSection value)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(new DeflaterOutputStream(bytes))){out.writeLong(value.matched());out.writeInt(value.groups().size());var ids=new HashMap<VerificationReport.Key,Integer>();for(var e:value.groups().entrySet()){ids.put(e.getKey(),ids.size());key(out,e.getKey());out.writeLong(e.getValue());}out.writeInt(value.samples().size());for(var sample:value.samples()){out.writeInt(sample.position().x());out.writeInt(sample.position().y());out.writeInt(sample.position().z());key(out,sample.key());}out.writeInt(value.positions().size());for(var sample:value.positions()){out.writeInt(sample.position().x());out.writeInt(sample.position().y());out.writeInt(sample.position().z());out.writeShort(ids.get(sample.key()));}}
        if(bytes.size()>8*1024*1024)throw new IOException("Verification section exceeds budget");return bytes.toByteArray();
    }
    private static VerificationSection decode(byte[] bytes)throws IOException{try(var in=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))){
        long matched=in.readLong();int n=in.readInt();if(n<0||n>4096)throw new IOException("Invalid verification groups");var groups=new LinkedHashMap<VerificationReport.Key,Long>();for(int i=0;i<n;i++)groups.put(key(in),in.readLong());n=in.readInt();if(n<0||n>32)throw new IOException("Invalid verification samples");var samples=new ArrayList<VerificationReport.Sample>();for(int i=0;i<n;i++)samples.add(new VerificationReport.Sample(new Vec3i(in.readInt(),in.readInt(),in.readInt()),key(in)));n=in.readInt();if(n<0||n>4096)throw new IOException("Invalid spatial positions");var identities=new ArrayList<>(groups.keySet());var positions=new ArrayList<VerificationReport.Sample>();for(int i=0;i<n;i++){var pos=new Vec3i(in.readInt(),in.readInt(),in.readInt());int group=in.readUnsignedShort();if(group>=identities.size())throw new IOException("Invalid spatial group");positions.add(new VerificationReport.Sample(pos,identities.get(group)));}return new VerificationSection(groups,matched,samples,positions);
    }}
    private static void key(DataOutput out,VerificationReport.Key key)throws IOException{out.writeByte(key.type().ordinal());out.writeUTF(key.expected());out.writeUTF(key.actual());}
    private static VerificationReport.Key key(DataInput in)throws IOException{int type=in.readUnsignedByte();if(type>=Comparison.values().length)throw new IOException("Invalid comparison");return new VerificationReport.Key(Comparison.values()[type],in.readUTF(),in.readUTF());}
    @Override public synchronized void close(){if(closed)return;closed=true;List<CompletableFuture<?>> pending;synchronized(jobs){pending=List.copyOf(jobs);}for(var future:pending)future.cancel(false);worker.getQueue().clear();try{worker.execute(()->{cache.close();records.clear();try{if(file!=null)file.close();if(path!=null)Files.deleteIfExists(path);closedFuture.complete(null);}catch(IOException e){closedFuture.completeExceptionally(e);}});}finally{worker.shutdown();}}
}
