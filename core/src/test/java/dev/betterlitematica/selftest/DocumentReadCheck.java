package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs the creative-paste source reader without launching Minecraft or writing to a world. */
public final class DocumentReadCheck {
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]);String before=SchematicImporter.sha256(source,Cancellation.NEVER);long start=System.nanoTime();
        var doc=SchematicDocument.read(source,Cancellation.NEVER);long cells=0,nonAir=0,blockEntities=0,entities=0,ticks=0;
        for(var part:doc.parts()){
            cells+=part.blocks().size();blockEntities+=part.blockEntities().size();entities+=part.entities().size();ticks+=part.blockTicks().size()+part.fluidTicks().size();
            for(int i=0;i<part.blocks().size();i++)if(!part.palette().get(part.blocks().get(i)).isAir())nonAir++;
            System.out.println("region="+part.region().name()+" min="+part.region().min()+" size="+part.region().size());
        }
        if(!before.equals(SchematicImporter.sha256(source,Cancellation.NEVER)))throw new AssertionError("Source changed");
        if(args.length>1&&nonAir!=Long.parseLong(args[1]))throw new AssertionError("Non-air mismatch: "+nonAir);
        System.out.printf(Locale.ROOT,"DOCUMENT PASS cells=%d nonAir=%d blockEntities=%d entities=%d ticks=%d seconds=%.3f maxHeapMiB=%d%n",cells,nonAir,blockEntities,entities,ticks,(System.nanoTime()-start)/1e9,Runtime.getRuntime().maxMemory()/(1<<20));
        System.out.println("sourceSha256="+before);System.out.println("Read-only paste preparation, no world writes or game acceptance.");
    }
}
