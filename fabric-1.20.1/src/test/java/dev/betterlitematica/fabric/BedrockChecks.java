package dev.betterlitematica.fabric;

import com.google.gson.JsonObject;
import dev.betterlitematica.core.Vec3i;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

/** Settings are checked through the same enclosing file format used by the client. */
final class BedrockChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(RuntimeException expected){checks++;}}
    private static void invalid(Consumer<BedrockSettings> change,String message){var settings=new BedrockSettings();change.accept(settings);rejects(settings::validate,message);}
    static int run()throws Exception{
        checks=0;var defaults=BedrockSettings.read(null);defaults.validate();
        check(defaults.whitelist.equals(List.of("minecraft:bedrock"))&&defaults.regions.isEmpty(),"Missing old settings introduce only the default target, not automatic regions");
        check(defaults.initialFacings.equals(EnumSet.of(BedrockSettings.Face.UP,BedrockSettings.Face.DOWN)),"Default setup faces are strictly vertical");
        var json=defaults.snapshot();var extension=new JsonObject();extension.addProperty("future",19);json.add("unknownExtension",extension);
        var settings=BedrockSettings.read(json);settings.emptyHandToggle=false;settings.shortWait=false;settings.timeoutTicks=240;settings.retries=3;
        settings.whitelist=new ArrayList<>(List.of("minecraft:bedrock","minecraft:end_portal_frame"));settings.excludedY=new ArrayList<>(List.of(-64,127));
        settings.breakDirections=EnumSet.of(BedrockSettings.Face.DOWN,BedrockSettings.Face.WEST);settings.initialFacings=EnumSet.of(BedrockSettings.Face.UP);
        var id=UUID.fromString("88937180-f2a2-4fa3-93de-83c3fe62b544");
        var region=new BedrockSettings.Region(id,"负层区域","server-example","minecraft:the_nether",new Vec3i(4,-39,7),new Vec3i(-2,-45,3),true);settings.regions.add(region);
        check(region.min().equals(new Vec3i(-2,-45,3))&&region.max().equals(new Vec3i(4,-39,7)),"Reversed negative corners retain inclusive bounds");
        check(region.contains(new Vec3i(-2,-45,3))&&region.contains(new Vec3i(4,-39,7))&&!region.contains(new Vec3i(4,-38,7)),"Region includes both end planes and excludes the next layer");
        var options=new InteractionOptions();options.bedrock=settings;var outer=options.snapshot();outer.addProperty("outerFuture",23);
        var directory=Files.createTempDirectory("betterlitematica-bedrock-settings-");var path=directory.resolve("options.json");
        try{
            InteractionOptions.write(path,outer);var restored=InteractionOptions.read(path);var read=restored.bedrock;
            check(read.regions.equals(List.of(region))&&read.regions.get(0).id().equals(id),"Real options persistence preserves exact region identity, world and dimension");
            check(read.whitelist.equals(settings.whitelist)&&read.excludedY.equals(settings.excludedY),"Real options persistence preserves target and negative-floor filters");
            check(read.breakDirections.equals(settings.breakDirections)&&read.initialFacings.equals(settings.initialFacings)&&!read.emptyHandToggle&&!read.shortWait&&read.timeoutTicks==240&&read.retries==3,"Real options persistence retains behavior, directions and limits");
            var roundTrip=restored.snapshot();check(roundTrip.get("outerFuture").getAsInt()==23&&roundTrip.getAsJsonObject("bedrock").getAsJsonObject("unknownExtension").get("future").getAsInt()==19,"Unknown outer and nested settings survive editing");
            var copy=read.copy();copy.whitelist.clear();copy.excludedY.clear();copy.breakDirections.clear();copy.regions.clear();
            check(read.whitelist.size()==2&&read.excludedY.size()==2&&read.breakDirections.size()==2&&read.regions.size()==1,"Draft copies cannot mutate live filter, direction or region collections");
            roundTrip.getAsJsonObject("bedrock").addProperty("timeoutTicks",19);InteractionOptions.write(path,roundTrip);
            try{InteractionOptions.read(path);throw new AssertionError("Malformed nested config silently accepted");}catch(java.io.IOException expected){checks++;}
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(directory);}
        invalid(s->s.timeoutTicks=19,"Too-short timeout is rejected");invalid(s->s.timeoutTicks=1201,"Unbounded timeout is rejected");
        invalid(s->s.retries=-1,"Negative retries are rejected");invalid(s->s.retries=21,"Unbounded retries are rejected");
        invalid(s->s.whitelist.clear(),"Empty whitelist is rejected");invalid(s->s.whitelist.add("minecraft:bedrock"),"Duplicate whitelist entries are rejected");
        invalid(s->s.whitelist.add("Minecraft:bad value"),"Malformed block IDs are rejected");
        invalid(s->{for(int i=0;i<BedrockSettings.MAX_WHITELIST;i++)s.whitelist.add("fixture:block_"+i);},"Whitelist count remains bounded");
        invalid(s->s.excludedY.addAll(List.of(-64,-64)),"Duplicate floor exclusions are rejected");invalid(s->s.excludedY.add(-2049),"Excluded floor lower bound is enforced");
        invalid(s->s.breakDirections.clear(),"Empty break directions are rejected");invalid(s->s.initialFacings=EnumSet.of(BedrockSettings.Face.NORTH),"Horizontal initial setup cannot enter the vertical planner");
        invalid(s->s.initialFacings.clear(),"Empty setup directions are rejected");
        invalid(s->{s.regions.add(region);s.regions.add(region);},"Duplicate persistent region IDs are rejected");
        invalid(s->s.regions.add(new BedrockSettings.Region(id,"临时","server-example","minecraft:overworld",Vec3i.ZERO,new Vec3i(1,1,1),false)),"Temporary regions cannot accidentally enter saved settings");
        rejects(()->new BedrockSettings.Region(id,"区域","world","bad dimension",Vec3i.ZERO,Vec3i.ZERO,true),"Malformed dimensions are rejected");
        rejects(()->new BedrockSettings.Region(id,"区域","","minecraft:overworld",Vec3i.ZERO,Vec3i.ZERO,true),"Persistent regions require a world identity");
        rejects(()->new BedrockSettings.Region(id,"区域","world","minecraft:overworld",new Vec3i(30_000_001,0,0),Vec3i.ZERO,true),"Out-of-range coordinates cannot enter region scans");
        var otherWorld=new BedrockSettings.Region(id,"负层区域","other-server","minecraft:the_nether",region.first(),region.second(),true);
        var otherDimension=new BedrockSettings.Region(id,"负层区域",region.world(),"minecraft:overworld",region.first(),region.second(),true);
        check(!region.equals(otherWorld)&&!region.equals(otherDimension),"Same coordinates and UUID do not erase world or dimension identity");
        var filter=new BedrockSettings();
        check(BedrockController.accepts(filter,Blocks.BEDROCK.getDefaultState(),-39),"Actual filter accepts the default target at negative Y");
        check(!BedrockController.accepts(filter,Blocks.STONE.getDefaultState(),-39),"Actual filter does not expand the default whitelist");
        filter.excludedY.add(-39);check(!BedrockController.accepts(filter,Blocks.BEDROCK.getDefaultState(),-39)&&BedrockController.accepts(filter,Blocks.BEDROCK.getDefaultState(),-38),"An excluded layer blocks exactly its Y coordinate");
        filter.whitelist.add("minecraft:end_portal_frame");check(BedrockController.accepts(filter,Blocks.END_PORTAL_FRAME.getDefaultState(),0),"Explicit permitted block additions take effect");
        for(var block:List.of(Blocks.AIR,Blocks.WATER,Blocks.BARRIER,Blocks.COMMAND_BLOCK,Blocks.CHAIN_COMMAND_BLOCK,Blocks.REPEATING_COMMAND_BLOCK,Blocks.STRUCTURE_BLOCK,Blocks.STRUCTURE_VOID,Blocks.JIGSAW)){
            filter.whitelist.add(Registries.BLOCK.getId(block).toString());check(!BedrockController.accepts(filter,block.getDefaultState(),0),"Explicit whitelist cannot override unsafe or replaceable target rejection: "+Registries.BLOCK.getId(block));
        }
        return checks;
    }
}
