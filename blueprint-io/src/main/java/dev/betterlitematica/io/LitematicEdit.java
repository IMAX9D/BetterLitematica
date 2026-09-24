package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Edits the original lossless NBT tree, never the preview cache. Exports are always new files. */
public final class LitematicEdit {
    public record Result(long changed, long removedBlockEntities, long removedTicks) {}
    private LitematicEdit() {}
    public static Result replace(Path source, Path output, BlockStateSpec from, BlockStateSpec to,
                                 boolean matchBlockType, Vec3i onlyPosition, Cancellation cancel) throws IOException {
        Map<String,Object> root=SourceVersions.litematic(SchematicFormats.canonical(NbtReader.read(source,SchematicImporter.SOURCE_LIMITS,cancel),cancel),cancel);
        int version=NbtReader.integer(root,"Version");if(version!=5&&version!=6)throw new IOException("不支持的 litematic 版本");
        var regions=NbtReader.compound(root.get("Regions"),"Regions");
        if(regions.size()>1024)throw new IOException("区域数量超限");
        long changed=0,removedEntities=0,removedTicks=0,totalBlocks=0,totalVolume=0;
        for(var regionEntry:regions.entrySet()){
            cancel.check();var tag=NbtReader.compound(regionEntry.getValue(),"Region");Vec3i signed=vector(tag.get("Size")),position=vector(tag.get("Position"));
            int sx=Math.toIntExact(Math.abs((long)signed.x())),sy=Math.toIntExact(Math.abs((long)signed.y())),sz=Math.toIntExact(Math.abs((long)signed.z()));
            Vec3i min=position.add(new Vec3i(signed.x()<0?signed.x()+1:0,signed.y()<0?signed.y()+1:0,signed.z()<0?signed.z()+1:0));
            Region region=new Region(regionEntry.getKey(),min,new Vec3i(sx,sy,sz));int volume=Math.toIntExact(region.volume());
            totalVolume+=volume;if(totalVolume>SchematicImporter.MAX_CELLS)throw new IOException("编辑体积超过预算");
            if(!(tag.get("BlockStatePalette") instanceof List<?> original)||original.isEmpty()||original.size()>65536)throw new IOException("Invalid palette");
            List<Object> paletteTags=new ArrayList<>(original);List<BlockStateSpec> palette=new ArrayList<>();
            for(Object value:original){var state=NbtReader.compound(value,"State");Map<String,String> props=new TreeMap<>();if(state.containsKey("Properties"))for(var prop:NbtReader.compound(state.get("Properties"),"Properties").entrySet()){if(!(prop.getValue() instanceof String text))throw new IOException("Invalid property");props.put(prop.getKey(),text);}palette.add(new BlockStateSpec(NbtReader.string(state,"Name",""),props));}
            int oldBits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));
            if(!(tag.get("BlockStates") instanceof long[] words)||words.length!=PackedBits.wordCount(oldBits,volume))throw new IOException("Invalid block states");
            boolean[] changedPalette=new boolean[palette.size()];
            for(int id=0;id<palette.size();id++){var state=palette.get(id);changedPalette[id]=!state.equals(to)&&(from==null||(matchBlockType?state.name().equals(from.name()):state.equals(from)));}
            int cell=-1;
            if(onlyPosition!=null){long x=(long)onlyPosition.x()-min.x(),y=(long)onlyPosition.y()-min.y(),z=(long)onlyPosition.z()-min.z();if(x>=0&&y>=0&&z>=0&&x<sx&&y<sy&&z<sz)cell=(int)(x+z*sx+y*sx*sz);}
            long changedHere=0;
            // Whole-file replacement changes palette entries, not hundreds of millions of packed cells.
            for(int i=0;i<volume;i++){
                if((i&8191)==0)cancel.check();int id=get(words,oldBits,i);if(id>=palette.size())throw new IOException("Palette index out of range");
                boolean modify=changedPalette[id]&&(onlyPosition==null||i==cell);
                if(modify)changedHere++;if(!(modify?to:palette.get(id)).isAir())totalBlocks++;
            }
            changed+=changedHere;
            for(String key:List.of("TileEntities","PendingBlockTicks","PendingFluidTicks")){
                if(!(tag.get(key) instanceof List<?> list))continue;List<Object> retained=new ArrayList<>();
                for(Object value:list){cancel.check();var data=NbtReader.compound(value,key);int x=NbtReader.integer(data,"x"),y=NbtReader.integer(data,"y"),z=NbtReader.integer(data,"z");
                    int at=x>=0&&y>=0&&z>=0&&x<sx&&y<sy&&z<sz?x+z*sx+y*sx*sz:-1;
                    boolean remove=false;
                    if(at>=0&&changedPalette[get(words,oldBits,at)]&&(onlyPosition==null||at==cell)){
                        var before=palette.get(get(words,oldBits,at));
                        if(before.name().equals("minecraft:moving_piston")&&to.name().equals(before.name()))throw new IOException("运动活塞请先替换为普通方块");
                        remove=key.equals("PendingFluidTicks")?!fluid(before).equals(fluid(to)):!before.name().equals(to.name());
                    }
                    if(remove){if(key.equals("TileEntities"))removedEntities++;else removedTicks++;}else retained.add(value);
                }tag.put(key,retained);
            }
            if(changedHere>0){
                var replacement=new LinkedHashMap<String,Object>();replacement.put("Name",to.name());if(!to.properties().isEmpty())replacement.put("Properties",to.properties());
                if(onlyPosition==null){for(int id=0;id<changedPalette.length;id++)if(changedPalette[id])paletteTags.set(id,replacement);}
                else{
                    int target=palette.indexOf(to);
                    if(target<0){if(palette.size()==65536)throw new IOException("调色板已满");target=palette.size();paletteTags.add(replacement);}
                    int bits=Math.max(2,32-Integer.numberOfLeadingZeros(paletteTags.size()-1));
                    if(bits!=oldBits){long[] expanded=new long[PackedBits.wordCount(bits,volume)];for(int i=0;i<volume;i++){if((i&8191)==0)cancel.check();set(expanded,bits,i,get(words,oldBits,i));}words=expanded;}
                    // This mutable NBT tree owns its array exclusively; no PackedBits object aliases it.
                    set(words,bits,cell,target);tag.put("BlockStates",words);
                }
                tag.put("BlockStatePalette",paletteTags);
            }
            regionEntry.setValue(tag);
        }
        root.put("Regions",regions);var metadata=root.containsKey("Metadata")?NbtReader.compound(root.get("Metadata"),"Metadata"):new LinkedHashMap<String,Object>();
        metadata.put("TimeModified",System.currentTimeMillis());metadata.put("TotalBlocks",Math.toIntExact(totalBlocks));metadata.put("TotalVolume",Math.toIntExact(totalVolume));root.put("Metadata",metadata);
        NbtWriter.writeNew(output,root,cancel);return new Result(changed,removedEntities,removedTicks);
    }
    /** Vanilla fluid identities are independent of level, facing and other ordinary properties. */
    private static String fluid(BlockStateSpec state)throws IOException{
        if("true".equals(state.properties().get("waterlogged")))return "water";
        return switch(state.name()){
            case "minecraft:water","minecraft:bubble_column","minecraft:kelp","minecraft:kelp_plant","minecraft:seagrass","minecraft:tall_seagrass"->"water";
            case "minecraft:lava"->"lava";
            default->{if(!state.name().startsWith("minecraft:"))throw new IOException("无法确认该模组方块的流体计划刻："+state.name());yield "empty";}
        };
    }
    private static int get(long[] words,int bits,int index){long bit=(long)index*bits;int word=(int)(bit>>>6),shift=(int)(bit&63);long value=words[word]>>>shift;if(shift+bits>64)value|=words[word+1]<<(64-shift);return (int)(value&((1L<<bits)-1));}
    private static void set(long[] words,int bits,int index,int value){long bit=(long)index*bits,mask=(1L<<bits)-1;int word=(int)(bit>>>6),shift=(int)(bit&63);words[word]=(words[word]&~(mask<<shift))|((long)value<<shift);if(shift+bits>64){int high=shift+bits-64;long highMask=(1L<<high)-1;words[word+1]=(words[word+1]&~highMask)|((long)value>>>(64-shift));}}
    private static Vec3i vector(Object tag)throws IOException{var data=NbtReader.compound(tag,"Position/Size");return new Vec3i(NbtReader.integer(data,"x"),NbtReader.integer(data,"y"),NbtReader.integer(data,"z"));}
}
