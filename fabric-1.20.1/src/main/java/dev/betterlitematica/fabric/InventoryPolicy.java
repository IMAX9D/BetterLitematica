package dev.betterlitematica.fabric;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Shared item-selection rules for pick, Easy Place and the printer. */
final class InventoryPolicy {
    private InventoryPolicy(){}
    static Item tool(String name){var id=Identifier.tryParse(name);if(id==null||!Registries.ITEM.containsId(id)||Registries.ITEM.get(id)==Items.AIR)throw new IllegalArgumentException("工具物品不存在");return Registries.ITEM.get(id);}
    static int destination(PlayerInventory inventory,int protectedSlots,Item tool){
        int selected=inventory.selectedSlot;if(allowed(inventory,selected,protectedSlots,tool))return selected;
        for(int i=0;i<9;i++)if(inventory.getStack(i).isEmpty()&&allowed(inventory,i,protectedSlots,tool))return i;
        for(int i=0;i<9;i++)if(allowed(inventory,i,protectedSlots,tool))return i;
        throw new IllegalStateException("没有可用的快捷栏格");
    }
    static int printerDestination(PlayerInventory inventory,int protectedSlots,Item tool){
        // Retain earlier materials in the hotbar; later batches can select them without a bag swap.
        for(int i=0;i<9;i++)if(inventory.getStack(i).isEmpty()&&allowed(inventory,i,protectedSlots,tool))return i;
        return destination(inventory,protectedSlots,tool);
    }
    private static boolean allowed(PlayerInventory inventory,int slot,int mask,Item tool){return (mask&(1<<slot))==0&&(tool==null||!inventory.getStack(slot).isOf(tool));}
}
