package dev.betterlitematica.selftest;

import dev.betterlitematica.core.Cancellation;
import dev.betterlitematica.io.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

final class SourceBudgetChecks {
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static int run(Path work)throws Exception {
        checks=0;Files.createDirectories(work);
        for(long heap:new long[]{1,99,100,101,1L<<30,10L<<30,Long.MAX_VALUE})for(int percent:new int[]{1,60,90}){
            long exact=BigInteger.valueOf(heap).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100)).max(BigInteger.ONE).longValueExact();
            check(SchematicReadLimits.bytesForHeap(heap,percent)==exact,"Exact quota without overflow or fixed ceiling");
        }
        check(SchematicReadLimits.bytesForHeap(10L<<30)==6L<<30,"10 GiB heap grants 6 GiB parsing quota");
        for(int percent:new int[]{-1,0,91,100}){
            try{SchematicReadLimits.bytesForHeap(10L<<30,percent);throw new AssertionError("Invalid percentage accepted");}
            catch(IllegalArgumentException expected){checks++;}
        }
        check(PreviewSourceReader.LIMITS==SchematicImporter.SOURCE_LIMITS,"Preview and importer use the same source quota");
        check(SchematicReadLimits.source(8L<<20).maxAllocatedBytes()<=8L<<20,"Header allocation remains bounded");
        check(SchematicReadLimits.source(64L<<20).maxAllocatedBytes()<=64L<<20,"Auxiliary allocation remains bounded");
        check(SchematicReadLimits.source(8L<<20).maxUncompressedBytes()==PreviewSourceReader.MAX_BYTES,"Partial scans share the full decompression envelope");
        check(NbtReader.Limits.DEFAULT.maxAllocatedBytes()==384L<<20,"Generic NBT allocation limit unchanged");
        check(NbtReader.Limits.DEFAULT.maxUncompressedBytes()==256L<<20,"Generic NBT decompression limit unchanged");
        Path source=work.resolve("auxiliary.nbt");
        // A region may itself be named Entities. Only its auxiliary children are omitted.
        Fixtures.write(source,Map.of("Regions",Map.of("Entities",Map.of("BlockStates",new long[]{123},"Entities",List.of(Map.of("id","minecraft:pig"))))));
        var preview=PreviewSourceReader.read(source,Cancellation.NEVER);
        var regions=NbtReader.compound(preview.get("Regions"),"regions");
        var region=NbtReader.compound(regions.get("Entities"),"region");
        check(Arrays.equals((long[])region.get("BlockStates"),new long[]{123}),"Region named Entities retains its block data");
        check(!region.containsKey("Entities"),"Preview skips auxiliary entity payloads");
        var complete=NbtReader.read(source,SchematicImporter.SOURCE_LIMITS,Cancellation.NEVER);
        var completeRegion=NbtReader.compound(NbtReader.compound(complete.get("Regions"),"regions").get("Entities"),"region");
        check(completeRegion.containsKey("Entities"),"Full source still preserves entities");
        try{PreviewSourceReader.read(source,()->true);throw new AssertionError("Cancellation ignored");}
        catch(InterruptedIOException expected){checks++;}
        Path malicious=work.resolve("declared-array.nbt");
        try(var out=new DataOutputStream(Files.newOutputStream(malicious))){
            out.writeByte(10);out.writeUTF("");out.writeByte(12);out.writeUTF("BlockStates");
            out.writeInt(Math.toIntExact(PreviewSourceReader.LIMITS.maxAllocatedBytes()/8+1));
        }
        try{PreviewSourceReader.read(malicious,Cancellation.NEVER);throw new AssertionError("Over-budget allocation accepted");}
        catch(IOException expected){check(expected.getMessage().contains("allocation budget"),"Declared oversized array rejected before allocation");}
        return checks;
    }
}
