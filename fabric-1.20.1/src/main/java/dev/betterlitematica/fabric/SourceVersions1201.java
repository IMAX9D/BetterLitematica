package dev.betterlitematica.fabric;

import com.mojang.serialization.Dynamic;
import dev.betterlitematica.io.*;
import net.minecraft.SharedConstants;
import net.minecraft.datafixer.*;
import net.minecraft.datafixer.fix.BlockStateFlattening;
import net.minecraft.nbt.*;
import java.io.*;
import java.util.*;

/** Only called by source IO workers; native DFU never runs in a frame or input callback. */
final class SourceVersions1201 implements SourceVersions.Adapter {
    @Override public int version(){return SharedConstants.getGameVersion().getSaveVersion().getId();}
    @Override public String cacheKey(){return "mc"+version()+"-2";}
    @Override public Map<String,Object> fix(SourceVersions.Kind kind,Map<String,Object> value,int source)throws IOException{
        try{var type=switch(kind){case STATE->TypeReferences.BLOCK_STATE;case BLOCK_ENTITY->TypeReferences.BLOCK_ENTITY;case ENTITY->TypeReferences.ENTITY_TREE;};int from=kind==SourceVersions.Kind.STATE?Math.max(1451,source):source;var dynamic=new Dynamic<NbtElement>(NbtOps.INSTANCE,NbtBridge.game(value));var result=Schemas.getFixer().update(type,dynamic,from,version()).getValue();if(!(result instanceof NbtCompound tag))throw new IOException("Data fixer returned non-compound data");return NbtBridge.compound(tag);}
        catch(RuntimeException e){throw new IOException("Source data migration failed: "+e.getMessage(),e);}
    }
    @Override public Map<String,Object> legacy(int id,int metadata,String name)throws IOException{
        if(name!=null){
            if(!name.contains(":"))name="minecraft:"+name;
            if(!name.startsWith("minecraft:"))return Map.of("Name",name,"Properties",Map.of("legacy_meta",Integer.toString(metadata)));
            int mapped=BlockStateFlattening.OLD_BLOCK_TO_ID.getInt(name);if(mapped<0)throw new IOException("Unknown legacy block name "+name+"; source retained");id=mapped>>>4;
        }
        var state=BlockStateFlattening.lookupState((id<<4)|metadata).convert(NbtOps.INSTANCE).getValue();if(!(state instanceof NbtCompound tag))throw new IOException("Unknown legacy block id "+id);
        if(id!=0&&tag.getString("Name").equals("minecraft:air"))throw new IOException("Unknown legacy block id "+id+"; source retained");return fix(SourceVersions.Kind.STATE,NbtBridge.compound(tag),1451);
    }
}
