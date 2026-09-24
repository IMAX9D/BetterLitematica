package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Merge a frozen region-qualified draft into the complete source tree, only to a new file. */
public final class SchematicPatchIO {
    private SchematicPatchIO(){}
    public record Source(Path path,boolean temporary) implements AutoCloseable {
        @Override public void close()throws IOException{if(temporary)Files.deleteIfExists(path);}
    }
    public static Source open(Path source,SchematicEdits.Snapshot patch,Path directory,Cancellation cancel)throws IOException{
        if(patch==null||patch.sections().isEmpty())return new Source(source,false);
        Files.createDirectories(directory);Path output=directory.resolve(UUID.randomUUID()+".litematic");
        try{write(source,output,patch,cancel);return new Source(output,true);}catch(IOException|RuntimeException e){Files.deleteIfExists(output);throw e;}
    }
    public static void write(Path source,Path output,SchematicEdits.Snapshot patch,Cancellation cancel)throws IOException{
        if(!SchematicImporter.sha256(source,cancel).equals(patch.metadata().sourceSha256()))throw new IOException("投影文件已被外部修改，请保留草稿后重新载入");
        var root=SourceVersions.litematic(SchematicFormats.canonical(NbtReader.read(source,SchematicImporter.SOURCE_LIMITS,cancel),cancel),cancel);
        if(!SchematicImporter.sha256(source,cancel).equals(patch.metadata().sourceSha256()))throw new IOException("读取时投影文件已改变");
        var regions=NbtReader.compound(root.get("Regions"),"Regions");long blockDelta=0;
        var grouped=new TreeMap<Integer,TreeMap<Integer,SchematicEdits.Patch>>();
        for(var entry:patch.sections().entrySet()){
            var key=entry.getKey();var region=patch.metadata().regions().get(key.region());var target=grouped.computeIfAbsent(key.region(),k->new TreeMap<>());
            for(var value:entry.getValue().entrySet()){int cell=value.getKey();int x=key.x()*16+(cell&15),y=key.y()*16+(cell>>>8),z=key.z()*16+((cell>>>4)&15);if(x<0||y<0||z<0||x>=region.size().x()||y>=region.size().y()||z>=region.size().z())throw new IOException("编辑位置越界");int index=Math.toIntExact(x+(long)z*region.size().x()+(long)y*region.size().x()*region.size().z());target.put(index,value.getValue());}
        }
        for(var part:grouped.entrySet()){
            cancel.check();var expected=patch.metadata().regions().get(part.getKey());var tag=NbtReader.compound(regions.get(expected.name()),"Region");
            var signed=SchematicDocument.vector(tag.get("Size"));var anchor=SchematicDocument.vector(tag.get("Position"));var size=new Vec3i(Math.abs(signed.x()),Math.abs(signed.y()),Math.abs(signed.z()));var min=anchor.add(new Vec3i(signed.x()<0?signed.x()+1:0,signed.y()<0?signed.y()+1:0,signed.z()<0?signed.z()+1:0));
            if(!expected.equals(new Region(expected.name(),min,size,anchor)))throw new IOException("子区域范围已改变");
            if(!(tag.get("BlockStatePalette") instanceof List<?> original)||original.isEmpty()||original.size()>65536)throw new IOException("无效方块调色板");
            var palette=new ArrayList<BlockStateSpec>();var paletteTags=new ArrayList<Object>(original);var ids=new HashMap<BlockStateSpec,Integer>();
            for(var item:original){var state=state(item);ids.putIfAbsent(state,palette.size());palette.add(state);}
            int oldBits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1)),volume=Math.toIntExact(expected.volume());
            if(!(tag.get("BlockStates") instanceof long[] words)||words.length!=PackedBits.wordCount(oldBits,volume))throw new IOException("无效方块数组");
            var values=PackedBits.takeOwnership(oldBits,volume,words);var changes=new TreeMap<Integer,Integer>();
            for(var edit:part.getValue().entrySet()){
                cancel.check();var cell=edit.getValue();int old=values.get(edit.getKey());var before=patch.metadata().palette().get(cell.original());var after=patch.metadata().palette().get(cell.state());
                if(old>=palette.size()||!palette.get(old).equals(before))throw new IOException("编辑源状态不匹配");
                Integer id=ids.get(after);if(id==null){if(paletteTags.size()>=65536)throw new IOException("方块调色板已满");id=paletteTags.size();ids.put(after,id);var state=new LinkedHashMap<String,Object>();state.put("Name",after.name());if(!after.properties().isEmpty())state.put("Properties",after.properties());paletteTags.add(state);}
                changes.put(edit.getKey(),id);blockDelta+=(after.isAir()?0:1)-(before.isAir()?0:1);
            }
            int bits=Math.max(2,32-Integer.numberOfLeadingZeros(paletteTags.size()-1));
            tag.put("BlockStatePalette",paletteTags);tag.put("BlockStates",packed(values,bits,changes));
            filter(tag,"TileEntities",size,part.getValue(),0);filter(tag,"PendingBlockTicks",size,part.getValue(),1);filter(tag,"PendingFluidTicks",size,part.getValue(),2);regions.put(expected.name(),tag);
        }
        root.put("Regions",regions);var metadata=root.containsKey("Metadata")?NbtReader.compound(root.get("Metadata"),"Metadata"):new LinkedHashMap<String,Object>();
        if(metadata.get("TotalBlocks") instanceof Number value)metadata.put("TotalBlocks",Math.toIntExact(value.longValue()+blockDelta));metadata.put("TimeModified",System.currentTimeMillis());root.put("Metadata",metadata);
        NbtWriter.writeNew(output,root,cancel);
    }
    private static BlockStateSpec state(Object value)throws IOException{
        var tag=NbtReader.compound(value,"BlockState");var properties=new TreeMap<String,String>();if(tag.containsKey("Properties"))for(var entry:NbtReader.compound(tag.get("Properties"),"Properties").entrySet()){if(!(entry.getValue() instanceof String text))throw new IOException("无效方块属性");properties.put(entry.getKey(),text);}return new BlockStateSpec(NbtReader.string(tag,"Name",""),properties);
    }
    private static void filter(Map<String,Object> tag,String name,Vec3i size,Map<Integer,SchematicEdits.Patch> changes,int type)throws IOException{
        if(!(tag.get(name) instanceof List<?> list))return;var retained=new ArrayList<Object>();
        for(var item:list){var pos=SchematicDocument.vector(item);SchematicEdits.Patch change=null;if(pos.x()>=0&&pos.y()>=0&&pos.z()>=0&&pos.x()<size.x()&&pos.y()<size.y()&&pos.z()<size.z())change=changes.get(pos.x()+pos.z()*size.x()+pos.y()*size.x()*size.z());
            if(change==null||switch(type){case 0->change.blockData();case 1->change.blockTicks();default->change.fluidTicks();})retained.add(item);
        }tag.put(name,retained);
    }
    private static NbtWriter.StreamLongs packed(PackedBits original,int bits,NavigableMap<Integer,Integer> changes){
        return new NbtWriter.StreamLongs(){public int length(){return PackedBits.wordCount(bits,original.size());}
            public void write(DataOutputStream out,Cancellation cancel)throws IOException{
                var edits=changes.entrySet().iterator();var edit=edits.hasNext()?edits.next():null;long word=0;int used=0;
                for(int index=0;index<original.size();index++){
                    if((index&8191)==0)cancel.check();int value;
                    if(edit!=null&&edit.getKey()==index){value=edit.getValue();edit=edits.hasNext()?edits.next():null;}else value=original.get(index);
                    word|=(long)value<<used;int total=used+bits;
                    if(total>=64){out.writeLong(word);word=total==64?0:((long)value>>>(64-used));used=total-64;}else used=total;
                }if(used!=0)out.writeLong(word);
            }};
    }
}
