package dev.betterlitematica.fabric;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import java.util.function.Predicate;

/** Uses production protection/selection predicates with real registered stacks, no live world. */
public final class ToolInventoryChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){checks=0;
        var cache=new InventoryPolicy.ToolProtection();String configured="minecraft:stone{tool:1b,Damage:7}";
        var protection=cache.get(true,configured);var tool=new ItemStack(Items.STONE,1);tool.getOrCreateNbt().putBoolean("tool",true);tool.getOrCreateNbt().putInt("Damage",91);
        var ordinary=new ItemStack(Items.STONE,64);var other=tool.copy();other.getOrCreateNbt().putString("name","different");
        check(protection.test(tool),"Tool NBT ignores only durability");check(!protection.test(ordinary)&&!protection.test(other),"Plain and otherwise differently tagged materials remain usable");
        check(cache.get(true,new String(configured))==protection,"Equivalent configuration reuses its parsed predicate");
        var inventory=new PlayerInventory(null);for(int i=0;i<9;i++)inventory.setStack(i,ordinary.copy());inventory.setStack(0,tool);inventory.selectedSlot=0;
        check(InventoryPolicy.destinationWithProtection(inventory,0,protection)==1,"A full hotbar of normal same-item materials is not falsely protected");
        check(InventoryPolicy.printerDestinationWithProtection(inventory,0,protection)==1,"Printer preserves only the actual NBT tool when the hotbar is full");
        check(InventoryPolicy.destinationWithProtection(inventory,1<<1,protection)==2,"Explicit protected-slot masks still apply alongside NBT identity");
        inventory.setStack(8,ItemStack.EMPTY);check(InventoryPolicy.printerDestinationWithProtection(inventory,0,protection)==8,"Printer still prefers empty slots for batching");
        Predicate<ItemStack> material=InventoryTransfers.materialMatch(s->s.isOf(Items.STONE),protection);
        check(!material.test(tool)&&material.test(ordinary),"Held tool cannot satisfy a construction material request");
        check(InventoryTransfers.find(inventory,material)==1,"Search skips the held tool and chooses a normal hotbar stack");
        for(int i=1;i<9;i++)inventory.setStack(i,ItemStack.EMPTY);inventory.setStack(10,tool.copy());inventory.setStack(11,ordinary.copy());
        check(InventoryTransfers.find(inventory,material)==11,"Bag search also skips the exact tool identity");inventory.setStack(11,ItemStack.EMPTY);
        check(InventoryTransfers.find(inventory,material)==-1,"Only tool copies are reported as missing material, never consumed");
        check(InventoryTransfers.canCreateMaterial(true,true,Items.STONE,null,protection),"Creative picking may create ordinary stone while preserving a tagged stone tool");
        check(!InventoryTransfers.canCreateMaterial(true,true,null,tool,protection),"Exact creative requests cannot generate a protected tool as material");
        check(InventoryTransfers.canCreateMaterial(true,true,null,other,protection),"Unprotected exact NBT material retains normal local creative support");
        check(!InventoryTransfers.canCreateMaterial(true,false,null,other,protection),"The protection change does not enable remote exact creative picking");
        check(!InventoryTransfers.canCreateMaterial(false,false,null,null,protection),"Absent creative identity is rejected without dereferencing null");
        var disabled=cache.get(false,configured);check(!disabled.test(tool),"Disabling the tool permits its item as ordinary material");check(cache.get(true,configured)==protection,"Toggling enablement does not reparse the unchanged identity");
        var entireType=cache.get(true,"minecraft:stone");check(entireType.test(tool)&&entireType.test(ordinary),"A configuration without NBT protects the whole item type");
        check(!InventoryTransfers.canCreateMaterial(true,true,Items.STONE,null,entireType),"A plain configured tool cannot be repeatedly generated and consumed by the printer");
        check(protection.test(tool)&&!protection.test(ordinary),"An earlier predicate keeps its immutable identity after configuration changes");
        for(String empty:new String[]{"","empty","minecraft:air"}){
            var unprotected=cache.get(true,empty);check(!unprotected.test(ItemStack.EMPTY),"Empty-hand tools must not reserve empty hotbar slots");
            check(InventoryPolicy.printerDestinationWithProtection(inventory,0,unprotected)==1,"Empty-hand configuration permits an empty destination");
        }
        check(InventoryPolicy.destination(inventory,0,Items.STONE)==1,"Legacy Item-only policy keeps its existing behavior");
        boolean denied=false;try{InventoryPolicy.destinationWithProtection(inventory,511,protection);}catch(IllegalStateException expected){denied=true;}check(denied,"All explicitly protected slots still reject before any transfer");
        check(tool.getCount()==1&&tool.getNbt().getInt("Damage")==91&&ordinary.getCount()==64,"Protection and search never mutate source stacks or NBT");
        return checks;
    }
}
