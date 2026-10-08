package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.nio.file.*;
import java.util.*;

/** Reads source detail branches with a deliberately small heap; never loads Minecraft. */
public final class AuxiliaryReadCheck {
    public static void main(String[] args)throws Exception{
        var file=Path.of(args[0]);var hash=SchematicImporter.sha256(file,Cancellation.NEVER);long start=System.nanoTime();
        var header=NbtReader.readPartial(file,new NbtReader.Limits(512L<<20,4L<<20,64,1_000_000),Cancellation.NEVER,p->p.size()==1&&!Set.of("Regions","MinecraftDataVersion").contains(p.get(0))||p.size()==3&&p.get(0).equals("Regions")&&!Set.of("Position","Size").contains(p.get(2)));
        var regions=new ArrayList<Region>();for(var entry:new TreeMap<>(NbtReader.compound(header.get("Regions"),"Regions")).entrySet()){var tag=NbtReader.compound(entry.getValue(),"Region");var position=SchematicDocument.vector(tag.get("Position"));var signed=SchematicDocument.vector(tag.get("Size"));var size=new Vec3i(Math.abs(signed.x()),Math.abs(signed.y()),Math.abs(signed.z()));var min=position.add(new Vec3i(signed.x()<0?signed.x()+1:0,signed.y()<0?signed.y()+1:0,signed.z()<0?signed.z()+1:0));regions.add(new Region(entry.getKey(),min,size,position));}
        var metadata=new BlueprintMetadata("check",NbtReader.integer(header,"MinecraftDataVersion"),hash,List.of(BlockStateSpec.AIR),regions,List.of());var data=AuxiliaryData.read(file,metadata,Cancellation.NEVER);long blocks=data.parts().stream().mapToLong(p->p.blocks().size()).sum(),entities=data.parts().stream().mapToLong(p->p.entities().size()).sum();
        if(args.length>1&&blocks!=Long.parseLong(args[1]))throw new AssertionError("Block entity count mismatch");if(!hash.equals(SchematicImporter.sha256(file,Cancellation.NEVER)))throw new AssertionError("Source changed");
        System.out.printf(Locale.ROOT,"AUXILIARY PASS regions=%d blockEntities=%d entities=%d seconds=%.3f maxHeapMiB=%d%n",regions.size(),blocks,entities,(System.nanoTime()-start)/1e9,Runtime.getRuntime().maxMemory()/(1<<20));System.out.println("sourceSha256="+hash);
    }
}
