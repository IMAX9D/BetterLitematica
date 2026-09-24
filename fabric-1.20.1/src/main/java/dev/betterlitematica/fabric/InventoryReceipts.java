package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.screen.ScreenHandler;
/** Tracks only server inventory messages; local predictions never advance this clock. */
public final class InventoryReceipts {
    private static final long[] slots=new long[41];private static long revision,containerRevision;
    private InventoryReceipts(){}
    static long stamp(int slot){return slot>=0&&slot<slots.length?slots[slot]:-1;}
    static long containerStamp(){return containerRevision;}
    static boolean completePlayerInventory(long seen){return (seen&((1L<<36)-1))==((1L<<36)-1);}
    private static void receipt(ScreenHandler handler,int slot){var player=MinecraftClient.getInstance().player;if(player==null||slot<0||slot>=handler.slots.size())return;var value=handler.getSlot(slot);int index=value.getIndex();if(value.inventory==player.getInventory()&&index>=0&&index<slots.length)slots[index]=++revision;}
    public static void inventory(InventoryS2CPacket packet){var player=MinecraftClient.getInstance().player;if(player==null)return;var handler=packet.getSyncId()==0?player.playerScreenHandler:player.currentScreenHandler;if(handler.syncId!=packet.getSyncId())return;long seen=0;for(int i=0;i<Math.min(handler.slots.size(),packet.getContents().size());i++){receipt(handler,i);var slot=handler.getSlot(i);if(slot.inventory==player.getInventory()&&slot.getIndex()>=0&&slot.getIndex()<36)seen|=1L<<slot.getIndex();}if(packet.getSyncId()>0&&completePlayerInventory(seen))containerRevision=++revision;}
    public static void slot(ScreenHandlerSlotUpdateS2CPacket packet){var player=MinecraftClient.getInstance().player;if(player==null)return;if(packet.getSyncId()==-2){int slot=packet.getSlot();if(slot>=0&&slot<slots.length)slots[slot]=++revision;return;}var handler=packet.getSyncId()==0?player.playerScreenHandler:player.currentScreenHandler;if(handler.syncId==packet.getSyncId())receipt(handler,packet.getSlot());}
    static void clear(){java.util.Arrays.fill(slots,0);revision=containerRevision=0;}
}
