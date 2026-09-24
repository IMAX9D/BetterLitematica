package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.nio.file.*;
import java.util.*;
/** Full-source edit check: writes a new validation artifact, never a Minecraft world. */
public final class LargeEditCheck {
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),output=Path.of(args[1]);String hash=SchematicImporter.sha256(source,Cancellation.NEVER);long start=System.nanoTime();
        var result=LitematicEdit.replace(source,output,BlockStateSpec.parse(args[2]),BlockStateSpec.parse(args[3]),true,null,Cancellation.NEVER);
        if(args.length>4&&result.changed()!=Long.parseLong(args[4]))throw new AssertionError("Unexpected edited count: "+result.changed());if(!hash.equals(SchematicImporter.sha256(source,Cancellation.NEVER)))throw new AssertionError("Source changed");
        System.out.printf(Locale.ROOT,"EDIT PASS changed=%d removedBlockEntities=%d removedTicks=%d seconds=%.3f maxHeapMiB=%d outputBytes=%d%n",result.changed(),result.removedBlockEntities(),result.removedTicks(),(System.nanoTime()-start)/1e9,Runtime.getRuntime().maxMemory()/(1<<20),Files.size(output));System.out.println("sourceSha256="+hash);
    }
}
