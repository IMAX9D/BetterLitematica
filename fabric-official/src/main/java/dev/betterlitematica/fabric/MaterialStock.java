package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.*;
import net.minecraft.nbt.Tag;

/** Bounded inventory and enabled shulker-source stock snapshot, refreshed on client ticks. */
final class MaterialStock {
    private final Minecraft client;
    private final Map<Item,Long> counts=new HashMap<>();
    private Object world;private int nextTick;private long inventoryRevision;
    MaterialStock(Minecraft client){this.client=client;}
    long available(Item item){return client.player!=null&&client.player.isCreative()?Long.MAX_VALUE:counts.getOrDefault(item,0L);}
    void refresh(int tick,PrinterSupply.Source source){
        if(world!=client.level){world=client.level;nextTick=0;counts.clear();}
        long revision=0;for(int i=0;i<36;i++)revision=Math.max(revision,InventoryReceipts.stamp(i));
        if(tick<nextTick&&revision==inventoryRevision)return;inventoryRevision=revision;nextTick=tick+4;counts.clear();
        if(client.player==null)return;
        for(int i=0;i<36;i++){
            var stack=client.player.getInventory().getItem(i);if(stack.isEmpty())continue;
            counts.merge(stack.getItem(),(long)stack.getCount(),Long::sum);
            if(source==PrinterSupply.Source.NONE)continue;
            if(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))continue;
            for(var inner:ItemDataBridge.contents(stack,27))if(!inner.isEmpty())counts.merge(inner.getItem(),(long)inner.getCount(),Long::sum);
        }

    }
}
