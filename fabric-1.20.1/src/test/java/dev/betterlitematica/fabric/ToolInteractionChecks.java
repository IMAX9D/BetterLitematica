package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.nio.file.*;
import java.util.*;
final class ToolInteractionChecks {
    private static int checks;private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    static int run()throws Exception{
        checks=0;
        check(ToolWorldOperations.recoveryPlacement(Path.of("C:/game/schematics/.betterlitematica-tool-recovery/move-test.litematic"),Vec3i.ZERO).source().equals(".betterlitematica-tool-recovery/move-test.litematic"),"Recovery placement uses legal relative identity even with absolute Windows backing file");
        check(ToolMode.selectable(true).size()==8&&!ToolMode.selectable(true).contains(ToolMode.GRID_PASTE),"Only implemented tools are offered in creative mode");
        check(ToolMode.selectable(false).equals(List.of(ToolMode.SELECTION,ToolMode.PLACEMENT,ToolMode.REBUILD)),"Survival list and wheel share the three permitted tools");
        for(var mode:ToolMode.selectable(true))check(mode.cycle(1,true).cycle(-1,true)==mode,"Creative modes cycle reversibly");
        for(var mode:ToolMode.values()){
            check(mode.cycle(1,true)!=ToolMode.GRID_PASTE&&mode.cycle(-1,true)!=ToolMode.GRID_PASTE,"Neither cycle direction reaches unfinished grid paste");
            check(!mode.cycle(1,false).creativeOnly(),"Survival forward skips destructive modes");check(!mode.cycle(-1,false).creativeOnly(),"Survival backward skips destructive modes");
            check(mode.usable(true).replacement()==mode.usable(true),"Legacy normalization is stable");
            check(!mode.usable(false).creativeOnly(),"Permission loss selects a safe non-executing mode");
            check(mode.cycle(0,false)==mode.usable(false),"Zero wheel step never loops or selects a forbidden mode");
        }
        check(ToolMode.GRID_PASTE.usable(true)==ToolMode.PASTE&&ToolMode.GRID_PASTE.usable(false)==ToolMode.PLACEMENT,"Old grid-paste preference maps to paste only when permitted");
        check(ToolMode.PASTE.cycle(1,true)==ToolMode.MOVE&&ToolMode.MOVE.cycle(-1,true)==ToolMode.PASTE,"Creative wheel has no gap for the removed entry");
        check(ToolMode.SELECTION.cycle(1,false)==ToolMode.PLACEMENT&&ToolMode.PLACEMENT.cycle(1,false)==ToolMode.REBUILD&&ToolMode.REBUILD.cycle(1,false)==ToolMode.SELECTION,"Three survival modes");
        var stick=new ItemStack(Items.STICK);var tool=ToolItemSpec.parse("minecraft:stick");check(tool.held(ItemStack.EMPTY,stick),"Offhand tools work");check(tool.held(stick,ItemStack.EMPTY),"Mainhand tools work");check(!tool.held(ItemStack.EMPTY,ItemStack.EMPTY),"Empty hands do not match stick");
        var named=ToolItemSpec.parse("minecraft:stick{CustomModelData:3}");var nbt=new NbtCompound();nbt.putInt("CustomModelData",3);nbt.putInt("Damage",8);stick.setNbt(nbt);
        check(named.matches(stick),"NBT tools ignore durability");nbt.putInt("CustomModelData",4);check(!named.matches(stick),"Different NBT is not the tool");check(tool.matches(stick),"ID-only tool ignores NBT");
        check(ToolItemSpec.parse("").held(ItemStack.EMPTY,stick),"Empty configuration selects empty main hand");check(!ToolItemSpec.parse("empty").held(stick,ItemStack.EMPTY),"Empty offhand does not steal all interactions");
        try{ToolItemSpec.validate("minecraft:no_such_tool");throw new AssertionError("Unknown tool accepted");}catch(IllegalArgumentException expected){checks++;}
        for(var face:Direction.values()){var hit=new BlockHitResult(Vec3d.ZERO,face,new BlockPos(-7,20,-13),false);var at=new Vec3i(-7,20,-13);var adjacent=at.add(new Vec3i(face.getOffsetX(),face.getOffsetY(),face.getOffsetZ()));check(ToolInteractions.point(hit,false,false).equals(at),"Selection targets interior");check(ToolInteractions.point(hit,false,true).equals(adjacent),"Sneak selection targets adjacent");check(ToolInteractions.point(hit,true,false).equals(adjacent),"Placement targets adjacent");check(ToolInteractions.point(hit,true,true).equals(at),"Sneak placement targets interior");}
        var reversed=new SelectionBox("r",new Vec3i(3,4,5),new Vec3i(-1,-2,-3));var grown=ToolInteractions.growBox(reversed,1);check(grown.first().equals(new Vec3i(4,5,6))&&grown.second().equals(new Vec3i(-2,-3,-4)),"Grow preserves corner direction on every axis");
        check(ToolInteractions.growBox(grown,-1).equals(reversed),"Shrink reverses growth");var one=new SelectionBox("one",Vec3i.ZERO,Vec3i.ZERO);check(ToolInteractions.growBox(one,-1).equals(one),"Shrink never inverts singleton");
        var options=new InteractionOptions();var input=new InputBindings();var session=new Object();var fired=new ArrayList<String>();input.poll(options.keys,session,()->true,k->false,fired::add);input.poll(options.keys,session,()->true,k->k.equals("MOUSE1")||k.equals("ALT"),fired::add);check(fired.isEmpty(),"Direct mouse/modifier bindings do not leak into generic actions");
        Path file=Files.createTempFile("tool-settings-",".json");try{options.mode="REPLACE";options.tools.primary="minecraft:oak_log[axis=x]";options.tools.secondary="minecraft:oak_log[axis=z]";options.tools.expandSelection=true;options.tools.distance=173;options.toolItem="minecraft:stick{CustomModelData:3}";options.keys.put("toolGrabModifier","G");InteractionOptions.write(file,options.snapshot());var read=InteractionOptions.read(file);check(read.mode.equals("REPLACE")&&read.tools.distance==173&&read.tools.expandSelection,"Tool mode and settings persist");check(read.tools.primary.equals(options.tools.primary)&&read.tools.secondary.equals(options.tools.secondary),"Full block states persist");check(read.toolItem.equals(options.toolItem)&&read.keys.get("toolGrabModifier").equals("G"),"NBT tool and custom modifier persist");}finally{Files.deleteIfExists(file);}
        return checks;
    }
}
