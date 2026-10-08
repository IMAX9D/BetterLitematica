package dev.betterlitematica.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
/** Tracks only server inventory messages; local predictions never advance this clock. */
public final class InventoryReceipts {
    private static final long[] slots=new long[41];private static long revision,containerRevision;
    private InventoryReceipts(){}
    static long stamp(int slot){return slot>=0&&slot<slots.length?slots[slot]:-1;}
    static long containerStamp(){return containerRevision;}
    static boolean completePlayerInventory(long seen){return (seen&((1L<<36)-1))==((1L<<36)-1);}
    private static void receipt(AbstractContainerMenu handler,int slot){var player=Minecraft.getInstance().player;if(player==null||slot<0||slot>=handler.slots.size())return;var value=handler.getSlot(slot);int index=value.getContainerSlot();if(value.container==player.getInventory()&&index>=0&&index<slots.length)slots[index]=++revision;}
    public static void inventory(ClientboundContainerSetContentPacket packet){var player=Minecraft.getInstance().player;if(player==null)return;var handler=packet.containerId()==0?player.inventoryMenu:player.containerMenu;if(handler.containerId!=packet.containerId())return;long seen=0;for(int i=0;i<Math.min(handler.slots.size(),packet.items().size());i++){receipt(handler,i);var slot=handler.getSlot(i);if(slot.container==player.getInventory()&&slot.getContainerSlot()>=0&&slot.getContainerSlot()<36)seen|=1L<<slot.getContainerSlot();}if(packet.containerId()>0&&completePlayerInventory(seen))containerRevision=++revision;}
    public static void slot(ClientboundContainerSetSlotPacket packet){var player=Minecraft.getInstance().player;if(player==null)return;if(packet.getContainerId()==-2){int slot=packet.getSlot();if(slot>=0&&slot<slots.length)slots[slot]=++revision;return;}var handler=packet.getContainerId()==0?player.inventoryMenu:player.containerMenu;if(handler.containerId==packet.getContainerId())receipt(handler,packet.getSlot());}
    public static void playerInventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet){if(Minecraft.getInstance().player!=null&&packet.slot()>=0&&packet.slot()<slots.length)slots[packet.slot()]=++revision;}
    static void clear(){java.util.Arrays.fill(slots,0);revision=containerRevision=0;}
}
