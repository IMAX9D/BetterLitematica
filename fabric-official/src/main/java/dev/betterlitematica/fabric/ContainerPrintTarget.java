package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

/** Detached source inventories. Empty source slots do not authorize deleting world items. */
record ContainerPrintTarget(BlockPos position,List<BlockPos> positions,List<BlockState> states,
                            List<ItemStack> items,boolean stored,int bytes) {
    static boolean supported(BlockState state){
        var block=state.getBlock();return block instanceof ChestBlock||block instanceof BarrelBlock
            ||block instanceof ShulkerBoxBlock||block instanceof HopperBlock||block instanceof DispenserBlock
            ||block instanceof AbstractFurnaceBlock||block instanceof BrewingStandBlock;
    }
    static ContainerPrintTarget read(ProjectionController controller,BlockPos pos,boolean join){
        var one=readOne(controller,pos);var state=one.states().get(0);
        if(!join||!(state.getBlock() instanceof ChestBlock)||state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return one;
        var neighbor=pos.relative(ChestBlock.getConnectedDirection(state));var two=readOne(controller,neighbor);
        if(!ProjectionInfoData.connected(state,two.states().get(0)))throw new IllegalStateException("等待另一半箱子投影");
        var first=state.getValue(ChestBlock.TYPE)==ChestType.RIGHT?one:two;
        var second=first==one?two:one;var slots=new ArrayList<ItemStack>(54);slots.addAll(first.items());slots.addAll(second.items());
        return new ContainerPrintTarget(first.position(),List.of(first.position(),second.position()),
            List.of(first.states().get(0),second.states().get(0)),List.copyOf(slots),one.stored()||two.stored(),one.bytes()+two.bytes());
    }
    private static ContainerPrintTarget readOne(ProjectionController controller,BlockPos pos){
        var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());var cell=controller.informationCell(at);
        if(cell==null||cell.unknown())throw new IllegalStateException("等待容器投影数据");
        var data=ProjectionInfoData.projectionBlock(cell,at);
        if(data.state()==null||!supported(data.state()))throw new IllegalStateException("投影容器已更换");
        var tag=data.tag();
        if(!data.error().isEmpty()&&!(tag==null&&data.error().equals("投影未包含容器数据")))throw new IllegalStateException(data.error());
        if(tag==null||tag.contains("LootTable"))return new ContainerPrintTarget(pos.immutable(),List.of(pos.immutable()),List.of(data.state()),ProjectionInfoData.items(data.state(),null,pos),false,256);
        var items=ProjectionInfoData.items(data.state(),tag,pos);validate(items,tag);
        int bytes=128;for(var stack:items)bytes+=64+ItemDataBridge.write(stack).sizeInBytes();
        return new ContainerPrintTarget(pos.immutable(),List.of(pos.immutable()),List.of(data.state()),items,true,bytes);
    }
    static void validate(List<ItemStack> items,CompoundTag tag){
        if(items.isEmpty()||items.size()>54)throw new IllegalArgumentException("不支持的容器槽位");
        if(tag.contains("Items")&&!NbtAccess.contains(tag,"Items",Tag.TAG_LIST))throw new IllegalArgumentException("容器物品数据无效");
        var entries=NbtAccess.list(tag,"Items",Tag.TAG_COMPOUND);var slots=new BitSet();
        if(tag.get("Items") instanceof ListTag list&&!list.isEmpty()&&list.stream().anyMatch(value->!(value instanceof CompoundTag)))throw new IllegalArgumentException("容器物品数据无效");
        for(var value:entries){var entry=(CompoundTag)value;int slot=entry.getByteOr("Slot",(byte)0)&255;
            if(slot>=items.size()||slots.get(slot))throw new IllegalArgumentException("容器槽位数据无效");slots.set(slot);
            var stack=items.get(slot);if(stack.isEmpty()||stack.getCount()>stack.getMaxStackSize())throw new IllegalArgumentException("容器物品数量无效");
        }
    }
    boolean empty(){return items.stream().allMatch(ItemStack::isEmpty);}
    boolean matchesWorld(net.minecraft.client.multiplayer.ClientLevel world){
        for(int i=0;i<positions.size();i++){
            var pos=positions.get(i);if(!WorldChunks.loaded(world,pos))return false;
            var actual=world.getBlockState(pos);var expected=states.get(i);if(actual.getBlock()!=expected.getBlock())return false;
            if(expected.getBlock() instanceof ChestBlock&&(actual.getValue(ChestBlock.TYPE)!=expected.getValue(ChestBlock.TYPE)||actual.getValue(ChestBlock.FACING)!=expected.getValue(ChestBlock.FACING)))return false;
        }return true;
    }
    MenuType<?> screenType(){
        var block=states.get(0).getBlock();
        if(block instanceof ChestBlock)return items.size()==54?MenuType.GENERIC_9x6:MenuType.GENERIC_9x3;
        if(block instanceof BarrelBlock)return MenuType.GENERIC_9x3;
        if(block instanceof ShulkerBoxBlock)return MenuType.SHULKER_BOX;
        if(block instanceof HopperBlock)return MenuType.HOPPER;
        if(block instanceof DispenserBlock)return MenuType.GENERIC_3x3;
        if(block instanceof BrewingStandBlock)return MenuType.BREWING_STAND;
        if(block instanceof BlastFurnaceBlock)return MenuType.BLAST_FURNACE;
        if(block instanceof SmokerBlock)return MenuType.SMOKER;
        return MenuType.FURNACE;
    }
    ItemStack placementStack(){
        if(positions.size()!=1)throw new IllegalStateException("Placement needs one source block");
        var stack=new ItemStack(states.get(0).getBlock().asItem());if(stored)ItemDataBridge.contents(stack,items);return stack;
    }
    boolean safeSurvivalItem(ItemStack stack){
        if(stack.isEmpty()||stack.getItem()!=states.get(0).getBlock().asItem())return false;
        if(ItemDataBridge.hasLoot(stack))return false;
        try{var carried=ItemDataBridge.contents(stack,items.size());
            if(carried.stream().allMatch(ItemStack::isEmpty))return true;
            for(int i=0;i<items.size();i++)if(!ItemStack.matches(carried.get(i),items.get(i)))return false;
            return true;
        }catch(RuntimeException bad){return false;}
    }
}
