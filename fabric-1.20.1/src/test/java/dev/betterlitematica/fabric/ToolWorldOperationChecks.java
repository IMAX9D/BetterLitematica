package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;

/** Registry-backed data and bounded command-plan checks; does not create or mutate a game world. */
public final class ToolWorldOperationChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|ArithmeticException expected){rejected=true;}check(rejected,message);}
    public static int run()throws Exception{
        checks=0;var a=new SelectionBox("a",new Vec3i(-8,-39,2),new Vec3i(-6,-38,5));var b=new SelectionBox("b",new Vec3i(5,-37,7),new Vec3i(6,-36,8));
        var area=new AreaSelection(List.of(a,b),"a",new Vec3i(-8,-39,2),false);
        check(ToolWorldOperations.operationBoxes(area).equals(List.of(a)),"Fill edits only the explicitly selected region");
        check(ToolWorldOperations.operationBoxes(area.select("")).equals(area.boxes()),"No selected region fills all regions");
        var moved=ToolWorldOperations.validateMove(area,new Vec3i(-7,-39,2));check(moved.boxes().size()==2&&moved.origin().equals(new Vec3i(-7,-39,2)),"Move carries all regions relative to the declared origin");
        check(moved.boxes().get(0).first().equals(new Vec3i(-7,-39,2))&&area.boxes().get(0).first().equals(new Vec3i(-8,-39,2)),"Overlapping destination leaves the source selection immutable");
        rejects(()->ToolWorldOperations.validateMove(AreaSelection.EMPTY,Vec3i.ZERO),"Empty move rejected before mutation");
        var overlap=new AreaSelection(List.of(a,new SelectionBox("overlap",a.first(),a.second())),"a",area.origin(),false);
        rejects(()->ToolWorldOperations.validateMove(overlap,Vec3i.ZERO),"Overlapping source boxes are explicitly rejected, not double deleted");
        var oversized=new AreaSelection(List.of(new SelectionBox("big",Vec3i.ZERO,new Vec3i(255,255,64))),"big",Vec3i.ZERO,false);
        rejects(()->ToolWorldOperations.validateMove(oversized,Vec3i.ZERO),"Snapshot volume budget checked before allocation");
        var regions=List.of(new Region("negative",new Vec3i(-17,-39,-5),new Vec3i(35,19,23)),new Region("tail",new Vec3i(100,4,-7),new Vec3i(3,2,5)));
        for(int limit:new int[]{1,7,256,4096,32768}){
            var plan=new ToolWorldOperations.CommandPages(regions,limit);Set<Vec3i> seen=new HashSet<>();int steps=0;
            while(!plan.done()){var page=plan.current();check(page.volume()<=limit&&page.volume()<=4096,"Every command page respects volume and fixed spatial bounds");
                var cursor=new RegionCursor(List.of(page));while(!cursor.done()){check(seen.add(cursor.local()),"Tail pages never overlap or duplicate cells");cursor.advance();}plan.advance();check(++steps<=16000,"Command traversal terminates within source volume");}
            check(seen.size()==regions.stream().mapToLong(Region::volume).sum(),"Command pages cover the complete selection");
            for(var region:regions){var cursor=new RegionCursor(List.of(region));while(!cursor.done()){check(seen.contains(cursor.local()),"Command pages contain every negative-coordinate and tail cell");cursor.advance();}}
        }
        var one=new Region("a",new Vec3i(-3,-39,5),new Vec3i(4,2,3));check(ToolWorldOperations.cloneCommand(one,new Vec3i(1,0,0)).equals("clone -3 -39 5 0 -38 7 -2 -39 5 replace move"),"Vanilla clone uses inclusive end and displacement rather than absolute origin");
        var admission=new ToolWorldOperations.ChunkAdmission(List.of(new Region("source",new Vec3i(-17,-39,0),new Vec3i(34,1,1)),new Region("target",new Vec3i(96,-39,0),new Vec3i(32,1,1))));
        var visited=new ArrayList<Vec3i>();boolean ready=false;for(int i=0;i<12&&!ready;i++){int before=visited.size();ready=admission.tick(p->{visited.add(p);return p.x()!=6;},1,Long.MAX_VALUE);check(visited.size()-before<=1,"Chunk admission honors the per-tick probe budget");}
        check(!ready&&visited.get(visited.size()-1).x()==6,"A missing destination holds admission before any clear stage");
        check(admission.tick(p->true,2,Long.MAX_VALUE),"Chunk admission resumes the held destination without restarting the full scan");
        check(visited.contains(new Vec3i(-2,0,0))&&visited.contains(new Vec3i(1,0,0)),"Readiness uses floor-divided negative coordinates and the inclusive far edge");
        check(ToolWorldOperations.deleteCommand(one).equals("kill @e[type=!minecraft:player,x=-3,y=-39,z=5,dx=3,dy=1,dz=2]"),"Deletion excludes players and uses inclusive selector dimensions");
        for(BlockEntity block:List.of(new ChestBlockEntity(BlockPos.ORIGIN,Blocks.CHEST.getDefaultState()),new HopperBlockEntity(BlockPos.ORIGIN,Blocks.HOPPER.getDefaultState()),new FurnaceBlockEntity(BlockPos.ORIGIN,Blocks.FURNACE.getDefaultState()))){
            var inventory=(Inventory)block;int slot=block instanceof FurnaceBlockEntity?2:0;var stack=new ItemStack(Items.DIAMOND,37);stack.getOrCreateNbt().putString("custom-test","preserve");inventory.setStack(slot,stack);
            var original=block.createNbtWithIdentifyingData();var saved=CreativeFillTask.emptyInventory(block);check(inventory.isEmpty(),"Replacement clears inventory before vanilla onStateReplaced can scatter it");check(saved.equals(original),"Full block-entity data remains available if placement fails");
            CreativeFillTask.restoreInventory(block,saved);check(inventory.getStack(slot).getCount()==37&&"preserve".equals(inventory.getStack(slot).getNbt().getString("custom-test")),"Rejected replacement restores item count and complete item NBT");
        }
        check(CreativeFillTask.emptyInventory(null)==null,"Non-container replacement needs no inventory snapshot");
        var pasteChest=new ChestBlockEntity(BlockPos.ORIGIN,Blocks.CHEST.getDefaultState());pasteChest.setStack(0,new ItemStack(Items.EMERALD,19));
        var rotated=Blocks.CHEST.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.EAST);
        check(CreativePasteTask.replacementInventory(Blocks.CHEST.getDefaultState(),rotated,pasteChest)==null&&pasteChest.getStack(0).getCount()==19,"Property-only paste retains destination container NBT when NBT application is disabled");
        var pasteSaved=CreativePasteTask.replacementInventory(Blocks.CHEST.getDefaultState(),Blocks.STONE.getDefaultState(),pasteChest);check(pasteSaved!=null&&pasteChest.isEmpty(),"Replacing a destination chest clears inventory before drop hooks");
        CreativeFillTask.restoreInventory(pasteChest,pasteSaved);check(pasteChest.getStack(0).getCount()==19,"Rejected paste can restore the destination inventory");
        Path file=Files.createTempFile("tool-move-source-",".litematic");Files.delete(file);
        try{
            var chest=new NbtCompound();chest.putString("id","minecraft:chest");chest.putInt("x",1);chest.putInt("y",0);chest.putInt("z",0);var items=new NbtList();var item=new NbtCompound();item.putByte("Slot",(byte)17);new ItemStack(Items.DIAMOND,31).writeNbt(item);items.add(item);chest.put("Items",items);
            int[] cells={1,2,0};var capture=new LitematicExport.Capture(new Region("source",new Vec3i(10,-39,5),new Vec3i(3,1,1)),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:stone"),BlockStateSpec.parse("minecraft:chest[facing=north,type=single,waterlogged=false]")),cells,List.of(NbtBridge.compound(chest)),List.of(),List.of(Map.of("x",0,"y",0,"z",0,"Block","minecraft:stone","Time",4,"Priority",0)),List.of());
            NbtWriter.writeNew(file,LitematicExport.create("move","",3465,new Vec3i(10,-39,5),List.of(capture)),Cancellation.NEVER);Arrays.fill(cells,0);
            var document=SchematicDocument.read(file,Cancellation.NEVER);var part=document.parts().get(0);check(part.blocks().get(0)==1&&part.blocks().get(1)==2,"Clearing an overlapping live source cannot change the completed disk snapshot");
            var transform=new PlacementTransform(new Vec3i(11,-39,5),0,false,false);check(transform.apply(part.region().min().add(new Vec3i(1,0,0))).equals(new Vec3i(12,-39,5)),"Source-relative container arrives at the translated destination");
            var restored=(NbtCompound)NbtBridge.game(part.blockEntities().get(1));check(restored.getList("Items",10).getCompound(0).getByte("Slot")==17&&restored.getList("Items",10).getCompound(0).getByte("Count")==31,"Container slots survive the durable snapshot pipeline");check(part.blockTicks().size()==1,"Scheduled ticks survive source backup");
        }finally{Files.deleteIfExists(file);}
        var root=new NbtCompound();root.putUuid("UUID",UUID.randomUUID());var child=new NbtCompound();child.putUuid("UUID",UUID.randomUUID());var passengers=new NbtList();passengers.add(child);root.put("Passengers",passengers);var ids=new ArrayList<UUID>();ToolWorldOperations.collectIds(root,ids,0);check(ids.equals(List.of(root.getUuid("UUID"),child.getUuid("UUID"))),"Captured entities and passengers are removed by their exact snapshot identity");
        var full=new ArrayList<UUID>();for(int i=0;i<8192;i++)full.add(UUID.randomUUID());rejects(()->ToolWorldOperations.collectIds(root,full,0),"Entity identity retention remains bounded");
        return checks;
    }
}
