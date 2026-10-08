package dev.betterlitematica.fabric;

import it.unimi.dsi.fastutil.ints.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

/** Hash exactly the predicted state; an unpredicted transfer still waits for server receipts. */
final class ClickPackets {
    private ClickPackets(){}
    static ClickSlotC2SPacket create(int sync,int revision,int slot,int button,SlotActionType action,ItemStack cursor,Int2ObjectMap<ItemStack> changed){
        if(slot<Short.MIN_VALUE||slot>Short.MAX_VALUE||button<Byte.MIN_VALUE||button>Byte.MAX_VALUE)throw new IllegalArgumentException("容器操作索引超出协议范围");
        var connection=MinecraftClient.getInstance().getNetworkHandler();if(connection==null)throw new IllegalStateException("连接已断开");
        var hasher=connection.method_68823();var hashes=new Int2ObjectOpenHashMap<ItemStackHash>();
        changed.forEach((key,stack)->hashes.put(key.intValue(),ItemStackHash.fromItemStack(stack,hasher)));
        return new ClickSlotC2SPacket(sync,revision,(short)slot,(byte)button,action,hashes,ItemStackHash.fromItemStack(cursor,hasher));
    }
}
