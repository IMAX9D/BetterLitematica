package dev.betterlitematica.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class PrinterInteractionGuard {
    private static PrinterInteractionGuard active,manualOwner;
    private static long blockPackets;
    private static int legacySequence;
    static long blockPackets(){return blockPackets;}
    private final MinecraftClient client;
    private final PrinterInteractionTracker<Item> tracker=new PrinterInteractionTracker<>();
    private long manualUntil;private int manualSequence=-1;
    private Object world,connection;private boolean sending;private Item preparedItem;private long preparedPos;private Object preparedBlock;
    PrinterInteractionGuard(MinecraftClient client){this.client=client;}
    private void context(){if(world!=client.world||connection!=client.getNetworkHandler()){clear();world=client.world;connection=client.getNetworkHandler();}}
    boolean blocked(Item item){context();return tracker.blocked(item);}
    boolean waiting(){context();return tracker.waiting(System.nanoTime());}
    boolean allow(BlockPos pos,BlockState wanted){
        context();var item=wanted.getBlock().asItem();if(!tracker.allowed(item,System.nanoTime()))return false;

        preparedItem=manualWindowPending()||client.currentScreen instanceof HandledScreen<?>?null:item;
        preparedPos=pos.asLong();preparedBlock=wanted.getBlock();if(preparedItem!=null)active=this;return true;
    }
    void sending(boolean value){sending=value;if(!value){preparedItem=null;preparedBlock=null;if(!tracker.tracking()&&active==this)active=null;}}
    private boolean manualWindowPending(){
        if(manualOwner!=this)return false;if(System.nanoTime()>=manualUntil){manualOwner=null;return false;}return true;
    }
    public static void sent(net.minecraft.network.Packet<?> packet){
        if(packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket)blockPackets++;
        var manual=manualOwner;
        if(manual!=null&&!manual.sending){
            if(packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket use)manual.manualSequence=++legacySequence;
            else if(packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket use)manual.manualSequence=++legacySequence;
        }
        var guard=active;if(guard!=null&&guard.sending&&guard.preparedItem!=null&&packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket use)
            guard.tracker.sent(guard.preparedItem,guard.preparedPos,guard.preparedBlock,++legacySequence,System.nanoTime());
    }
    public static void acknowledged(int sequence){
        var manual=manualOwner;if(manual!=null&&manual.client.isOnThread()&&manual.manualSequence>=0&&sequence>=manual.manualSequence)manualOwner=null;
        var guard=active;if(guard==null||!guard.client.isOnThread())return;guard.context();guard.tracker.acknowledged(sequence,System.nanoTime());
    }
    void confirmed(BlockPos pos,BlockState actual){context();tracker.confirmedLegacy(pos.asLong(),actual.getBlock(),System.nanoTime());}
    void cancel(){tracker.forget();sending=false;preparedItem=null;preparedBlock=null;if(active==this)active=null;}
    void clear(){cancel();tracker.clear();if(manualOwner==this)manualOwner=null;}
    public static void manualUse(){
        var guard=active;if(guard!=null&&!guard.sending){guard.cancel();manualOwner=guard;guard.manualSequence=-1;guard.manualUntil=System.nanoTime()+5_000_000_000L;}
    }
    public static boolean opened(OpenScreenS2CPacket packet){
        var guard=active;if(guard==null||!guard.client.isOnThread())return false;guard.context();var client=guard.client;
        if(client.player==null||client.currentScreen instanceof HandledScreen<?>||!client.player.currentScreenHandler.getCursorStack().isEmpty()){guard.cancel();return false;}
        var decision=guard.tracker.opened(System.nanoTime());if(!decision.owned())return false;

        client.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(packet.getSyncId()));
        client.player.currentScreenHandler=client.player.playerScreenHandler;
        if(decision.blockedItem()!=null){
            var item=decision.blockedItem();client.player.sendMessage(new net.minecraft.text.LiteralText("放置触发容器，已跳过：").append(item.getName()),false);
            org.slf4j.LoggerFactory.getLogger("BetterLitematica").warn("Printer placement opened container: item={}, sync={}, type={}; skipped for this print session",Registry.ITEM.getId(item),packet.getSyncId(),Registry.SCREEN_HANDLER.getId(packet.getScreenHandlerType()));
        }else if(decision.diagnosing()){
            client.player.sendMessage(new net.minecraft.text.LiteralText("打印触发了容器，正在确认对应材料"),false);
            org.slf4j.LoggerFactory.getLogger("BetterLitematica").info("Printer replacement menu has multiple candidate materials; diagnosing only after observed menu, sync={}",packet.getSyncId());
        }
        return true;
    }
}
