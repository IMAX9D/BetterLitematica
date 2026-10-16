package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Source document for editing/pasting. Auxiliary data is read from the source, never from BPC. */
public record SchematicDocument(int dataVersion, List<Part> parts) {
    public record Part(Region region, List<BlockStateSpec> palette, PackedBits blocks,
                       Map<Integer,Map<String,Object>> blockEntities, List<Map<String,Object>> entities,
                       List<Map<String,Object>> blockTicks, List<Map<String,Object>> fluidTicks,SourceBlockIndex nonAir,Set<Integer> tickCells) {}
    public SchematicDocument { parts=List.copyOf(parts); }
    public static SchematicDocument read(Path path,Cancellation cancel)throws IOException {
        cancel.check();
        if(!Files.isRegularFile(path)||Files.size(path)>SchematicImporter.MAX_SOURCE_BYTES)throw new IOException("Source missing or exceeds 512 MiB compressed limit");
        Map<String,Object> root=NbtReader.read(path,SchematicImporter.SOURCE_LIMITS,cancel);
        return takeSource(root,cancel);
    }
    /** Transfers this private parsed tree; callers must not mutate its packed arrays afterwards. */
    static SchematicDocument takeSource(Map<String,Object> root,Cancellation cancel)throws IOException{
        root=SourceVersions.litematic(SchematicFormats.canonical(root,cancel),cancel);
        int version=NbtReader.integer(root,"Version");SourceVersions.requireLitematicVersion(version);int dataVersion=NbtReader.integer(root,"MinecraftDataVersion");
        var regions=NbtReader.compound(root.get("Regions"),"Regions");if(regions.isEmpty()||regions.size()>1024)throw new IOException("Invalid region count");
        List<Part> parts=new ArrayList<>();long total=0;
        for(var entry:new TreeMap<>(regions).entrySet()){
            cancel.check();var tag=NbtReader.compound(entry.getValue(),"Region");Vec3i position=vector(tag.get("Position")),signed=vector(tag.get("Size"));
            Vec3i size=new Vec3i(Math.toIntExact(Math.abs((long)signed.x())),Math.toIntExact(Math.abs((long)signed.y())),Math.toIntExact(Math.abs((long)signed.z())));
            Vec3i min=position.add(new Vec3i(signed.x()<0?signed.x()+1:0,signed.y()<0?signed.y()+1:0,signed.z()<0?signed.z()+1:0));
            Region region=new Region(entry.getKey(),min,size,position);total+=region.volume();if(total>SchematicImporter.MAX_CELLS)throw new IOException("Document volume limit");
            if(!(tag.get("BlockStatePalette") instanceof List<?> list)||list.isEmpty()||list.size()>65536)throw new IOException("Invalid palette");
            List<BlockStateSpec> palette=new ArrayList<>();for(Object value:list){cancel.check();palette.add(SourceVersions.readState(NbtReader.compound(value,"State"),dataVersion).state());}
            if(!(tag.get("BlockStates") instanceof long[] words))throw new IOException("Missing states");
            // The parsed root is private to this read and is never published. Retain its
            // packed array directly instead of duplicating hundreds of MiB for large sources.
            PackedBits blocks=PackedBits.takeOwnership(Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1)),Math.toIntExact(region.volume()),words);
            boolean[] air=new boolean[palette.size()];for(int i=0;i<air.length;i++)air[i]=palette.get(i).isAir()||palette.get(i).equals(SourceVersions.SKIPPED);
            var nonAir=SourceBlockIndex.build(blocks,size,air,cancel);
            Map<Integer,Map<String,Object>> blockEntities=new HashMap<>();
            for(var data:tags(tag,"TileEntities")){if(!SourceVersions.acceptsAuxiliary(SourceVersions.Kind.BLOCK_ENTITY,data,dataVersion))continue;Vec3i p=vector(data);if(p.x()<0||p.y()<0||p.z()<0||p.x()>=size.x()||p.y()>=size.y()||p.z()>=size.z())throw new IOException("Block entity outside region");int cell=p.x()+p.z()*size.x()+p.y()*size.x()*size.z();if(!palette.get(blocks.get(cell)).equals(SourceVersions.SKIPPED))blockEntities.put(cell,data);}
            var blockTicks=retainedTicks(tags(tag,"PendingBlockTicks"),size,blocks,palette);var fluidTicks=retainedTicks(tags(tag,"PendingFluidTicks"),size,blocks,palette);Set<Integer> tickCells=new HashSet<>();
            for(var tickList:List.of(blockTicks,fluidTicks))for(var data:tickList){var p=vector(data);if(p.x()>=0&&p.y()>=0&&p.z()>=0&&p.x()<size.x()&&p.y()<size.y()&&p.z()<size.z())tickCells.add(p.x()+p.z()*size.x()+p.y()*size.x()*size.z());}
            var entities=tags(tag,"Entities").stream().filter(e->SourceVersions.acceptsAuxiliary(SourceVersions.Kind.ENTITY,e,dataVersion)).toList();
            parts.add(new Part(region,List.copyOf(palette),blocks,Map.copyOf(blockEntities),entities,blockTicks,fluidTicks,nonAir,Set.copyOf(tickCells)));
        }
        return new SchematicDocument(NbtReader.integer(root,"MinecraftDataVersion"),parts);
    }
    public static Vec3i vector(Object value)throws IOException{var tag=NbtReader.compound(value,"Vector");return new Vec3i(NbtReader.integer(tag,"x"),NbtReader.integer(tag,"y"),NbtReader.integer(tag,"z"));}
    private static List<Map<String,Object>> retainedTicks(List<Map<String,Object>> ticks,Vec3i size,PackedBits blocks,List<BlockStateSpec> palette)throws IOException{
        if(!SourceVersions.compatibleLoading())return ticks;var retained=new ArrayList<Map<String,Object>>();
        for(var tick:ticks){var p=vector(tick);if(p.x()>=0&&p.y()>=0&&p.z()>=0&&p.x()<size.x()&&p.y()<size.y()&&p.z()<size.z()&&palette.get(blocks.get(p.x()+p.z()*size.x()+p.y()*size.x()*size.z())).equals(SourceVersions.SKIPPED))continue;retained.add(tick);}return List.copyOf(retained);
    }
    private static List<Map<String,Object>> tags(Map<String,Object> root,String name)throws IOException{
        if(!root.containsKey(name))return List.of();if(!(root.get(name) instanceof List<?> list)||list.size()>65536)throw new IOException("Invalid "+name);
        List<Map<String,Object>> result=new ArrayList<>();for(Object value:list)result.add(NbtReader.compound(value,name));return List.copyOf(result);
    }
}
