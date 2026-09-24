package dev.betterlitematica.fabric;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Presentation only: never mutates a source snapshot, inventory, or item NBT. */
final class ProjectionInventoryView {
    private static final int MAX_SLOTS=256;
    private ProjectionInventoryView() {}
    static ProjectionInfoData.Side compact(ProjectionInfoData.Side source){
        if(source.items().size()>MAX_SLOTS)throw new IllegalArgumentException("Inventory display exceeds 256 slots");
        var block=source.state()==null?null:source.state().getBlock();
        boolean merge=block instanceof ChestBlock||block instanceof BarrelBlock||block instanceof ShulkerBoxBlock;
        var items=new ArrayList<ItemStack>();
        if(merge){
            for(var original:source.items()){
                if(original.isEmpty())continue;
                ItemStack match=null;
                for(var existing:items)if(ItemStack.canCombine(existing,original)){match=existing;break;}
                if(match==null)items.add(original.copy());
                else {
                    long count=(long)match.getCount()+original.getCount();
                    if(count>Integer.MAX_VALUE){
                        // Preserve exact original counts if the display's integer count cannot represent the sum.
                        String message=source.availability().isEmpty()?"合计数量超过显示范围":source.availability()+" · 合计数量超过显示范围";
                        return new ProjectionInfoData.Side(source.state(),message,source.nbt(),copies(source.items()),source.columns());
                    }
                    match.setCount((int)count);
                }
            }
        }else items.addAll(copies(source.items()));
        int columns=merge?9:block instanceof DispenserBlock?3:block instanceof HopperBlock?5:block instanceof AbstractFurnaceBlock?3:source.columns();
        return new ProjectionInfoData.Side(source.state(),source.availability(),source.nbt(),items,columns);
    }
    private static List<ItemStack> copies(List<ItemStack> source){
        var result=new ArrayList<ItemStack>(source.size());
        for(var stack:source)result.add(stack.isEmpty()?new ItemStack(Items.AIR,0):stack.copy());
        return result;
    }
}
