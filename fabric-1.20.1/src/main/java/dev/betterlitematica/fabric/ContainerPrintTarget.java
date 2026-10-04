package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.math.BlockPos;
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
        if(!join||!(state.getBlock() instanceof ChestBlock)||state.get(ChestBlock.CHEST_TYPE)==ChestType.SINGLE)return one;
        var neighbor=pos.offset(ChestBlock.getFacing(state));var two=readOne(controller,neighbor);
        if(!ProjectionInfoData.connected(state,two.states().get(0)))throw new IllegalStateException("等待另一半箱子投影");
        var first=state.get(ChestBlock.CHEST_TYPE)==ChestType.RIGHT?one:two;
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
        if(tag==null||tag.contains("LootTable"))return new ContainerPrintTarget(pos.toImmutable(),List.of(pos.toImmutable()),List.of(data.state()),ProjectionInfoData.items(data.state(),null,pos),false,256);
        var items=ProjectionInfoData.items(data.state(),tag,pos);validate(items,tag);
        int bytes=128;for(var stack:items)bytes+=64+(stack.getNbt()==null?0:stack.getNbt().getSizeInBytes());
        return new ContainerPrintTarget(pos.toImmutable(),List.of(pos.toImmutable()),List.of(data.state()),items,true,bytes);
    }
    static void validate(List<ItemStack> items,NbtCompound tag){
        if(items.isEmpty()||items.size()>54)throw new IllegalArgumentException("不支持的容器槽位");
        if(tag.contains("Items")&&!tag.contains("Items",NbtElement.LIST_TYPE))throw new IllegalArgumentException("容器物品数据无效");
        var entries=tag.getList("Items",NbtElement.COMPOUND_TYPE);var slots=new BitSet();
        if(tag.get("Items") instanceof NbtList list&&!list.isEmpty()&&list.getHeldType()!=NbtElement.COMPOUND_TYPE)throw new IllegalArgumentException("容器物品数据无效");
        for(var value:entries){var entry=(NbtCompound)value;int slot=entry.getByte("Slot")&255;
            if(slot>=items.size()||slots.get(slot))throw new IllegalArgumentException("容器槽位数据无效");slots.set(slot);
            var stack=items.get(slot);if(stack.isEmpty()||stack.getCount()>stack.getMaxCount())throw new IllegalArgumentException("容器物品数量无效");
        }
    }
    boolean empty(){return items.stream().allMatch(ItemStack::isEmpty);}
    boolean matchesWorld(net.minecraft.client.world.ClientWorld world){
        for(int i=0;i<positions.size();i++){
            var pos=positions.get(i);if(!WorldChunks.loaded(world,pos))return false;
            var actual=world.getBlockState(pos);var expected=states.get(i);if(actual.getBlock()!=expected.getBlock())return false;
            if(expected.getBlock() instanceof ChestBlock&&(actual.get(ChestBlock.CHEST_TYPE)!=expected.get(ChestBlock.CHEST_TYPE)||actual.get(ChestBlock.FACING)!=expected.get(ChestBlock.FACING)))return false;
        }return true;
    }
    ScreenHandlerType<?> screenType(){
        var block=states.get(0).getBlock();
        if(block instanceof ChestBlock)return items.size()==54?ScreenHandlerType.GENERIC_9X6:ScreenHandlerType.GENERIC_9X3;
        if(block instanceof BarrelBlock)return ScreenHandlerType.GENERIC_9X3;
        if(block instanceof ShulkerBoxBlock)return ScreenHandlerType.SHULKER_BOX;
        if(block instanceof HopperBlock)return ScreenHandlerType.HOPPER;
        if(block instanceof DispenserBlock)return ScreenHandlerType.GENERIC_3X3;
        if(block instanceof BrewingStandBlock)return ScreenHandlerType.BREWING_STAND;
        if(block instanceof BlastFurnaceBlock)return ScreenHandlerType.BLAST_FURNACE;
        if(block instanceof SmokerBlock)return ScreenHandlerType.SMOKER;
        return ScreenHandlerType.FURNACE;
    }
    ItemStack placementStack(){
        if(positions.size()!=1)throw new IllegalStateException("Placement needs one source block");
        var stack=new ItemStack(states.get(0).getBlock().asItem());if(!stored)return stack;var tag=new NbtCompound();var entries=new NbtList();
        for(int i=0;i<items.size();i++)if(!items.get(i).isEmpty()){var value=items.get(i).writeNbt(new NbtCompound());value.putByte("Slot",(byte)i);entries.add(value);}
        tag.put("Items",entries);stack.setSubNbt("BlockEntityTag",tag);return stack;
    }
    boolean safeSurvivalItem(ItemStack stack){
        if(stack.isEmpty()||stack.getItem()!=states.get(0).getBlock().asItem())return false;
        var tag=BlockItem.getBlockEntityNbt(stack);if(tag==null||!tag.contains("Items"))return tag==null||!tag.contains("LootTable");
        try{var carried=ProjectionInfoData.items(states.get(0),tag,position);validate(carried,tag);
            if(carried.stream().allMatch(ItemStack::isEmpty))return true;
            for(int i=0;i<items.size();i++)if(!ItemStack.areEqual(carried.get(i),items.get(i)))return false;
            return !tag.contains("LootTable");
        }catch(RuntimeException bad){return false;}
    }
}
