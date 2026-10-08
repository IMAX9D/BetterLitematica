package dev.betterlitematica.fabric;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.datafixer.Schemas;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import java.util.List;

/** Component-aware item serialization. Legacy tags are migrated, never discarded or treated as empty. */
final class ItemDataBridge {
    private ItemDataBridge(){}
    private static final class Defaults {static final RegistryWrapper.WrapperLookup REGISTRIES=BuiltinRegistries.createWrapperLookup();}
    static RegistryWrapper.WrapperLookup registries(){var client=MinecraftClient.getInstance();return client!=null&&client.world!=null?client.world.getRegistryManager():Defaults.REGISTRIES;}
    static NbtCompound write(ItemStack stack){return stack.isEmpty()?new NbtCompound():(NbtCompound)stack.toNbt(registries());}
    static ItemStack read(NbtCompound source){return read(source,registries());}
    static ItemStack read(NbtCompound source,RegistryWrapper.WrapperLookup registries){
        NbtCompound tag=source.copy();
        if(tag.contains("Count")&&!tag.contains("count")){
            var fixed=Schemas.getFixer().update(TypeReferences.ITEM_STACK,new Dynamic<NbtElement>(NbtOps.INSTANCE,tag),3700,SharedConstants.getGameVersion().getSaveVersion().getId()).getValue();
            if(!(fixed instanceof NbtCompound compound))throw new IllegalArgumentException("物品数据迁移失败");tag=compound;
        }
        return ItemStack.fromNbt(registries,tag).orElseThrow(()->new IllegalArgumentException("物品组件数据无效"));
    }
    static ItemStack legacy(Item item,NbtCompound nbt){var tag=new NbtCompound();tag.putString("id",Registries.ITEM.getId(item).toString());tag.putByte("Count",(byte)1);tag.put("tag",nbt.copy());return read(tag);}
    static NbtCompound identity(ItemStack stack){var tag=write(stack.copyWithCount(1));var components=tag.getCompoundOrEmpty("components");components.remove("minecraft:damage");if(components.isEmpty())tag.remove("components");return tag;}
    static List<ItemStack> contents(ItemStack stack,int size){
        if(size<0||size>54)throw new IllegalArgumentException("容器槽位超过预算");
        var component=stack.getOrDefault(DataComponentTypes.CONTAINER,ContainerComponent.DEFAULT);
        if(component.stream().skip(size).anyMatch(item->!item.isEmpty()))throw new IllegalArgumentException("容器数据超出目标槽位");
        var result=DefaultedList.ofSize(size,ItemStack.EMPTY);component.copyTo(result);return List.copyOf(result);
    }
    static void contents(ItemStack stack,List<ItemStack> items){stack.set(DataComponentTypes.CONTAINER,ContainerComponent.fromStacks(items));}
    static void blockEntityData(ItemStack stack,BlockState state,NbtCompound source){
        var tag=source.copy();var entity=((BlockEntityProvider)state.getBlock()).createBlockEntity(BlockPos.ORIGIN,state);
        if(entity==null)throw new IllegalArgumentException("方块不支持实体数据");
        tag.putString("id",Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString());stack.set(DataComponentTypes.BLOCK_ENTITY_DATA,NbtComponent.of(tag));
    }
    static boolean hasLoot(ItemStack stack){return stack.contains(DataComponentTypes.CONTAINER_LOOT)||stack.getOrDefault(DataComponentTypes.BLOCK_ENTITY_DATA,NbtComponent.DEFAULT).copyNbt().contains("LootTable");}
    static ItemStack withoutCustomKey(ItemStack stack,String key){var copy=stack.copy();var tag=copy.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt();tag.remove(key);if(tag.isEmpty())copy.remove(DataComponentTypes.CUSTOM_DATA);else copy.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(tag));return copy;}
}
