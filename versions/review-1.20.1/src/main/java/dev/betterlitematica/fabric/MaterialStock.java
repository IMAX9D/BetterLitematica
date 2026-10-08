package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtElement;

/** Client-tick stock snapshot. Unknown remote stock is never reported as zero. */
final class MaterialStock {
    private final MinecraftClient client;
    private final Map<Item,Long> counts=new HashMap<>();
    private Object world;private int nextTick;private long inventoryRevision;private boolean remoteUnknown;
    MaterialStock(MinecraftClient client){this.client=client;}
    long available(Item item){return client.player!=null&&client.player.isCreative()?Long.MAX_VALUE:counts.getOrDefault(item,remoteUnknown?-1L:0L);}
    void refresh(int tick,PrinterSupply.Source source){
        if(world!=client.world){world=client.world;nextTick=0;counts.clear();RemoteSupply.clearSnapshot();}
        long revision=0;for(int i=0;i<36;i++)revision=Math.max(revision,InventoryReceipts.stamp(i));
        if(tick<nextTick&&revision==inventoryRevision)return;inventoryRevision=revision;nextTick=tick+4;counts.clear();remoteUnknown=false;
        if(client.player==null)return;
        for(int i=0;i<36;i++){
            var stack=client.player.getInventory().getStack(i);if(stack.isEmpty())continue;
            counts.merge(stack.getItem(),(long)stack.getCount(),Long::sum);
            if(source==PrinterSupply.Source.NONE)continue;
            var tag=BlockItem.getBlockEntityNbt(stack);
            if(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)||tag==null)continue;
            var entries=tag.getList("Items",NbtElement.COMPOUND_TYPE);
            for(int j=0;j<Math.min(27,entries.size());j++){var inner=ItemStack.fromNbt(entries.getCompound(j));if(!inner.isEmpty())counts.merge(inner.getItem(),(long)inner.getCount(),Long::sum);}
        }
        if(source==PrinterSupply.Source.AUTO&&RemoteSupply.available(client)){
            var remote=RemoteSupply.snapshot(client);
            if(remote==null)remoteUnknown=true;else remote.forEach((item,count)->counts.merge(item,count,Long::sum));
        }
    }
}
