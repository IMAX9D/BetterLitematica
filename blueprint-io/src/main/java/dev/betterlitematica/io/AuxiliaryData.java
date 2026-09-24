package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Full-fidelity small NBT branches; packed voxel arrays are streamed past without allocating them. */
public record AuxiliaryData(List<Part> parts) {
    public record Part(Map<Vec3i,Map<String,Object>> blocks,List<Map<String,Object>> entities){}
    public AuxiliaryData {parts=List.copyOf(parts);}
    public static AuxiliaryData read(Path path,BlueprintMetadata metadata,Cancellation cancel)throws IOException{
        var root=NbtReader.readPartial(path,new NbtReader.Limits(512L<<20,64L<<20,64,4_000_000),cancel,(branch,type)->omit(branch)||branch.size()==1&&branch.get(0).equals("Blocks")&&type==7);
        var result=new ArrayList<Part>();int sourceVersion=root.containsKey("MinecraftDataVersion")?NbtReader.integer(root,"MinecraftDataVersion"):root.containsKey("Schematic")?NbtReader.integer(NbtReader.compound(root.get("Schematic"),"Schematic"),"DataVersion"):root.containsKey("DataVersion")?NbtReader.integer(root,"DataVersion"):1343;
        if(root.containsKey("Regions")){
            var regions=NbtReader.compound(root.get("Regions"),"Regions");
            for(var region:metadata.regions()){var tag=NbtReader.compound(regions.get(region.name()),"Region");var blocks=new LinkedHashMap<Vec3i,Map<String,Object>>();for(var block:tags(tag,"TileEntities")){var p=SchematicDocument.vector(block);blocks.put(region.min().add(p),block);}result.add(new Part(Collections.unmodifiableMap(blocks),tags(tag,"Entities")));}
        }else if(root.containsKey("Materials")||!root.containsKey("Version")&&!root.containsKey("size")&&!root.containsKey("Schematic")){
            var region=metadata.regions().get(0);var blocks=new LinkedHashMap<Vec3i,Map<String,Object>>();for(var tag:tags(root,"TileEntities"))blocks.put(region.min().add(SchematicDocument.vector(tag)),tag);result.add(new Part(Collections.unmodifiableMap(blocks),tags(root,"Entities")));
        }else if(root.containsKey("size")){
            var region=metadata.regions().get(0);var blocks=new LinkedHashMap<Vec3i,Map<String,Object>>();for(var item:tags(root,"blocks",1_000_000)){if(!item.containsKey("nbt"))continue;var p=vectorList(item.get("pos"));blocks.put(region.min().add(p),NbtReader.compound(item.get("nbt"),"nbt"));}
            var entities=new ArrayList<Map<String,Object>>();for(var item:tags(root,"entities")){var tag=NbtReader.compound(item.get("nbt"),"nbt");tag.put("Pos",item.get("pos"));entities.add(tag);}result.add(new Part(Collections.unmodifiableMap(blocks),List.copyOf(entities)));
        }else{
            var tag=root.containsKey("Schematic")?NbtReader.compound(root.get("Schematic"),"Schematic"):root;int version=NbtReader.integer(tag,"Version");var holder=version==3?NbtReader.compound(tag.get("Blocks"),"Blocks"):tag;var region=metadata.regions().get(0);var blocks=new LinkedHashMap<Vec3i,Map<String,Object>>();
            for(var raw:tags(holder,holder.containsKey("BlockEntities")?"BlockEntities":"TileEntities")){var p=vectorArray(raw.get("Pos"));var data=version==3?NbtReader.compound(raw.get("Data"),"Data"):new LinkedHashMap<>(raw);data.remove("Pos");data.remove("Id");if(raw.containsKey("Id"))data.put("id",raw.get("Id"));blocks.put(region.min().add(p),data);}
            var entities=new ArrayList<Map<String,Object>>();for(var raw:tags(tag,"Entities")){var data=version==3?NbtReader.compound(raw.get("Data"),"Data"):new LinkedHashMap<>(raw);data.remove("Id");if(raw.containsKey("Id"))data.put("id",raw.get("Id"));data.put("Pos",raw.get("Pos"));entities.add(data);}result.add(new Part(Collections.unmodifiableMap(blocks),List.copyOf(entities)));
        }
        if(SourceVersions.version(sourceVersion)!=sourceVersion){var upgraded=new ArrayList<Part>();for(var part:result){var blocks=new LinkedHashMap<Vec3i,Map<String,Object>>();for(var e:part.blocks().entrySet()){cancel.check();blocks.put(e.getKey(),SourceVersions.fix(SourceVersions.Kind.BLOCK_ENTITY,e.getValue(),sourceVersion));}var entities=new ArrayList<Map<String,Object>>();for(var entity:part.entities()){cancel.check();entities.add(SourceVersions.fix(SourceVersions.Kind.ENTITY,entity,sourceVersion));}upgraded.add(new Part(Collections.unmodifiableMap(blocks),List.copyOf(entities)));}result=upgraded;}
        if(!SchematicImporter.sha256(path,cancel).equals(metadata.sourceSha256()))throw new IOException("Source changed while reading auxiliary data");
        return new AuxiliaryData(result);
    }
    private static boolean omit(List<String> p){String last=p.get(p.size()-1);if(p.size()==3&&p.get(0).equals("Regions"))return !Set.of("TileEntities","Entities").contains(last);if(p.size()==1)return Set.of("Metadata","palette","palettes","BlockData","Palette","Biomes","BiomeData","Data","AddBlocks").contains(last);if(p.size()==2&&p.get(0).equals("Schematic"))return Set.of("Metadata","Data","BlockData","Palette","Biomes").contains(last);if((p.size()==2&&p.get(0).equals("Blocks"))||(p.size()==3&&p.get(0).equals("Schematic")&&p.get(1).equals("Blocks")))return !last.equals("BlockEntities");return false;}
    private static List<Map<String,Object>> tags(Map<String,Object> tag,String key)throws IOException{return tags(tag,key,65536);}
    private static List<Map<String,Object>> tags(Map<String,Object> tag,String key,int limit)throws IOException{if(!tag.containsKey(key))return List.of();if(!(tag.get(key) instanceof List<?> list)||list.size()>limit)throw new IOException("Auxiliary entry budget exceeded");var result=new ArrayList<Map<String,Object>>();for(var v:list)result.add(NbtReader.compound(v,key));return List.copyOf(result);}
    private static Vec3i vectorArray(Object value)throws IOException{if(!(value instanceof int[] a)||a.length!=3)throw new IOException("Invalid block entity position");return new Vec3i(a[0],a[1],a[2]);}
    private static Vec3i vectorList(Object value)throws IOException{if(!(value instanceof List<?> list)||list.size()!=3||list.stream().anyMatch(v->!(v instanceof Number)))throw new IOException("Invalid structure position");return new Vec3i(((Number)list.get(0)).intValue(),((Number)list.get(1)).intValue(),((Number)list.get(2)).intValue());}
}
