package dev.betterlitematica.fabric;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import java.util.List;

/** Registry-aware item and block data. Legacy item tags retain every supported component during migration. */
final class ItemDataBridge {
    private ItemDataBridge(){}
    private static final class Defaults {static final HolderLookup.Provider REGISTRIES=VersionGameplay.defaultRegistries();}
    static HolderLookup.Provider registries(){var client=Minecraft.getInstance();return client!=null&&client.level!=null?client.level.registryAccess():Defaults.REGISTRIES;}
    static CompoundTag write(ItemStack stack){return write(stack,registries());}
    static CompoundTag write(ItemStack stack,HolderLookup.Provider registries){
        var result=ItemStack.OPTIONAL_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE),stack).getOrThrow();
        if(!(result instanceof CompoundTag tag))throw new IllegalArgumentException("物品序列化失败");return tag;
    }
    static ItemStack read(CompoundTag source){return read(source,registries());}
    static ItemStack read(CompoundTag source,HolderLookup.Provider registries){
        if(source.isEmpty())return ItemStack.EMPTY;var tag=source.copy();
        if(tag.contains("Count")&&!tag.contains("count")){
            var fixed=DataFixers.getDataFixer().update(References.ITEM_STACK,new Dynamic<Tag>(NbtOps.INSTANCE,tag),3700,SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
            if(!(fixed instanceof CompoundTag compound))throw new IllegalArgumentException("物品数据迁移失败");tag=compound;
        }
        return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),tag).getOrThrow();
    }
    static ItemStack legacy(Item item,CompoundTag nbt){var tag=new CompoundTag();tag.putString("id",BuiltInRegistries.ITEM.getKey(item).toString());tag.putByte("Count",(byte)1);tag.put("tag",nbt.copy());return read(tag);}
    static CompoundTag identity(ItemStack stack){var tag=write(stack.copyWithCount(1));var components=tag.getCompoundOrEmpty("components");components.remove("minecraft:damage");if(components.isEmpty())tag.remove("components");return tag;}
    static List<ItemStack> contents(ItemStack stack,int size){
        if(size<0||size>54)throw new IllegalArgumentException("容器槽位超过预算");
        var component=stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        if(VersionGameplay.containerItems(component).skip(size).anyMatch(item->!item.isEmpty()))throw new IllegalArgumentException("容器数据超出目标槽位");
        var result=NonNullList.withSize(size,ItemStack.EMPTY);component.copyInto(result);return List.copyOf(result);
    }
    static void contents(ItemStack stack,List<ItemStack> items){stack.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(items));}
    static void blockEntityData(ItemStack stack,BlockState state,CompoundTag source){
        var tag=source.copy();var entity=((EntityBlock)state.getBlock()).newBlockEntity(BlockPos.ZERO,state);
        if(entity==null)throw new IllegalArgumentException("方块不支持实体数据");
        tag.putString("id",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());stack.set(DataComponents.BLOCK_ENTITY_DATA,net.minecraft.world.item.component.TypedEntityData.of(entity.getType(),tag));
    }
    static boolean hasLoot(ItemStack stack){return stack.has(DataComponents.CONTAINER_LOOT)||stack.has(DataComponents.BLOCK_ENTITY_DATA)&&stack.get(DataComponents.BLOCK_ENTITY_DATA).contains("LootTable");}
    static ItemStack withoutCustomKey(ItemStack stack,String key){var copy=stack.copy();var tag=copy.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();tag.remove(key);if(tag.isEmpty())copy.remove(DataComponents.CUSTOM_DATA);else copy.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return copy;}
    static void loadBlockEntity(BlockEntity entity,CompoundTag tag,HolderLookup.Provider registries){entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,registries,tag));}
}
