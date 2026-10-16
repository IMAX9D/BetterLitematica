package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.util.*;

/** Version adapters supply data fixes; the file modules have no game dependency. */
public final class SourceVersions {
    public enum Kind {STATE,BLOCK_ENTITY,ENTITY}
    public interface Adapter {
        int version();String cacheKey();
        default boolean compatibleLoading(){return false;}
        default boolean known(Kind kind,String name){return true;}
        default boolean supported(BlockStateSpec state){return true;}
        Map<String,Object> fix(Kind kind,Map<String,Object> value,int sourceVersion)throws IOException;
        Map<String,Object> legacy(int id,int metadata,String name)throws IOException;
    }
    private static volatile Adapter adapter;
    private SourceVersions(){}
    public static void install(Adapter value){adapter=Objects.requireNonNull(value);}
    public static String cacheKey(){var a=adapter;return a==null?"raw":a.cacheKey();}
    public static boolean compatibleLoading(){var a=adapter;return a!=null&&a.compatibleLoading();}
    public static String previewKey(String sourceKey){
        if(!compatibleLoading())return sourceKey;
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((sourceKey+"|"+cacheKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static int gameVersion(){var a=adapter;return a==null?0:a.version();}
    public static void requireLitematicVersion(int version)throws IOException{
        if(version==5||version==6||compatibleLoading()&&version>=5)return;
        throw new IOException("无法读取此投影格式版本："+version);
    }
    /** Structure void is a no-op cell, never expected air which could trigger excavation. */
    public static final BlockStateSpec SKIPPED=BlockStateSpec.parse("minecraft:structure_void");
    public record ReadState(BlockStateSpec state,boolean skipped){}
    public static boolean skipUnknown(Kind kind,String name,int source){
        var a=adapter;return a!=null&&a.compatibleLoading()&&source>=a.version()&&!a.known(kind,name);
    }
    public static ReadState readState(Map<String,Object> tag,int source)throws IOException{
        String name=NbtReader.string(tag,"Name","");
        // Do not attempt to interpret a future block's properties in an older game.
        if(skipUnknown(Kind.STATE,name,source))return new ReadState(SKIPPED,true);
        var properties=new TreeMap<String,String>();
        if(tag.containsKey("Properties"))for(var entry:NbtReader.compound(tag.get("Properties"),"Properties").entrySet()){
            if(!(entry.getValue() instanceof String text))throw new IOException("Invalid block property");properties.put(entry.getKey(),text);
        }
        var fixed=state(new BlockStateSpec(name,properties),source);var a=adapter;
        boolean skipped=a!=null&&a.compatibleLoading()&&!a.supported(fixed);
        return new ReadState(skipped?SKIPPED:fixed,skipped);
    }
    public static ReadState readState(BlockStateSpec state,int source)throws IOException{
        return readState(Map.of("Name",state.name(),"Properties",state.properties()),source);
    }
    public static boolean acceptsAuxiliary(Kind kind,Map<String,Object> tag,int source){
        Object id=tag.get("id");return !(id instanceof String name)||!skipUnknown(kind,name,source);
    }
    /** Palette-only check prevents an embedded preview from reintroducing discarded blocks. */
    public static boolean requiresFiltering(Map<String,Object> root,Cancellation cancel)throws IOException{
        if(!compatibleLoading())return false;
        root=SchematicFormats.canonical(root,cancel);int source=NbtReader.integer(root,"MinecraftDataVersion");
        for(var region:NbtReader.compound(root.get("Regions"),"Regions").values()){
            var tag=NbtReader.compound(region,"Region");
            if(!(tag.get("BlockStatePalette") instanceof List<?> palette))throw new IOException("Invalid palette");
            for(var value:palette){cancel.check();if(readState(NbtReader.compound(value,"State"),source).skipped())return true;}
        }
        return false;
    }
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
