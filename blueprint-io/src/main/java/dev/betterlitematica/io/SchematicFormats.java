package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.util.*;

/** Independent structure/Sponge interoperability. Keeps entity and block-entity payloads. */
public final class SchematicFormats {
    private SchematicFormats(){}
    public static Map<String,Object> canonical(Map<String,Object> root,Cancellation cancel)throws IOException{
        if(root.containsKey("Regions"))return root;
        if(root.get("Blocks") instanceof byte[]&&root.containsKey("Data"))return LegacySchematic.canonical(root,cancel);
        if(root.containsKey("size")&&root.containsKey("blocks"))return structure(root,cancel);
        return sponge(root,cancel);
    }
    private static Map<String,Object> structure(Map<String,Object> root,Cancellation cancel)throws IOException{
        Vec3i size=listVector(root.get("size"));int volume=volume(size);List<BlockStateSpec> palette=new ArrayList<>();
        if(!(root.get("palette") instanceof List<?> raw)||raw.isEmpty()||raw.size()>65535)throw new IOException("Invalid structure palette");
        for(Object value:raw)palette.add(state(NbtReader.compound(value,"palette")));int air=palette.indexOf(BlockStateSpec.AIR);if(air<0){air=palette.size();palette.add(BlockStateSpec.AIR);}
        int[] blocks=new int[volume];Arrays.fill(blocks,air);BitSet seen=new BitSet(volume);List<Map<String,Object>> blockEntities=new ArrayList<>(),entities=new ArrayList<>();
        if(!(root.get("blocks") instanceof List<?> data))throw new IOException("Missing structure blocks");
        for(Object value:data){cancel.check();var tag=NbtReader.compound(value,"block");Vec3i pos=listVector(tag.get("pos"));int index=index(pos,size);if(seen.get(index))throw new IOException("Duplicate structure block");seen.set(index);int id=NbtReader.integer(tag,"state");if(id<0||id>=raw.size())throw new IOException("Invalid structure state");blocks[index]=id;
            if(tag.containsKey("nbt")){var nbt=NbtReader.compound(tag.get("nbt"),"block nbt");nbt.putAll(LitematicExport.vector(pos));blockEntities.add(nbt);}
        }
        for(var tag:tags(root,"entities")){var nbt=NbtReader.compound(tag.get("nbt"),"entity nbt");nbt.put("Pos",tag.get("pos"));entities.add(nbt);}
        var capture=new LitematicExport.Capture(new Region("main",Vec3i.ZERO,size),palette,blocks,blockEntities,entities,List.of(),List.of());
        return LitematicExport.create("Imported structure","BetterLitematica",NbtReader.integer(root,"DataVersion"),Vec3i.ZERO,List.of(capture));
    }
    private static Map<String,Object> sponge(Map<String,Object> input,Cancellation cancel)throws IOException{
        var root=input.containsKey("Schematic")?NbtReader.compound(input.get("Schematic"),"Schematic"):input;
        int version=NbtReader.integer(root,"Version");if(version!=2&&version!=3)throw new IOException("Supported formats: litematic v5/v6, Sponge v2/v3, vanilla structure");
        Vec3i size=new Vec3i(dimension(root,"Width"),dimension(root,"Height"),dimension(root,"Length"));long cells=(long)size.x()*size.y()*size.z();if(size.x()<1||size.y()<1||size.z()<1||cells>SchematicImporter.MAX_CELLS)throw new IOException("Sponge volume exceeds limit");int volume=Math.toIntExact(cells);
        Vec3i offset=Vec3i.ZERO;if(root.containsKey("Offset")){if(!(root.get("Offset") instanceof int[] a)||a.length!=3)throw new IOException("Invalid Sponge offset");offset=new Vec3i(a[0],a[1],a[2]);}
        var data=version==3?NbtReader.compound(root.get("Blocks"),"Blocks"):root;var rawPalette=NbtReader.compound(data.get("Palette"),"Palette");
        if(rawPalette.isEmpty()||rawPalette.size()>65536)throw new IOException("Palette limit");List<BlockStateSpec> palette=new ArrayList<>();Map<Integer,Integer> remap=new HashMap<>();
        for(var entry:rawPalette.entrySet()){int key=NbtReader.integer(rawPalette,entry.getKey());if(key<0||remap.put(key,palette.size())!=null)throw new IOException("Invalid palette id");palette.add(BlockStateSpec.parse(entry.getKey()));}
        if(!(data.get(version==3?"Data":"BlockData") instanceof byte[] bytes))throw new IOException("Missing block data");var blocks=new PackedOutput(Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1)),volume,bytes.length);int cursor=0;
        for(int i=0;i<volume;i++){if((i&4095)==0)cancel.check();int value=0;boolean finished=false;for(int shift=0;shift<35;shift+=7){if(cursor>=bytes.length)throw new EOFException("Truncated varint");int b=bytes[cursor++]&255;if(shift==28&&(b&0xf8)!=0)throw new IOException("Varint overflow");value|=(b&127)<<shift;if((b&128)==0){finished=true;break;}}if(!finished||!remap.containsKey(value))throw new IOException("Invalid block index");blocks.put(i,remap.get(value));}
        if(cursor!=bytes.length)throw new IOException("Trailing block data");
        List<Map<String,Object>> blockEntities=new ArrayList<>(),entities=new ArrayList<>();
        String beKey=version==3?"BlockEntities":root.containsKey("BlockEntities")?"BlockEntities":"TileEntities";
        for(var tag:tags(data,beKey)){Map<String,Object> nbt=version==3?NbtReader.compound(tag.get("Data"),"Data"):new LinkedHashMap<>(tag);Vec3i pos=intVector(tag.get("Pos"));index(pos,size);nbt.remove("Pos");nbt.remove("Id");nbt.putAll(LitematicExport.vector(pos));nbt.put("id",NbtReader.string(tag,"Id",NbtReader.string(nbt,"id","")));blockEntities.add(nbt);}
        for(var tag:tags(root,"Entities")){Map<String,Object> nbt=version==3?NbtReader.compound(tag.get("Data"),"Data"):new LinkedHashMap<>(tag);nbt.put("id",NbtReader.string(tag,"Id",NbtReader.string(nbt,"id","")));nbt.put("Pos",tag.get("Pos"));nbt.remove("Id");entities.add(nbt);}
        var meta=root.containsKey("Metadata")?NbtReader.compound(root.get("Metadata"),"Metadata"):Map.<String,Object>of();
        var region=new LinkedHashMap<String,Object>();region.put("Position",LitematicExport.vector(offset));region.put("Size",LitematicExport.vector(size));region.put("BlockStatePalette",palette.stream().map(SchematicFormats::stateTag).toList());region.put("BlockStates",blocks.words);region.put("TileEntities",blockEntities);region.put("Entities",entities);
        var result=new LinkedHashMap<String,Object>();result.put("Version",6);result.put("MinecraftDataVersion",NbtReader.integer(root,"DataVersion"));result.put("Regions",Map.of("main",region));result.put("Metadata",new LinkedHashMap<>(meta));return result;
    }
    public static Map<String,Object> exportSingle(SchematicDocument document,String format)throws IOException{
        return exportSingle(document,format,Cancellation.THREAD);
    }
    public static Map<String,Object> exportSingle(SchematicDocument document,String format,Cancellation cancel)throws IOException{
        if(document.parts().size()!=1)throw new IOException("此格式出口当前要求单个区域；多区域请保存 .litematic");
        var part=document.parts().get(0);var size=part.region().size();int volume=Math.toIntExact(part.region().volume());
        if(format.equals("nbt")){
            if(volume>262144)throw new IOException("结构 NBT 导出上限 262144 格");List<Map<String,Object>> palette=new ArrayList<>(),blocks=new ArrayList<>(),entities=new ArrayList<>();
            for(var state:part.palette())palette.add(stateTag(state));
            for(int i=0;i<volume;i++){if((i&4095)==0)cancel.check();Map<String,Object> tag=new LinkedHashMap<>();tag.put("pos",List.of(i%size.x(),i/(size.x()*size.z()),(i/size.x())%size.z()));tag.put("state",part.blocks().get(i));if(part.blockEntities().containsKey(i))tag.put("nbt",part.blockEntities().get(i));blocks.add(tag);}
            for(var entity:part.entities()){var pos=(List<?>)entity.get("Pos");entities.add(Map.of("pos",pos,"blockPos",List.of((int)Math.floor(((Number)pos.get(0)).doubleValue()),(int)Math.floor(((Number)pos.get(1)).doubleValue()),(int)Math.floor(((Number)pos.get(2)).doubleValue())),"nbt",entity));}
            return Map.of("DataVersion",document.dataVersion(),"size",List.of(size.x(),size.y(),size.z()),"palette",palette,"blocks",blocks,"entities",entities);
        }
        if(!format.equals("schem"))throw new IOException("Unknown export format");
        if(size.x()>65535||size.y()>65535||size.z()>65535)throw new IOException("Sponge dimension limit");
        Map<String,Object> palette=new LinkedHashMap<>();int[] remap=new int[part.palette().size()];for(int i=0;i<remap.length;i++){String key=part.palette().get(i).toString();if(!palette.containsKey(key))palette.put(key,palette.size());remap[i]=(Integer)palette.get(key);}
        long byteCount=0;for(int i=0;i<volume;i++){if((i&8191)==0)cancel.check();int value=remap[part.blocks().get(i)];byteCount+=value<128?1:value<16384?2:3;if(byteCount>512L<<20)throw new IOException("Sponge block data exceeds output budget");}int encodedLength=Math.toIntExact(byteCount);
        NbtWriter.StreamBytes bytes=new NbtWriter.StreamBytes(){public int length(){return encodedLength;}public void write(DataOutputStream out,Cancellation cancel)throws IOException{for(int i=0;i<volume;i++){if((i&8191)==0)cancel.check();int value=remap[part.blocks().get(i)];do{int b=value&127;value>>>=7;out.writeByte(b|(value==0?0:128));}while(value!=0);}}};
        List<Map<String,Object>> blockEntities=new ArrayList<>(),entities=new ArrayList<>();
        for(var tag:part.blockEntities().values()){var pos=SchematicDocument.vector(tag);var data=new LinkedHashMap<>(tag);data.remove("id");data.remove("x");data.remove("y");data.remove("z");blockEntities.add(Map.of("Id",tag.get("id"),"Pos",new int[]{pos.x(),pos.y(),pos.z()},"Data",data));}
        for(var tag:part.entities()){var data=new LinkedHashMap<>(tag);data.remove("id");data.remove("Pos");entities.add(Map.of("Id",tag.get("id"),"Pos",tag.get("Pos"),"Data",data));}
        var min=part.region().min();Map<String,Object> root=new LinkedHashMap<>();root.put("Version",3);root.put("DataVersion",document.dataVersion());root.put("Width",(short)size.x());root.put("Height",(short)size.y());root.put("Length",(short)size.z());root.put("Offset",new int[]{min.x(),min.y(),min.z()});root.put("Blocks",Map.of("Palette",palette,"Data",bytes,"BlockEntities",blockEntities));root.put("Entities",entities);return Map.of("Schematic",root);
    }
    private static int volume(Vec3i size)throws IOException{long n=(long)size.x()*size.y()*size.z();if(size.x()<1||size.y()<1||size.z()<1||n>4_194_304)throw new IOException("完整格式转换上限为 4194304 格");return(int)n;}
    private static int dimension(Map<String,Object> root,String key)throws IOException{Object value=root.get(key);if(!(value instanceof Short s))throw new IOException("Expected unsigned short");return s&65535;}
    private static int index(Vec3i pos,Vec3i size)throws IOException{if(pos.x()<0||pos.y()<0||pos.z()<0||pos.x()>=size.x()||pos.y()>=size.y()||pos.z()>=size.z())throw new IOException("Coordinate outside region");return pos.x()+pos.z()*size.x()+pos.y()*size.x()*size.z();}
    private static Vec3i intVector(Object value)throws IOException{if(!(value instanceof int[] p)||p.length!=3)throw new IOException("Expected int[3]");return new Vec3i(p[0],p[1],p[2]);}
    private static Vec3i listVector(Object value)throws IOException{if(!(value instanceof List<?> p)||p.size()!=3)throw new IOException("Expected vector list");try{return new Vec3i(((Number)p.get(0)).intValue(),((Number)p.get(1)).intValue(),((Number)p.get(2)).intValue());}catch(ClassCastException e){throw new IOException("Invalid vector",e);}}
    private static List<Map<String,Object>> tags(Map<String,Object> root,String name)throws IOException{if(!root.containsKey(name))return List.of();if(!(root.get(name) instanceof List<?> raw))throw new IOException("Expected list "+name);List<Map<String,Object>> result=new ArrayList<>();for(Object value:raw)result.add(NbtReader.compound(value,name));return result;}
    private static BlockStateSpec state(Map<String,Object> tag)throws IOException{Map<String,String> properties=new TreeMap<>();if(tag.containsKey("Properties"))for(var p:NbtReader.compound(tag.get("Properties"),"Properties").entrySet()){if(!(p.getValue() instanceof String text))throw new IOException("Invalid state property");properties.put(p.getKey(),text);}return new BlockStateSpec(NbtReader.string(tag,"Name",""),properties);}
    private static Map<String,Object> stateTag(BlockStateSpec state){return state.properties().isEmpty()?Map.of("Name",state.name()):Map.of("Name",state.name(),"Properties",state.properties());}
}
