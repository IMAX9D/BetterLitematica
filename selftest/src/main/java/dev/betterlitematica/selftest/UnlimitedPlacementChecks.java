package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

/** Quantity is not a validity rule; serialized and resident bytes remain bounded. */
public final class UnlimitedPlacementChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static Placement entry(int i){return new Placement(new UUID(17,i),"placement "+i,"source-"+i+".litematic",new PlacementTransform(new Vec3i(i*16,-39,-i),i&3,(i&1)!=0,false),true,false);}
    @FunctionalInterface private interface IoAction{void run()throws Exception;}
    private static void rejects(IoAction action,String message)throws Exception{boolean rejected=false;try{action.run();}catch(IOException expected){rejected=true;}check(rejected,message);}
    public static int run()throws Exception{
        checks=0;var directory=Files.createTempDirectory("unlimited-placements-");
        var placements=new ArrayList<Placement>();for(int i=0;i<1000;i++)placements.add(entry(i));
        var session=new PlacementSession(placements,placements.get(999).id(),new LayerRange(LayerRange.Axis.Y,-39,64),.67f,true);
        var file=directory.resolve("many.blps");PlacementStore.write(file,session);
        check(PlacementStore.read(file).equals(session),"1000 placements, selected identity and signed geometry survive actual file roundtrip");
        var good=Files.readAllBytes(file);
        var impossible=PlacementStore.encode(PlacementSession.EMPTY);ByteBuffer.wrap(impossible).putInt(23,Integer.MAX_VALUE);checksum(impossible);
        rejects(()->PlacementStore.decode(impossible),"CRC-valid impossible count rejected by remaining bytes");
        var negative=impossible.clone();ByteBuffer.wrap(negative).putInt(23,-1);checksum(negative);
        rejects(()->PlacementStore.decode(negative),"Negative placement count rejected");
        rejects(()->PlacementStore.decode(Arrays.copyOf(good,good.length-1)),"Truncated multi-placement settings rejected");
        for(int version:new int[]{1,2}){
            var old=legacy(version,32);var decoded=PlacementStore.decode(old);
            check(decoded.placements().size()==32&&decoded.selected()==null,"Version "+version+" supports 32 records using its own minimum record width");
            check(PlacementStore.decode(PlacementStore.encode(decoded)).equals(decoded),"Version "+version+" migration preserves complete session");
        }
        var large=new ArrayList<Placement>();String name="名".repeat(120),source="源".repeat(1024);for(int i=0;i<4000;i++)large.add(new Placement(new UUID(29,i),name,source,new PlacementTransform(Vec3i.ZERO,0,false,false),true,false));
        var oversized=new PlacementSession(large,null,LayerRange.ALL,.45f,true);
        rejects(()->PlacementStore.write(file,oversized),"UTF-heavy output stops at 8 MiB serialization budget");
        check(Arrays.equals(good,Files.readAllBytes(file)),"Oversized output cannot replace last valid settings");
        var root=Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,1,1,1,i->1)),"small");
        try(var sources=new TemporarySources(directory.resolve("temporary"))){
            var ids=new ArrayList<String>();for(int i=0;i<32;i++)ids.add(sources.create(root,Cancellation.NEVER));
            for(var id:ids)check(Files.isRegularFile(sources.reference(directory,id).read()),"More than 16 complete temporary sources remain individually readable");
            var lease=sources.lease(directory,ids.get(0));var leased=lease.reference().read();sources.clear();sources.cleanupAsync().get(5,java.util.concurrent.TimeUnit.SECONDS);
            check(Files.exists(leased),"Leased temporary source survives retirement with larger catalog");lease.close();sources.cleanupAsync().get(5,java.util.concurrent.TimeUnit.SECONDS);check(!Files.exists(leased),"Final lease release permits exact source cleanup");
            rejects(()->sources.create(root,()->true),"Cancelled temporary creation is rejected");
            var id=sources.create(root,Cancellation.NEVER);check(Files.exists(sources.reference(directory,id).read()),"Cancelled reservation does not poison subsequent creation");
        }
        try(var tiny=new TemporarySources(directory.resolve("budgeted-temporary"),8192)){
            var id=tiny.create(root,Cancellation.NEVER);var lease=tiny.lease(directory,id);var old=lease.reference().read();
            rejects(()->tiny.create(root,Cancellation.NEVER),"Entry overhead and source bytes enforce temporary byte budget");
            tiny.remove(id);rejects(()->tiny.create(root,Cancellation.NEVER),"Retired leased source still consumes byte budget");
            check(Files.exists(old),"Budget rejection does not delete leased full source");lease.close();tiny.cleanupAsync().get(5,java.util.concurrent.TimeUnit.SECONDS);
            var next=tiny.create(root,Cancellation.NEVER);check(Files.exists(tiny.reference(directory,next).read()),"Successful retirement restores byte budget for new source");
        }
        var retained=new ArrayList<LoadCoordinator.Loaded>();
        try(var loader=new LoadCoordinator()){
            long total=0;
            for(int i=0;i<32;i++){
                var input=directory.resolve("independent-"+i+".litematic");Fixtures.write(input,Fixtures.litematic(Map.of("r",Fixtures.region(0,0,0,1,1,1,n->1)),"independent "+i));
                loader.request(input,directory.resolve("cache"),directory);LoadCoordinator.Loaded value=null;long deadline=System.nanoTime()+5_000_000_000L;
                while(value==null&&System.nanoTime()<deadline){value=loader.poll();if(value==null&&loader.status().phase().equals("failed"))throw new AssertionError(loader.status().error());if(value==null)Thread.sleep(2);}
                check(value!=null,"Independent small source loads through actual bounded import worker");retained.add(value);long bytes=value.estimatedBytes();total+=bytes;
                check(bytes>0&&bytes<16L*1024*1024,"Small-file accounting does not reserve obsolete flat 96 MiB");
                check(value.details()!=null&&value.detailsError().isEmpty(),"Independent source retains full auxiliary data");
                try(var duplicate=value.retain()){check(duplicate.estimatedBytes()==bytes&&duplicate.stream()==value.stream(),"Retain shares cached estimate and decoder without source rewalk");}
            }
            check(retained.size()==32&&total<(1L<<30),"32 distinct loaded files fit preserved 1 GiB admission budget");
            var hashes=new HashSet<String>();for(var value:retained)hashes.add(value.cache().metadata().sourceSha256());check(hashes.size()==32,"Load regression uses 32 different immutable source files, not aliases");
        }finally{for(var value:retained)value.close();}
        try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        return checks;
    }
    private static void checksum(byte[] bytes){var crc=new CRC32();crc.update(bytes,0,bytes.length-8);ByteBuffer.wrap(bytes).putLong(bytes.length-8,crc.getValue());}
    private static byte[] legacy(int version,int count)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x424c5053);out.writeInt(version);out.writeBoolean(false);out.writeByte(1);out.writeInt(Integer.MIN_VALUE);out.writeInt(Integer.MAX_VALUE);out.writeFloat(.45f);out.writeBoolean(true);out.writeInt(count);for(int i=0;i<count;i++){out.writeLong(51);out.writeLong(i);out.writeUTF("old "+i);out.writeUTF("old-"+i+".litematic");out.writeInt(i);out.writeInt(-39);out.writeInt(-i);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(false);if(version>=2)out.writeFloat(.67f);}var crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());}return bytes.toByteArray();}
    public static void main(String[] args)throws Exception{System.out.println("UnlimitedPlacementChecks: "+run());}
}
