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
    // Immutable registry snapshot: import workers never touch the game registry or a world.
    private final Map<String,Map<String,Set<String>>> blocks;
    private final Set<String> blockEntities,entities;
    private final String registryKey;
    SourceVersions1201(){
        var snapshot=new TreeMap<String,Map<String,Set<String>>>();
        for(var block:net.minecraft.util.registry.Registry.BLOCK){
            var properties=new TreeMap<String,Set<String>>();
            for(var property:block.getStateManager().getProperties())properties.put(property.getName(),propertyValues(property));
            snapshot.put(net.minecraft.util.registry.Registry.BLOCK.getId(block).toString(),Collections.unmodifiableMap(properties));
        }
        blocks=Collections.unmodifiableMap(snapshot);
        blockEntities=Set.copyOf(net.minecraft.util.registry.Registry.BLOCK_ENTITY_TYPE.getIds().stream().map(Object::toString).toList());
        entities=Set.copyOf(net.minecraft.util.registry.Registry.ENTITY_TYPE.getIds().stream().map(Object::toString).toList());
        try{var hash=java.security.MessageDigest.getInstance("SHA-256");registryKey=HexFormat.of().formatHex(hash.digest(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static <T extends Comparable<T>> Set<String> propertyValues(net.minecraft.state.property.Property<T> property){
        var values=new TreeSet<String>();for(T value:property.getValues())values.add(property.name(value));return Collections.unmodifiableSet(values);
    }
    @Override public boolean compatibleLoading(){return true;}
    @Override public boolean known(SourceVersions.Kind kind,String name){
        if(!name.contains(":"))name="minecraft:"+name;
        return switch(kind){case STATE->blocks.containsKey(name);case BLOCK_ENTITY->blockEntities.contains(name);case ENTITY->entities.contains(name);};
    }
    @Override public boolean supported(dev.betterlitematica.core.BlockStateSpec state){
        var properties=blocks.get(state.name());if(properties==null)return false;
        for(var entry:state.properties().entrySet()){var values=properties.get(entry.getKey());if(values==null||!values.contains(entry.getValue()))return false;}return true;
    }

    @Override public int version(){return SharedConstants.getGameVersion().getSaveVersion().getId();}
    @Override public String cacheKey(){return "mc"+version()+"-compat1-"+registryKey;}
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
