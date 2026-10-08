package dev.betterlitematica.fabric;

import com.mojang.serialization.Dynamic;
import dev.betterlitematica.io.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import java.io.*;
import java.util.*;

/** Only called by source IO workers; native DFU never runs in a frame or input callback. */
final class SourceVersions1201 implements SourceVersions.Adapter {
    @Override public int version(){return SharedConstants.getCurrentVersion().dataVersion().version();}
    @Override public String cacheKey(){return "mc"+version()+"-2";}
    @Override public Map<String,Object> fix(SourceVersions.Kind kind,Map<String,Object> value,int source)throws IOException{
        try{var type=switch(kind){case STATE->References.BLOCK_STATE;case BLOCK_ENTITY->References.BLOCK_ENTITY;case ENTITY->References.ENTITY_TREE;};int from=kind==SourceVersions.Kind.STATE?Math.max(1451,source):source;var dynamic=new Dynamic<Tag>(NbtOps.INSTANCE,NbtBridge.game(value));var result=DataFixers.getDataFixer().update(type,dynamic,from,version()).getValue();if(!(result instanceof CompoundTag tag))throw new IOException("Data fixer returned non-compound data");var out=NbtBridge.compound(tag);if(kind==SourceVersions.Kind.STATE&&!out.containsKey("Name")&&out.get("id") instanceof String){out.put("Name",out.remove("id"));if(out.containsKey("properties"))out.put("Properties",out.remove("properties"));}return out;}
        catch(RuntimeException e){throw new IOException("Source data migration failed: "+e.getMessage(),e);}
    }
    @Override public Map<String,Object> legacy(int id,int metadata,String name)throws IOException{
        if(name!=null){
            if(!name.contains(":"))name="minecraft:"+name;
            if(!name.startsWith("minecraft:"))return Map.of("Name",name,"Properties",Map.of("legacy_meta",Integer.toString(metadata)));
            int mapped=BlockStateData.ID_BY_OLD_NAME.getInt(name);if(mapped<0)throw new IOException("Unknown legacy block name "+name+"; source retained");id=mapped>>>4;
        }
        var state=BlockStateData.getTag((id<<4)|metadata).convert(NbtOps.INSTANCE).getValue();if(!(state instanceof CompoundTag tag))throw new IOException("Unknown legacy block id "+id);
        if(id!=0&&tag.getStringOr("Name","").equals("minecraft:air"))throw new IOException("Unknown legacy block id "+id+"; source retained");return fix(SourceVersions.Kind.STATE,NbtBridge.compound(tag),1451);
    }
}
