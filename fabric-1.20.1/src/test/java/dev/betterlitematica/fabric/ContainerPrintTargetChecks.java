package dev.betterlitematica.fabric;

import net.minecraft.block.*;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Creative item payloads are read back by actual vanilla block entities. */
final class ContainerPrintTargetChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static int run(){checks=0;var pos=BlockPos.ORIGIN;
        for(Block block:List.of(Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.BARREL,Blocks.SHULKER_BOX,Blocks.HOPPER,Blocks.DISPENSER,Blocks.DROPPER,Blocks.FURNACE,Blocks.BLAST_FURNACE,Blocks.SMOKER,Blocks.BREWING_STAND)){
            var state=block.getDefaultState();check(ContainerPrintTarget.supported(state),"Vanilla inventory block is supported");
            var entity=((net.minecraft.block.BlockEntityProvider)block).createBlockEntity(pos,state);var inventory=(Inventory)entity;
            var wanted=new ItemStack(Items.DIAMOND,3);wanted.setCustomName(Text.literal("精确内容"));wanted.getOrCreateNbt().putInt("fixture",42);
            var values=new ArrayList<ItemStack>(Collections.nCopies(inventory.size(),ItemStack.EMPTY));values.set(0,wanted);
            var target=new ContainerPrintTarget(pos,List.of(pos),List.of(state),List.copyOf(values),true,1024);
            var item=target.placementStack();var tag=BlockItem.getBlockEntityNbt(item);check(tag!=null,"Creative item carries block entity inventory");
            entity.readNbt(tag.copy());check(ItemStack.areEqual(inventory.getStack(0),wanted),"Vanilla entity reads exact count, name and custom item NBT");
            check(target.safeSurvivalItem(new ItemStack(block.asItem())),"Plain empty container can be filled after survival placement");
            check(target.safeSurvivalItem(item),"A matching prefilled survival item is usable");
            var wrong=item.copy();BlockItem.getBlockEntityNbt(wrong).getList("Items",NbtElement.COMPOUND_TYPE).getCompound(0).putByte("Count",(byte)4);
            check(!target.safeSurvivalItem(wrong),"Different prefilled contents are not consumed as empty building material");
            check(wanted.getCount()==3&&wanted.getNbt().getInt("fixture")==42,"Payload mutation does not modify desired source stack");
            var empty=new ContainerPrintTarget(pos,List.of(pos),List.of(state),Collections.nCopies(inventory.size(),ItemStack.EMPTY),true,256);
            check(empty.empty()&&BlockItem.getBlockEntityNbt(empty.placementStack()).getList("Items",NbtElement.COMPOUND_TYPE).isEmpty(),"Stored empty source clears only a newly created creative item payload");
            check(!empty.safeSurvivalItem(item),"Printing an empty source does not spend a loaded survival container");
            var absent=new ContainerPrintTarget(pos,List.of(pos),List.of(state),empty.items(),false,256);
            check(absent.empty()&&BlockItem.getBlockEntityNbt(absent.placementStack())==null,"Absent inventory data keeps ordinary block placement without invented source contents");
        }
        check(!ContainerPrintTarget.supported(Blocks.ENDER_CHEST.getDefaultState()),"Ender chest player storage is not a schematic inventory");
        check(!ContainerPrintTarget.supported(Blocks.CRAFTING_TABLE.getDefaultState()),"Crafting recipes are not stored container items");
        var values=new ArrayList<ItemStack>(Collections.nCopies(27,ItemStack.EMPTY));values.set(0,new ItemStack(Items.STONE,1));
        var entries=new NbtList();var entry=values.get(0).writeNbt(new NbtCompound());entry.putByte("Slot",(byte)0);entries.add(entry);var tag=new NbtCompound();tag.put("Items",entries);
        ContainerPrintTarget.validate(values,tag);checks++;
        entries.add(entry.copy());try{ContainerPrintTarget.validate(values,tag);throw new AssertionError("Duplicate slot accepted");}catch(IllegalArgumentException expected){checks++;}
        entries.remove(1);entry.putByte("Slot",(byte)27);try{ContainerPrintTarget.validate(values,tag);throw new AssertionError("Out of range slot accepted");}catch(IllegalArgumentException expected){checks++;}
        entry.putByte("Slot",(byte)0);values.set(0,new ItemStack(Items.STONE,65));try{ContainerPrintTarget.validate(values,tag);throw new AssertionError("Oversized stack accepted");}catch(IllegalArgumentException expected){checks++;}
        var doubleBox=new ContainerPrintTarget(pos,List.of(pos,pos.east()),List.of(Blocks.CHEST.getDefaultState(),Blocks.CHEST.getDefaultState()),Collections.nCopies(54,ItemStack.EMPTY),true,512);
        check(doubleBox.screenType()==ScreenHandlerType.GENERIC_9X6,"Double chest requires actual 54-slot screen");
        return checks;
    }
}
