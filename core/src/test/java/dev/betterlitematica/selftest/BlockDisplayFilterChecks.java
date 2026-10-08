package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.PlacementStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Independent format fixtures and display-only identity contracts. */
public final class BlockDisplayFilterChecks {
    private static int checks;
    private static final UUID ID=new UUID(51,19);
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void rejects(Class<? extends Exception> type,Action action,String message)throws Exception{
        try{action.run();}catch(Exception e){check(type.isInstance(e),message+": "+e);return;}throw new AssertionError(message);
    }
    public static int run()throws Exception{
        checks=0;
        var mutable=new HashSet<>(List.of("oak_log","minecraft:oak_log","custom:polished/stone"));
        var white=new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,mutable);mutable.clear();
        check(white.blockIds().equals(Set.of("minecraft:oak_log","custom:polished/stone")),"IDs are canonical, deduplicated and independent of mutable input");
        rejects(UnsupportedOperationException.class,()->white.blockIds().clear(),"Published selection cannot be modified");
        for(String axis:List.of("x","y","z"))check(white.allows(BlockStateSpec.parse("oak_log[axis="+axis+"]")),"Selecting a block includes every state: "+axis);
        check(!white.allows("minecraft:oak_wood")&&!white.allows("other:oak_log"),"IDs match exactly, without type or namespace prefixes");
        var black=new BlockDisplayFilter(BlockDisplayFilter.Mode.BLACKLIST,white.whitelist());
        check(!black.allows("minecraft:oak_log")&&black.allows("minecraft:stone"),"Blacklist hides only selected block IDs");
        check(white.mode(BlockDisplayFilter.Mode.BLACKLIST).blacklist().isEmpty()&&white.mode(BlockDisplayFilter.Mode.BLACKLIST).allows("minecraft:oak_log"),"Switching to blacklist never imports the whitelist selection");
        check(!new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,Set.of()).allows("minecraft:stone"),"Empty whitelist displays no blocks");
        check(new BlockDisplayFilter(BlockDisplayFilter.Mode.BLACKLIST,Set.of()).allows("minecraft:stone"),"Empty blacklist displays all blocks");
        check(white.mode(BlockDisplayFilter.Mode.OFF).allows("minecraft:stone")&&white.mode(BlockDisplayFilter.Mode.OFF).whitelist().equals(white.whitelist()),"Turning filtering off preserves the whitelist while displaying all blocks");
        check(white.mode(BlockDisplayFilter.Mode.OFF).blockIds().isEmpty(),"Compatibility view while OFF returns only the blacklist");
        var mutableBlack=new HashSet<>(Set.of("stone"));var mutableWhite=new HashSet<>(Set.of("glass"));
        var independent=new BlockDisplayFilter(BlockDisplayFilter.Mode.BLACKLIST,mutableBlack,mutableWhite);mutableBlack.clear();mutableWhite.clear();
        check(independent.blacklist().equals(Set.of("minecraft:stone"))&&independent.whitelist().equals(Set.of("minecraft:glass")),"Both independent lists copy and normalize their input");
        rejects(UnsupportedOperationException.class,()->independent.blacklist().clear(),"Blacklist accessor is immutable");
        rejects(UnsupportedOperationException.class,()->independent.whitelist().clear(),"Whitelist accessor is immutable");
        for(var mode:BlockDisplayFilter.Mode.values()){
            var switched=independent.mode(mode);
            check(switched.blacklist().equals(independent.blacklist())&&switched.whitelist().equals(independent.whitelist()),"Mode switch retains both independent lists: "+mode);
            check(switched.mode(BlockDisplayFilter.Mode.BLACKLIST).equals(independent),"Mode roundtrip changes no selection: "+mode);
        }
        check(!independent.allows("minecraft:stone")&&independent.allows("minecraft:dirt"),"Blacklist uses only its own IDs");
        check(independent.mode(BlockDisplayFilter.Mode.WHITELIST).allows("minecraft:glass")&&!independent.mode(BlockDisplayFilter.Mode.WHITELIST).allows("minecraft:stone")&&!independent.mode(BlockDisplayFilter.Mode.WHITELIST).allows("minecraft:dirt"),"Whitelist uses only its own IDs");
        var editedWhite=new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,independent.blacklist(),Set.of("dirt"));
        check(editedWhite.blacklist().equals(independent.blacklist())&&independent.whitelist().equals(Set.of("minecraft:glass")),"Editing whitelist cannot mutate blacklist or an older snapshot");
        for(String id:List.of("",":stone","mine craft:stone","Minecraft:stone","minecraft:stone[axis=x]","minecraft:*","minecraft:stone\0","a:b:c"))
            rejects(IllegalArgumentException.class,()->new BlockDisplayFilter(BlockDisplayFilter.Mode.BLACKLIST,Set.of(id)),"Invalid IDs rejected: "+id);
        rejects(NullPointerException.class,()->new BlockDisplayFilter(null,Set.of()),"Null mode rejected");
        rejects(IllegalArgumentException.class,()->new BlockDisplayFilter(BlockDisplayFilter.Mode.OFF,Set.of("x:"+"a".repeat(1023))),"Per-ID length budget enforced");
        var countBudget=new HashSet<String>();for(int i=0;i<BlockDisplayFilter.MAX_IDS;i++)countBudget.add("x:"+Integer.toHexString(i));
        check(new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,countBudget).blockIds().size()==BlockDisplayFilter.MAX_IDS,"Bounded large mod registry selection remains supported");
        countBudget.add("x:overflow");rejects(IllegalArgumentException.class,()->new BlockDisplayFilter(BlockDisplayFilter.Mode.OFF,countBudget),"Selection count budget enforced even when off");
        var characters=new HashSet<String>();for(int i=0;i<1025;i++)characters.add("test:"+String.format(Locale.ROOT,"%04d",i)+"a".repeat(1015));
        rejects(IllegalArgumentException.class,()->new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,characters),"Total ID character budget enforced before publishing filter");
        rejects(IllegalArgumentException.class,()->new BlockDisplayFilter(BlockDisplayFilter.Mode.OFF,Set.of(),characters),"Inactive whitelist still obeys its own character budget");
        characters.remove(characters.iterator().next());
        var bothLarge=new BlockDisplayFilter(BlockDisplayFilter.Mode.OFF,characters,characters);
        check(bothLarge.blacklist().size()==1024&&bothLarge.whitelist().size()==1024,"Each list has an independent full character budget");

        var base=new Placement(ID,"original","source.litematic",new PlacementTransform(new Vec3i(-5,-39,17),1,true,false),true,false);
        check(base.displayFilter().equals(BlockDisplayFilter.OFF),"Six-argument construction remains unfiltered");
        check(new Placement(ID,"old","old.schem",base.transform(),true,false,.8f,Map.of(),false,2,ReplaceRule.NONE).displayFilter().equals(BlockDisplayFilter.OFF),"Legacy eleven-argument construction remains source-compatible");
        var p=base.displayFilter(white);var region=new Region("r",new Vec3i(-2,0,3),new Vec3i(2,3,4));
        var changed=p.region(region,RegionPlacement.original(region).enabled(false));
        for(var copy:List.of(p.placed(new PlacementTransform(new Vec3i(3,7,-13),3,false,true)),p.named("renamed"),p.source("other.schem"),p.enabled(false),p.locked(true),p.opacity(.9f),p.renderBlocks(false),p.axes(3),p.overlap(ReplaceRule.NONE),p.duplicate(),p.identity(new UUID(4,5)),changed))
            check(copy.displayFilter()==white,"Copy and mutation preserve the same immutable filter");
        check(p.sameGeometry(base)&&!p.equals(base),"Filter changes visible identity without changing construction geometry");
        check(!p.sameGeometry(p.source("other.schem")),"Real source changes remain geometry changes");
        var second=p.duplicate().displayFilter(black);
        check(!second.id().equals(p.id())&&p.displayFilter()==white&&second.displayFilter()==black,"Independent placements never share mutable filter settings");
        var session=new PlacementSession(List.of(p,second),p.id(),new LayerRange(LayerRange.Axis.Y,-39,70),.65f,false);
        var bytes=PlacementStore.encode(session);
        check(java.nio.ByteBuffer.wrap(bytes).getInt(4)==5,"Writer emits version five");
        check(PlacementStore.decode(bytes).equals(session),"All display and construction settings survive version-five roundtrip");
        var reverse=new BlockDisplayFilter(BlockDisplayFilter.Mode.WHITELIST,new LinkedHashSet<>(List.of("custom:polished/stone","minecraft:oak_log")));
        check(Arrays.equals(bytes,PlacementStore.encode(new PlacementSession(List.of(p.displayFilter(reverse),second),p.id(),session.layer(),session.opacity(),session.rendering()))),"Equivalent selection orders serialize identically");
        for(int version=1;version<=3;version++){
            var legacy=PlacementStore.decode(fixture(version,0,List.of(),0));var old=legacy.placements().get(0);
            check(old.displayFilter().equals(BlockDisplayFilter.OFF),"Version "+version+" defaults to all blocks");
            check(old.id().equals(ID)&&old.transform().origin().equals(new Vec3i(-7,-39,22))&&old.transform().quarterTurns()==3&&old.transform().mirrorX(),"Version "+version+" keeps identity and signed transform");
            check(old.opacity()==(version==1?.6f:.7f),"Version "+version+" keeps its opacity semantics");
            check(old.regions().size()==(version>=3?1:0)&&old.renderBlocks()==(version<3),"Version "+version+" keeps subregion and display flags");
            check(PlacementStore.decode(PlacementStore.encode(legacy)).equals(legacy),"Legacy migration writes and reloads without data loss");
        }
        var known=PlacementStore.decode(fixture(4,2,List.of("minecraft:stone"),1));
        check(known.placements().get(0).displayFilter().allows("minecraft:stone")&&!known.placements().get(0).displayFilter().allows("minecraft:dirt"),"Independent v4 fixture verifies wire mode and block selection");
        for(var mode:BlockDisplayFilter.Mode.values()){
            var old=PlacementStore.decode(fixture(4,mode.ordinal(),List.of("minecraft:stone"),1));var migrated=old.placements().get(0).displayFilter();
            check(migrated.mode()==mode,"Version-four active mode survives migration: "+mode);
            check(migrated.blacklist().equals(mode==BlockDisplayFilter.Mode.WHITELIST?Set.of():Set.of("minecraft:stone"))&&migrated.whitelist().equals(mode==BlockDisplayFilter.Mode.WHITELIST?Set.of("minecraft:stone"):Set.of()),"Version-four shared selection migrates only to its owning mode: "+mode);
            check(PlacementStore.decode(PlacementStore.encode(old)).equals(old),"Version-four migrated selection survives v5 rewrite: "+mode);
            var both=base.displayFilter(independent.mode(mode));var bothSession=new PlacementSession(List.of(both),both.id(),LayerRange.ALL,.5f,true);
            check(PlacementStore.decode(PlacementStore.encode(bothSession)).equals(bothSession),"Both lists persist including inactive list in mode: "+mode);
        }
        var v5=PlacementStore.decode(fixture(5,2,List.of("minecraft:stone"),1,List.of("minecraft:glass"),1)).placements().get(0).displayFilter();
        check(v5.blacklist().equals(Set.of("minecraft:stone"))&&v5.whitelist().equals(Set.of("minecraft:glass"))&&v5.allows("minecraft:glass")&&!v5.allows("minecraft:stone"),"Independent handwritten v5 fixture verifies list order and active semantics");
        rejects(IOException.class,()->PlacementStore.decode(fixture(5,0,List.of(),0,List.of(),-1)),"Inactive second list negative count is rejected");
        rejects(IOException.class,()->PlacementStore.decode(fixture(5,1,List.of(),0,List.of(),Integer.MAX_VALUE)),"Inactive second list impossible count is rejected before allocation");
        rejects(IOException.class,()->PlacementStore.decode(fixture(5,1,List.of(),0,List.of("minecraft:glass","minecraft:glass"),2)),"Second-list duplicate IDs are rejected");
        rejects(IOException.class,()->PlacementStore.decode(fixture(5,0,List.of(),0,List.of("minecraft:glass[foo=bar]"),1)),"Inactive second list IDs receive full validation");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,3,List.of(),0)),"Unknown wire filter mode rejected with a valid CRC");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of(),-1)),"Negative declared ID count rejected");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of(),Integer.MAX_VALUE)),"Impossible declared ID count rejected before allocation");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of("minecraft:stone","minecraft:stone"),2)),"Duplicate stored IDs rejected instead of silently shrinking the selection");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of("stone"),1)),"Stored IDs must be canonical");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of("minecraft:stone[axis=x]"),1)),"Stored properties cannot become a block filter");
        rejects(IOException.class,()->PlacementStore.decode(fixture(4,1,List.of("x:"+"a".repeat(1023)),1)),"Stored ID length cannot bypass core limits");
        byte[] corrupt=bytes.clone();corrupt[corrupt.length-1]^=1;
        rejects(IOException.class,()->PlacementStore.decode(corrupt),"Filter/session checksum corruption rejected");
        rejects(IOException.class,()->PlacementStore.decode(Arrays.copyOf(bytes,bytes.length-9)),"Truncated filter/session rejected");
        var file=Files.createTempFile("block-display-filter-",".blps");
        try{PlacementStore.write(file,session);check(PlacementStore.read(file).equals(session),"Actual atomic file write retains each independent filter");Files.write(file,corrupt);rejects(IOException.class,()->PlacementStore.read(file),"Corrupt file read fails");check(Arrays.equals(Files.readAllBytes(file),corrupt),"Failed read preserves original recovery bytes");}finally{Files.deleteIfExists(file);}
        return checks;
    }
    /** Handwritten earlier-version wire records, not generated by the production encoder. */
    private static byte[] fixture(int version,int mode,List<String> ids,int count)throws IOException{
        return fixture(version,mode,ids,count,List.of(),0);
    }
    private static byte[] fixture(int version,int mode,List<String> ids,int count,List<String> white,int whiteCount)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(0x424c5053);out.writeInt(version);out.writeBoolean(true);out.writeLong(ID.getMostSignificantBits());out.writeLong(ID.getLeastSignificantBits());
            out.writeByte(1);out.writeInt(-39);out.writeInt(70);out.writeFloat(.6f);out.writeBoolean(false);out.writeInt(1);
            out.writeLong(ID.getMostSignificantBits());out.writeLong(ID.getLeastSignificantBits());out.writeUTF("old");out.writeUTF("old.litematic");out.writeInt(-7);out.writeInt(-39);out.writeInt(22);out.writeByte(3);out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(true);
            if(version>=2)out.writeFloat(.7f);
            if(version>=3){out.writeBoolean(false);out.writeByte(5);out.writeByte(ReplaceRule.NON_AIR.ordinal());out.writeInt(1);out.writeUTF("r");out.writeInt(2);out.writeInt(-3);out.writeInt(9);out.writeByte(1);out.writeBoolean(false);out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(true);}
            if(version>=4){out.writeByte(mode);out.writeInt(count);for(String id:ids)out.writeUTF(id);}
            if(version>=5){out.writeInt(whiteCount);for(String id:white)out.writeUTF(id);}
            var crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());
        }return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{System.out.println("BlockDisplayFilterChecks: "+run()+" checks");}
}
