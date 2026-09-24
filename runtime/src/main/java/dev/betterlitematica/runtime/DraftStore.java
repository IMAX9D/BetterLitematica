package dev.betterlitematica.runtime;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Complete bounded recovery image, independent of the placement settings commit. */
public final class DraftStore {
    private static final int MAGIC=0x424c4452,VERSION=1;
    public static final int MAX_BYTES=64*1024*1024;
    public record Draft(String worldKey,Placement placement,SchematicEdits.Checkpoint checkpoint){
        public Draft{Objects.requireNonNull(worldKey);Objects.requireNonNull(placement);Objects.requireNonNull(checkpoint);if(worldKey.isBlank()||worldKey.length()>1024)throw new IllegalArgumentException("Invalid draft world identity");}
    }
    private DraftStore(){}
    public static Draft read(Path file)throws IOException{
        if(!Files.exists(file))return null;if(Files.size(file)>MAX_BYTES)throw new IOException("Draft file exceeds budget");byte[] bytes;
        try(var in=Files.newInputStream(file)){bytes=in.readNBytes(MAX_BYTES+1);}return decode(bytes);
    }
    public static Draft decode(byte[] bytes)throws IOException{
        if(bytes.length<16||bytes.length>MAX_BYTES)throw new IOException("Invalid draft size");var crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        try(var tail=new DataInputStream(new ByteArrayInputStream(bytes,bytes.length-8,8))){if(tail.readLong()!=crc.getValue())throw new IOException("Draft checksum mismatch");}
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes,0,bytes.length-8))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported draft format");var strings=new Strings();String world=strings.read(in);
            int length=bounded(in.readInt(),1,8*1024*1024,"placement size");byte[] placement=in.readNBytes(length);if(placement.length!=length)throw new EOFException();var session=PlacementStore.decode(placement);if(session.placements().size()!=1)throw new IOException("Draft must identify one placement");
            String name=strings.read(in);int dataVersion=in.readInt();String hash=strings.read(in);int originalSize=in.readInt();long revision=in.readLong();
            int count=bounded(in.readInt(),1,65536,"palette");var palette=new ArrayList<BlockStateSpec>(count);for(int i=0;i<count;i++)palette.add(BlockStateSpec.parse(strings.read(in)));
            count=bounded(in.readInt(),1,1024,"regions");var regions=new ArrayList<Region>(count);for(int i=0;i<count;i++)regions.add(new Region(strings.read(in),vector(in),vector(in),vector(in)));
            count=bounded(in.readInt(),0,1024,"warnings");var warnings=new ArrayList<String>(count);for(int i=0;i<count;i++)warnings.add(strings.read(in));
            count=bounded(in.readInt(),0,SchematicEdits.MAX_SECTIONS,"sections");var sections=new HashMap<SectionKey,Map<Integer,SchematicEdits.Patch>>();int cells=0;
            for(int i=0;i<count;i++){var key=section(in);int n=bounded(in.readInt(),1,4096,"section cells");cells+=n;if(cells>SchematicEdits.MAX_CELLS)throw new IOException("Draft cell budget");var values=new HashMap<Integer,SchematicEdits.Patch>();for(int j=0;j<n;j++){int cell=in.readUnsignedShort();var value=patch(in);if(value==null||values.put(cell,value)!=null)throw new IOException("Invalid duplicate draft cell");}if(sections.put(key,values)!=null)throw new IOException("Duplicate draft section");}
            int[] history={0,0};var undo=actions(in,history);var redo=actions(in,history);if(in.read()!=-1)throw new IOException("Trailing draft bytes");
            var metadata=new BlueprintMetadata(name,dataVersion,hash,palette,regions,warnings);var image=new SchematicEdits.Checkpoint(metadata,originalSize,sections,revision,undo,redo);SchematicEdits.validateCheckpoint(image);return new Draft(world,session.placements().get(0),image);
        }catch(IllegalArgumentException|ArithmeticException e){throw new IOException("Invalid draft checkpoint",e);}
    }
    public static byte[] encode(Draft draft)throws IOException{
        try{SchematicEdits.validateCheckpoint(draft.checkpoint());}catch(IllegalArgumentException|ArithmeticException e){throw new IOException("Invalid draft checkpoint",e);}
        var bytes=new ByteArrayOutputStream();var crc=new CRC32();
        try(var out=new DataOutputStream(new OutputStream(){int size;@Override public void write(int value)throws IOException{if(++size>MAX_BYTES)throw new IOException("Draft output budget");bytes.write(value);crc.update(value);}@Override public void write(byte[] b,int offset,int length)throws IOException{if(length>MAX_BYTES-size)throw new IOException("Draft output budget");size+=length;bytes.write(b,offset,length);crc.update(b,offset,length);}})){
            var strings=new Strings();out.writeInt(MAGIC);out.writeInt(VERSION);strings.write(out,draft.worldKey());var p=draft.placement();byte[] placement=PlacementStore.encode(new PlacementSession(List.of(p),p.id(),LayerRange.ALL,p.opacity(),true));out.writeInt(placement.length);out.write(placement);
            var image=draft.checkpoint();var meta=image.metadata();strings.write(out,meta.name());out.writeInt(meta.dataVersion());strings.write(out,meta.sourceSha256());out.writeInt(image.originalPaletteSize());out.writeLong(image.revision());
            out.writeInt(meta.palette().size());for(var state:meta.palette())strings.write(out,state.toString());out.writeInt(meta.regions().size());for(var r:meta.regions()){strings.write(out,r.name());vector(out,r.min());vector(out,r.size());vector(out,r.anchor());}
            out.writeInt(meta.warnings().size());for(var w:meta.warnings())strings.write(out,w);
            out.writeInt(image.sections().size());for(var e:image.sections().entrySet()){section(out,e.getKey());out.writeInt(e.getValue().size());for(var cell:e.getValue().entrySet()){out.writeShort(cell.getKey());patch(out,cell.getValue());}}
            actions(out,image.undo());actions(out,image.redo());long checksum=crc.getValue();out.writeLong(checksum);
        }return bytes.toByteArray();
    }
    public static void write(Path file,Draft draft)throws IOException{
        byte[] bytes=encode(draft);Path target=file.toAbsolutePath().normalize();Files.createDirectories(target.getParent());Path temporary=Files.createTempFile(target.getParent(),"draft-",".part");
        try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    public static void clear(Path file)throws IOException{Files.deleteIfExists(file);}
    private static int bounded(int value,int min,int max,String label)throws IOException{if(value<min||value>max)throw new IOException("Invalid draft "+label);return value;}
    private static final class Strings {
        long remaining=16L<<20;
        void account(String value)throws IOException{remaining-=64L+value.length()*2L;if(remaining<0)throw new IOException("Draft text budget");}
        String read(DataInput in)throws IOException{String value=in.readUTF();account(value);return value;}
        void write(DataOutput out,String value)throws IOException{account(value);out.writeUTF(value);}
    }
    private static Vec3i vector(DataInput in)throws IOException{return new Vec3i(in.readInt(),in.readInt(),in.readInt());}
    private static void vector(DataOutput out,Vec3i v)throws IOException{out.writeInt(v.x());out.writeInt(v.y());out.writeInt(v.z());}
    private static SectionKey section(DataInput in)throws IOException{return new SectionKey(in.readInt(),in.readInt(),in.readInt(),in.readInt());}
    private static void section(DataOutput out,SectionKey k)throws IOException{out.writeInt(k.region());out.writeInt(k.x());out.writeInt(k.y());out.writeInt(k.z());}
    private static SchematicEdits.Patch patch(DataInput in)throws IOException{int flags=in.readUnsignedByte();if(flags==0)return null;if((flags&~15)!=0||(flags&1)==0)throw new IOException("Invalid draft patch flags");return new SchematicEdits.Patch(in.readInt(),in.readInt(),(flags&2)!=0,(flags&4)!=0,(flags&8)!=0);}
    private static void patch(DataOutput out,SchematicEdits.Patch p)throws IOException{if(p==null){out.writeByte(0);return;}out.writeByte(1|(p.blockData()?2:0)|(p.blockTicks()?4:0)|(p.fluidTicks()?8:0));out.writeInt(p.original());out.writeInt(p.state());}
    private static List<SchematicEdits.Action> actions(DataInput in,int[] budget)throws IOException{
        int count=bounded(in.readInt(),0,128,"actions");budget[0]+=count;if(budget[0]>128)throw new IOException("Draft action budget");var actions=new ArrayList<SchematicEdits.Action>(count);
        for(int i=0;i<count;i++){int n=bounded(in.readInt(),1,SchematicEdits.MAX_TRANSACTION,"action cells");budget[1]+=n;if(budget[1]>SchematicEdits.MAX_HISTORY_CELLS)throw new IOException("Draft history budget");var changes=new ArrayList<SchematicEdits.Delta>(n);for(int j=0;j<n;j++){var address=new SchematicEdits.Address(section(in),in.readUnsignedShort());changes.add(new SchematicEdits.Delta(address,patch(in),patch(in)));}actions.add(new SchematicEdits.Action(changes));}return List.copyOf(actions);
    }
    private static void actions(DataOutput out,List<SchematicEdits.Action> actions)throws IOException{out.writeInt(actions.size());for(var action:actions){out.writeInt(action.values().size());for(var change:action.values()){section(out,change.address().section());out.writeShort(change.address().cell());patch(out,change.before());patch(out,change.after());}}}
}
