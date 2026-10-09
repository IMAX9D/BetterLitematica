package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.function.Predicate;
/** Real bag transfers await receipts. Integrated creative picking follows vanilla's local pick flow. */
final class InventoryTransfers {
    enum Result {READY,WAIT,MISSING}
    private final MinecraftClient client;
    private final java.util.function.Supplier<InteractionOptions> options;
    private final CreativeEchoes creativeEchoes=new CreativeEchoes();
    private final InventoryPolicy.ToolProtection toolProtection=new InventoryPolicy.ToolProtection();
    /** Vanilla applies old S2C slot values directly. Keep our latest ordered C2S intent per slot. */
    static final class CreativeEchoes {
        private final ItemStack[] latest=new ItemStack[9];
        void sent(int slot,ItemStack stack){latest[slot]=stack.copy();}
        boolean reassert(int slot,ItemStack displayed){return latest[slot]!=null&&!ItemStack.areEqual(latest[slot],displayed);}
        void clear(){java.util.Arrays.fill(latest,null);}
    }
    private Predicate<ItemStack> pending;private int slot,startedTick;private long stamp,containerStamp;private boolean echo,watched;
    InventoryTransfers(MinecraftClient client,java.util.function.Supplier<InteractionOptions> options){this.client=client;this.options=options;}
    void reset(){watched=false;}
    boolean inFlight(){return pending!=null;}
    private void clearPending(){pending=null;watched=false;}
    void clear(){clearPending();creativeEchoes.clear();}
    String settle(int tick){if(pending==null)return null;var p=client.player;if(p==null){clear();return null;}if(InventoryReceipts.containerStamp()>containerStamp){clearPending();return null;}if(confirmed(tick,startedTick,echo,stamp,InventoryReceipts.stamp(slot),pending.test(p.getInventory().getStack(slot)))){clearPending();return null;}if(tick-startedTick>100){boolean report=watched&&client.currentScreen==null;watched=false;return report?"换手未确认，请打开容器后重试":null;}return null;}
    static boolean confirmed(int tick,int started,boolean echo,long before,long after,boolean expected){return tick>started&&expected&&(!echo||after>before);}
    static int playerSlot(int inventorySlot){if(inventorySlot<0||inventorySlot>=36)throw new IllegalArgumentException("无效背包格");return inventorySlot<9?inventorySlot+36:inventorySlot;}
    static ClickSlotC2SPacket click(ScreenHandler handler,int slot,int button,SlotActionType type){
        if(slot<0||slot>=handler.slots.size()||!handler.getCursorStack().isEmpty())throw new IllegalStateException("请先放下光标上的物品");
        return new ClickSlotC2SPacket(handler.syncId,handler.getRevision(),slot,button,type,ItemStack.EMPTY,new Int2ObjectOpenHashMap<>());
    }
    Result equip(Item item,int tick){
        return equip(stack->stack.isOf(item),item,tick,false);
    }
    Result equip(Predicate<ItemStack> matches,int tick){return equip(matches,null,tick,false);}
    Result equipForPrinter(Item item,int tick){return equip(stack->stack.isOf(item),item,tick,true);}
    Result equipForPrinter(Predicate<ItemStack> matches,int tick){return equip(matches,null,tick,true);}
    Result equipForPrinter(ItemStack wanted,int tick){
        var exact=wanted.copy();return equip(stack->matchesStack(stack,exact),null,tick,true,exact);
    }
    Result equipContainerForPrinter(ItemStack wanted,int tick){
        var exact=wanted.copy();return equip(stack->matchesStack(stack,exact),null,tick,true,exact,true);
    }
    Result equipForPrinter(Item item,int tick,int reservedSlots){return equip(stack->stack.isOf(item),item,tick,true,null,false,reservedSlots);}
    boolean usableForPrinter(ItemStack stack){var s=options.get();return !toolProtection.get(s.tool,s.toolItem).test(stack);}
    static boolean matchesStack(ItemStack actual,ItemStack wanted){return wanted.isEmpty()?actual.isEmpty():!actual.isEmpty()&&ItemStack.canCombine(actual,wanted);}
    static boolean canCreate(boolean creative,boolean localCreative,Item item,ItemStack exact){
        return exact!=null?creative&&localCreative&&!exact.isEmpty():creative&&item!=null&&item!=Items.AIR;
    }
    static boolean canCreateMaterial(boolean creative,boolean localCreative,Item item,ItemStack exact,Predicate<ItemStack> protection){
        return canCreate(creative,localCreative,item,exact)&&!protection.test(exact!=null?exact:new ItemStack(item));
    }
    static int find(net.minecraft.entity.player.PlayerInventory inventory,Predicate<ItemStack> matches){
        if(matches.test(inventory.getMainHandStack()))return inventory.selectedSlot;
        for(int i=0;i<36;i++)if(matches.test(inventory.getStack(i)))return i;
        return -1;
    }
    static Predicate<ItemStack> materialMatch(Predicate<ItemStack> matches,Predicate<ItemStack> protection){return stack->!protection.test(stack)&&matches.test(stack);}
    static void creativePick(net.minecraft.entity.player.PlayerInventory inventory,Item item,int destination,java.util.function.Consumer<ItemStack> send,Runnable select){
        if(item==Items.AIR)throw new IllegalArgumentException("Invalid creative pick");
        creativePick(inventory,new ItemStack(item),destination,send,select);
    }
    static void creativePick(net.minecraft.entity.player.PlayerInventory inventory,ItemStack source,int destination,java.util.function.Consumer<ItemStack> send,Runnable select){
        if(destination<0||destination>=9)throw new IllegalArgumentException("Invalid creative pick");
        ItemStack previous=inventory.getStack(destination),picked=source.copy();inventory.setStack(destination,picked);
        try{send.accept(picked);}catch(RuntimeException e){inventory.setStack(destination,previous);throw e;}
        inventory.selectedSlot=destination;select.run();
    }
    private void sendCreative(ItemStack stack,int destination){client.interactionManager.clickCreativeStack(stack,36+destination);creativeEchoes.sent(destination,stack);}
    private boolean reconcileCreative(net.minecraft.entity.player.PlayerInventory inventory,int source,boolean localCreative){
        if(!localCreative||!creativeEchoes.reassert(source,inventory.getStack(source)))return true;
        var expected=inventory.getStack(source);
        // Do not suppress a server update or claim it confirms anything: re-send the current
        // wanted stack through vanilla before the following interaction in packet order.
        sendCreative(expected,source);return true;
    }
    private Result equip(Predicate<ItemStack> matches,Item creativeItem,int tick,boolean retainMaterials){
        return equip(matches,creativeItem,tick,retainMaterials,null);
    }
    private Result equip(Predicate<ItemStack> matches,Item creativeItem,int tick,boolean retainMaterials,ItemStack exact){
        return equip(matches,creativeItem,tick,retainMaterials,exact,false);
    }
    private Result equip(Predicate<ItemStack> matches,Item creativeItem,int tick,boolean retainMaterials,ItemStack exact,boolean container){return equip(matches,creativeItem,tick,retainMaterials,exact,container,0);}
    private Result equip(Predicate<ItemStack> matches,Item creativeItem,int tick,boolean retainMaterials,ItemStack exact,boolean container,int reservedSlots){
        var player=client.player;var inv=player.getInventory();
        if(pending!=null){
            if(InventoryReceipts.containerStamp()<=containerStamp&&!confirmed(tick,startedTick,echo,stamp,InventoryReceipts.stamp(slot),pending.test(inv.getStack(slot)))){if(tick-startedTick>100)throw new IllegalStateException("换手未确认，请打开容器后重试");return Result.WAIT;}
            clearPending();
        }
        if(player.currentScreenHandler!=player.playerScreenHandler)return Result.WAIT;
        var settings=options.get();var protection=toolProtection.get(settings.tool,settings.toolItem);var material=materialMatch(matches,protection);
        boolean localCreative=client.getServer()!=null&&player.isCreative()&&client.interactionManager.getCurrentGameMode().isCreative();
        boolean creativePick=localCreative||container&&player.isCreative()&&client.interactionManager.getCurrentGameMode().isCreative();
        if(material.test(inv.getMainHandStack()))return reconcileCreative(inv,inv.selectedSlot,creativePick)?Result.READY:Result.MISSING;
        int source=find(inv,material);
        if(source<0&&!canCreateMaterial(player.isCreative(),creativePick,creativeItem,exact,protection))return Result.MISSING;
        if(source>=0&&source<9){if(!reconcileCreative(inv,source,creativePick))return Result.MISSING;inv.selectedSlot=source;client.interactionManager.syncSelectedSlot();return Result.READY;}
        int destination=retainMaterials?InventoryPolicy.printerDestinationWithProtection(inv,settings.protectedHotbar|reservedSlots,protection):InventoryPolicy.destinationWithProtection(inv,settings.protectedHotbar|reservedSlots,protection);
        if(retainMaterials&&creativePick){
            var picked=source>=9?inv.getStack(source):exact!=null?exact:new ItemStack(creativeItem);
            // Same ordering as the vanilla creative pick operation. No inventory receipt is
            // forged; real SWAP transactions above still have to settle before this path runs.
            creativePick(inv,picked,destination,stack->sendCreative(stack,destination),()->client.interactionManager.syncSelectedSlot());
            return Result.READY;
        }
        // Validate before arming the in-flight transaction: an unsent click has no receipt.
        var swap=source>=9?click(player.playerScreenHandler,source,destination,SlotActionType.SWAP):null;
        pending=matches;watched=true;startedTick=tick;slot=destination;stamp=InventoryReceipts.stamp(slot);containerStamp=InventoryReceipts.containerStamp();echo=source<0||source>=9;
        if(inv.selectedSlot!=slot){inv.selectedSlot=slot;client.interactionManager.syncSelectedSlot();}
        if(swap!=null)client.getNetworkHandler().sendPacket(swap);
        else if(source<0)sendCreative(new ItemStack(creativeItem),slot);
        return Result.WAIT;
    }
}
