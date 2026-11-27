package dev.betterlitematica.fabric;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

public final class PrinterInteractionGuard {
    private static PrinterInteractionGuard active,manualOwner;
    private static long blockPackets;
    static long blockPackets(){return blockPackets;}
    private final Minecraft client;
    private final PrinterInteractionTracker<Item> tracker=new PrinterInteractionTracker<>();
    private long manualUntil;private int manualSequence=-1;
    private Object world,connection;private boolean sending;private Item preparedItem;private long preparedPos;private Object preparedBlock;
    PrinterInteractionGuard(Minecraft client){this.client=client;}
    private void context(){if(world!=client.level||connection!=client.getConnection()){clear();world=client.level;connection=client.getConnection();}}
    boolean blocked(Item item){context();return tracker.blocked(item);}
    boolean waiting(){context();return tracker.waiting(System.nanoTime());}
    boolean allow(BlockPos pos,BlockState wanted){
        context();var item=wanted.getBlock().asItem();if(!tracker.allowed(item,System.nanoTime()))return false;

        preparedItem=manualWindowPending()||ClientUi.screen(client) instanceof AbstractContainerScreen<?>?null:item;
        preparedPos=pos.asLong();preparedBlock=wanted.getBlock();if(preparedItem!=null)active=this;return true;
    }
    void sending(boolean value){sending=value;if(!value){preparedItem=null;preparedBlock=null;if(!tracker.tracking()&&active==this)active=null;}}
    private boolean manualWindowPending(){
        if(manualOwner!=this)return false;if(System.nanoTime()>=manualUntil){manualOwner=null;return false;}return true;
    }
    public static void sent(net.minecraft.network.protocol.Packet<?> packet){
        if(packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket)blockPackets++;
        var manual=manualOwner;
        if(manual!=null&&!manual.sending){
            if(packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket use)manual.manualSequence=use.sequence();
            else if(packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket use)manual.manualSequence=use.sequence();
        }
        var guard=active;if(guard!=null&&guard.sending&&guard.preparedItem!=null&&packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket use)
            guard.tracker.sent(guard.preparedItem,guard.preparedPos,guard.preparedBlock,use.sequence(),System.nanoTime());
    }
    public static void acknowledged(int sequence){
        var manual=manualOwner;if(manual!=null&&manual.client.isSameThread()&&manual.manualSequence>=0&&sequence>=manual.manualSequence)manualOwner=null;
        var guard=active;if(guard==null||!guard.client.isSameThread())return;guard.context();guard.tracker.acknowledged(sequence,System.nanoTime());
    }
    void confirmed(BlockPos pos,BlockState actual){context();tracker.confirmed(pos.asLong(),actual.getBlock(),System.nanoTime());}
    void cancel(){tracker.forget();sending=false;preparedItem=null;preparedBlock=null;if(active==this)active=null;}
    void clear(){cancel();tracker.clear();if(manualOwner==this)manualOwner=null;}
    public static void manualUse(){
        var guard=active;if(guard!=null&&!guard.sending){guard.cancel();manualOwner=guard;guard.manualSequence=-1;guard.manualUntil=System.nanoTime()+5_000_000_000L;}
    }
    public static boolean opened(ClientboundOpenScreenPacket packet){
        var guard=active;if(guard==null||!guard.client.isSameThread())return false;guard.context();var client=guard.client;
        if(client.player==null||ClientUi.screen(client) instanceof AbstractContainerScreen<?>||!client.player.containerMenu.getCarried().isEmpty()){guard.cancel();return false;}
        var decision=guard.tracker.opened(System.nanoTime());if(!decision.owned())return false;

        client.getConnection().send(new ServerboundContainerClosePacket(packet.getContainerId()));
        client.player.containerMenu=client.player.inventoryMenu;
        if(decision.blockedItem()!=null){
            var item=decision.blockedItem();ClientUi.message(client,Component.literal("放置触发容器，已跳过：").append(item.getName(new net.minecraft.world.item.ItemStack(item))),false);
            org.slf4j.LoggerFactory.getLogger("BetterLitematica").warn("Printer placement opened container: item={}, sync={}, type={}; skipped for this print session",BuiltInRegistries.ITEM.getId(item),packet.getContainerId(),BuiltInRegistries.MENU.getId(packet.getType()));
        }else if(decision.diagnosing()){
            ClientUi.message(client,Component.literal("打印触发了容器，正在确认对应材料"),false);
            org.slf4j.LoggerFactory.getLogger("BetterLitematica").info("Printer replacement menu has multiple candidate materials; diagnosing only after observed menu, sync={}",packet.getContainerId());
        }
        return true;
    }
}
