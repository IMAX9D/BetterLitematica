package com.retrievepanel.printer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.class_2540;
public final class TrashPacket {
    public static class_2540 payload(int menuSlot){
        if(menuSlot<0||menuSlot>=512)throw new IllegalArgumentException("禁止批量或特殊槽位删除");
        var buf=PacketByteBufs.create();buf.method_10804(menuSlot);buf.writeBoolean(false);return buf;
    }
}
