package dev.betterlitematica.fabric;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import java.util.Objects;
import java.util.function.Predicate;

final class InventoryPolicy {
    private InventoryPolicy(){}
    private static final Predicate<ItemStack> UNPROTECTED=stack->false;

    static final class ToolProtection {
        private String text;private Predicate<ItemStack> protectedStack=UNPROTECTED;
        Predicate<ItemStack> get(boolean enabled,String configured){
            if(!enabled)return UNPROTECTED;
            if(!Objects.equals(text,configured)){var parsed=ToolItemSpec.parse(configured);protectedStack=parsed.item()==null?UNPROTECTED:parsed::matches;text=configured;}
            return protectedStack;
        }
    }
    static Item tool(String name){return ToolItemSpec.parse(name).item();}
    static int destination(PlayerInventory inventory,int protectedSlots,Item tool){
        return destinationWithProtection(inventory,protectedSlots,tool==null?UNPROTECTED:stack->stack.isOf(tool));
    }
    static int destinationWithProtection(PlayerInventory inventory,int protectedSlots,Predicate<ItemStack> protection){
        int selected=inventory.getSelectedSlot();if(allowed(inventory,selected,protectedSlots,protection))return selected;
        for(int i=0;i<9;i++)if(inventory.getStack(i).isEmpty()&&allowed(inventory,i,protectedSlots,protection))return i;
        for(int i=0;i<9;i++)if(allowed(inventory,i,protectedSlots,protection))return i;
        throw new IllegalStateException("没有可用的快捷栏格");
    }
    static int printerDestination(PlayerInventory inventory,int protectedSlots,Item tool){
        return printerDestinationWithProtection(inventory,protectedSlots,tool==null?UNPROTECTED:stack->stack.isOf(tool));
    }
    static int printerDestinationWithProtection(PlayerInventory inventory,int protectedSlots,Predicate<ItemStack> protection){

        for(int i=0;i<9;i++)if(inventory.getStack(i).isEmpty()&&allowed(inventory,i,protectedSlots,protection))return i;
        return destinationWithProtection(inventory,protectedSlots,protection);
    }
    private static boolean allowed(PlayerInventory inventory,int slot,int mask,Predicate<ItemStack> protection){return (mask&(1<<slot))==0&&!protection.test(inventory.getStack(slot));}
}
