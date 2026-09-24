package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.util.*;

/** Classic Alpha/Schematica numeric block arrays, with optional twelve-bit IDs. */
final class LegacySchematic {
    private LegacySchematic(){}
    static Map<String,Object> canonical(Map<String,Object> root,Cancellation cancel)throws IOException{
        int x=dimension(root,"Width"),y=dimension(root,"Height"),z=dimension(root,"Length");long volume=(long)x*y*z;if(volume>SchematicImporter.MAX_CELLS)throw new IOException("Legacy volume exceeds limit");int size=Math.toIntExact(volume);
        if(!(root.get("Blocks") instanceof byte[] blocks)||blocks.length!=size||!(root.get("Data") instanceof byte[] data)||data.length!=size)throw new IOException("Legacy block arrays do not cover their dimensions");
        byte[] extra=root.get("AddBlocks") instanceof byte[] a?a:null;if(extra!=null&&extra.length!=(size+1)/2)throw new IOException("Invalid AddBlocks length");
        var names=new HashMap<Integer,String>();if(root.containsKey("SchematicaMapping"))for(var entry:NbtReader.compound(root.get("SchematicaMapping"),"SchematicaMapping").entrySet()){int id=NbtReader.integer(Map.of("id",entry.getValue()),"id");if(id<0||id>4095||names.put(id,entry.getKey())!=null)throw new IOException("Invalid legacy block mapping");}
        if(!root.containsKey("SchematicaMapping")&&root.containsKey("BlockIDs"))for(var entry:NbtReader.compound(root.get("BlockIDs"),"BlockIDs").entrySet()){int id;try{id=Integer.parseInt(entry.getKey());}catch(NumberFormatException e){throw new IOException("Invalid BlockIDs key",e);}if(id<0||id>4095||!(entry.getValue() instanceof String name)||names.put(id,name)!=null)throw new IOException("Invalid BlockIDs mapping");}
        var palette=new ArrayList<Map<String,Object>>();var remap=new HashMap<Integer,Integer>();
        for(int i=0;i<size;i++){if((i&8191)==0)cancel.check();int key=state(blocks,data,extra,i);if(!remap.containsKey(key)){if(remap.size()>=65536)throw new IOException("Legacy palette limit");remap.put(key,palette.size());palette.add(SourceVersions.legacy(key>>>4,key&15,names.get(key>>>4)));}}
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));PackedBits packed;
        try{packed=PackedBits.generate(bits,size,i->{if((i&8191)==0)try{cancel.check();}catch(IOException e){throw new UncheckedIOException(e);}return remap.get(state(blocks,data,extra,i));});}catch(UncheckedIOException e){throw e.getCause();}
        var region=new LinkedHashMap<String,Object>();region.put("Position",LitematicExport.vector(Vec3i.ZERO));region.put("Size",LitematicExport.vector(new Vec3i(x,y,z)));region.put("BlockStatePalette",palette);region.put("BlockStates",packed.copyWords());region.put("TileEntities",root.getOrDefault("TileEntities",List.of()));region.put("Entities",root.getOrDefault("Entities",List.of()));
        var result=new LinkedHashMap<String,Object>();result.put("Version",6);result.put("MinecraftDataVersion",1343);result.put("Regions",Map.of("main",region));result.put("Metadata",Map.of("Name","Imported schematic"));return result;
    }
    private static int dimension(Map<String,Object> root,String key)throws IOException{Object value=root.get(key);if(!(value instanceof Short n)||(n&65535)==0)throw new IOException("Invalid legacy dimension");return n&65535;}
    private static int state(byte[] blocks,byte[] data,byte[] extra,int i){int high=extra==null?0:((extra[i>>>1]&255)>>>((1-(i&1))*4))&15;return (((blocks[i]&255)|(high<<8))<<4)|(data[i]&15);}
}
