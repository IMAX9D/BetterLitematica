package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** File metadata read without allocating source block arrays; edits always produce a separate file. */
public record SchematicFileInfo(String name,String author,String description,int dataVersion,long blocks,long volume,int regions,int[] preview) {
    public SchematicFileInfo {preview=preview.clone();}
    @Override public int[] preview(){return preview.clone();}
    private static final Set<String> HEADER=Set.of("Version","MinecraftDataVersion","DataVersion","Metadata","Regions","Width","Height","Length","size","Schematic");
    public static SchematicFileInfo read(Path source,Cancellation cancel)throws IOException{
        var root=NbtReader.readPartial(source,new NbtReader.Limits(512L<<20,8L<<20,64,4_000_000),cancel,(path,type)->{
            if(path.size()==1)return !HEADER.contains(path.get(0));
            int start=path.get(0).equals("Schematic")?1:0;if(path.size()<=start)return false;String first=path.get(start);
            if(first.equals("Metadata"))return false;
            if(first.equals("Regions"))return path.size()>start+2&&!Set.of("Size","Position","x","y","z").contains(path.get(path.size()-1));
            return !HEADER.contains(first);
        });
        if(root.containsKey("Schematic"))root=NbtReader.compound(root.get("Schematic"),"Schematic");var metadata=root.containsKey("Metadata")?NbtReader.compound(root.get("Metadata"),"Metadata"):Map.<String,Object>of();int count=root.get("Regions") instanceof Map<?,?> map?map.size():1;
        int[] pixels=metadata.get("PreviewImageData") instanceof int[] p?p:new int[0];int side=(int)Math.sqrt(pixels.length);if(pixels.length>1_048_576||side*side!=pixels.length)throw new IOException("无效投影预览图");
        return new SchematicFileInfo(NbtReader.string(metadata,"Name",source.getFileName().toString()),NbtReader.string(metadata,"Author",""),NbtReader.string(metadata,"Description",""),(int)number(root,root.containsKey("MinecraftDataVersion")?"MinecraftDataVersion":"DataVersion",1343),number(metadata,"TotalBlocks",-1),number(metadata,"TotalVolume",-1),count,pixels);
    }
    private static long number(Map<String,Object> data,String key,long fallback)throws IOException{Object value=data.get(key);if(value==null)return fallback;if(!(value instanceof Number number))throw new IOException("Invalid metadata "+key);return number.longValue();}
    public static void export(Path source,Path output,String format,String name,String author,String description,int[] preview,Cancellation cancel)throws IOException{
        if(name==null||name.isBlank()||name.length()>120||author==null||author.length()>120||description==null||description.length()>2048)throw new IOException("元信息长度无效");
        if(!Set.of("litematic","schem","nbt").contains(format))throw new IOException("无效输出格式");
        Map<String,Object> root=NbtReader.read(source,SchematicImporter.SOURCE_LIMITS,cancel);
        boolean same=format.equals("litematic")&&root.containsKey("Regions")||format.equals("nbt")&&root.containsKey("size")&&root.containsKey("blocks")||format.equals("schem")&&(root.containsKey("Schematic")||root.containsKey("Palette")&&root.containsKey("BlockData"));
        if(!same){if(format.equals("litematic"))root=SourceVersions.litematic(SchematicFormats.canonical(root,cancel),cancel);else root=new LinkedHashMap<>(SchematicFormats.exportSingle(SchematicDocument.takeSource(root,cancel),format,cancel));}
        Map<String,Object> target=root;if(root.containsKey("Schematic")){target=NbtReader.compound(root.get("Schematic"),"Schematic");root.put("Schematic",target);}
        var metadata=target.containsKey("Metadata")?NbtReader.compound(target.get("Metadata"),"Metadata"):new LinkedHashMap<String,Object>();metadata.put("Name",name);metadata.put("Author",author);metadata.put("Description",description);metadata.put("TimeModified",System.currentTimeMillis());
        if(preview!=null){int side=(int)Math.sqrt(preview.length);if(preview.length>1_048_576||side*side!=preview.length)throw new IOException("Invalid preview");metadata.put("PreviewImageData",preview.clone());}target.put("Metadata",metadata);NbtWriter.writeNew(output,root,cancel);
    }
}
