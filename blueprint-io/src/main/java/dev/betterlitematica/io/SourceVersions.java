package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.util.*;

/** Version adapters supply data fixes; the file modules have no game dependency. */
public final class SourceVersions {
    public enum Kind {STATE,BLOCK_ENTITY,ENTITY}
    public interface Adapter {
        int version();String cacheKey();
        Map<String,Object> fix(Kind kind,Map<String,Object> value,int sourceVersion)throws IOException;
        Map<String,Object> legacy(int id,int metadata,String name)throws IOException;
    }
    private static volatile Adapter adapter;
    private SourceVersions(){}
    public static void install(Adapter value){adapter=Objects.requireNonNull(value);}
    public static String cacheKey(){var a=adapter;return a==null?"raw":a.cacheKey();}
    public static int version(int source){var a=adapter;return a==null?source:Math.max(source,a.version());}
    public static Map<String,Object> fix(Kind kind,Map<String,Object> value,int source)throws IOException{var a=adapter;return a==null||source>=a.version()?value:a.fix(kind,value,source);}
    public static Map<String,Object> legacy(int id,int metadata,String name)throws IOException{var a=adapter;if(a==null)throw new IOException("Legacy .schematic requires a Minecraft version adapter");return a.legacy(id,metadata,name);}
    public static BlockStateSpec state(BlockStateSpec value,int source)throws IOException{
        var input=new LinkedHashMap<String,Object>();input.put("Name",value.name());if(!value.properties().isEmpty())input.put("Properties",value.properties());var out=fix(Kind.STATE,input,source);var properties=new TreeMap<String,String>();if(out.containsKey("Properties"))for(var entry:NbtReader.compound(out.get("Properties"),"Properties").entrySet()){if(!(entry.getValue() instanceof String text))throw new IOException("Invalid upgraded state");properties.put(entry.getKey(),text);}return new BlockStateSpec(NbtReader.string(out,"Name",""),properties);
    }
    public static Map<String,Object> litematic(Map<String,Object> root,Cancellation cancel)throws IOException{
        int source=NbtReader.integer(root,"MinecraftDataVersion");if(source==version(source))return root;
        var regions=NbtReader.compound(root.get("Regions"),"Regions");for(var entry:regions.entrySet()){
            cancel.check();var tag=NbtReader.compound(entry.getValue(),"Region");
            for(String field:List.of("BlockStatePalette","TileEntities","Entities")){if(!(tag.get(field) instanceof List<?> values))continue;var next=new ArrayList<Map<String,Object>>(values.size());var kind=field.equals("BlockStatePalette")?Kind.STATE:field.equals("TileEntities")?Kind.BLOCK_ENTITY:Kind.ENTITY;for(var value:values){cancel.check();next.add(fix(kind,NbtReader.compound(value,field),source));}tag.put(field,next);}
            entry.setValue(tag);
        }
        root.put("Regions",regions);root.put("MinecraftDataVersion",version(source));return root;
    }
}
