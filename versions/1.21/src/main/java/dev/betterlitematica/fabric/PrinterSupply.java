package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.*;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.nbt.NbtElement;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import java.util.*;

/** One bounded, owned material request. GUI exceptions never authorize world actions. */
final class PrinterSupply {
    enum Source {NONE,AUTO,QUICK_SHULKER,AX_SHULKERS,TAKE_IT_OUT_2,TAKE_IT_OUT_3;
        String label(){return switch(this){case NONE->"关闭";case AUTO->"自动";case QUICK_SHULKER->"Quick Shulker";case AX_SHULKERS->"AxShulkers";case TAKE_IT_OUT_2->"TakeItOut 旧协议";case TAKE_IT_OUT_3->"TakeItOut 新协议";};}}
    private static final Identifier QUICK=Identifier.of("quickshulker","open_shulker_packet"),TAKE=Identifier.of("takeitout","getstack");
    private final MinecraftClient client;
    private ClientWorld world;private Object connection;private Item wanted;private int boxSlot,started,stage,ownedSync=-1;private boolean direct,contents;
    private Source chosen;private ItemStack box;private ScreenHandler owned;private long[] before;private String reason="";private long triedBoxes;
    private record Cooldown(int until,String reason){}
    private final Map<Item,Cooldown> cooldown=new LinkedHashMap<>();
    PrinterSupply(MinecraftClient client){this.client=client;}
    String reason(){return reason;}boolean active(){return wanted!=null;}
    private void cool(Item item,int tick){if(cooldown.size()>=128&&!cooldown.containsKey(item))cooldown.remove(cooldown.keySet().iterator().next());cooldown.put(item,new Cooldown(tick+100,reason));}
    static boolean candidate(ItemStack stack,Item item,Source source){return source==Source.AX_SHULKERS?stack.getItem() instanceof BlockItem block&&block.getBlock() instanceof ShulkerBoxBlock:inner(stack,item)>=0;}
    static int inner(ItemStack stack,Item wanted){
        if(!(stack.getItem() instanceof BlockItem block)||!(block.getBlock() instanceof ShulkerBoxBlock))return -1;
        var items=ItemDataBridge.contents(stack,27);for(int i=0;i<items.size();i++)if(items.get(i).isOf(wanted))return i;return -1;
    }
    static net.minecraft.network.PacketByteBuf takePayload(int inside,int box,boolean modern){if(inside<0||inside>=27||box<0||box>=36)throw new IllegalArgumentException("无效潜影盒格");var buf=PacketByteBufs.create();buf.writeVarInt(inside);buf.writeVarInt(box);if(modern)buf.writeBoolean(false);return buf;}
    boolean request(Item item,Source source,int tick){
        if(active())return wanted==item;reason="";if(source==Source.NONE||client.player.isCreative())return false;var delay=cooldown.get(item);if(delay!=null&&delay.until()>tick){reason=delay.reason();return false;}
        if(client.currentScreen!=null||client.player.currentScreenHandler!=client.player.playerScreenHandler||!client.player.playerScreenHandler.getCursorStack().isEmpty())return false;
        if(source==Source.AUTO)source=ClientPlayNetworking.canSend(QUICK)?Source.QUICK_SHULKER:Source.NONE;
        if(source==Source.NONE||source==Source.QUICK_SHULKER&&!ClientPlayNetworking.canSend(QUICK)||(source==Source.TAKE_IT_OUT_2||source==Source.TAKE_IT_OUT_3)&&!ClientPlayNetworking.canSend(TAKE)){reason="服务器未提供所选补给通道";cool(item,tick);return false;}
        int index=-1,inside=-1;for(int i=0;i<36;i++){var stack=client.player.getInventory().getStack(i);if(candidate(stack,item,source)){index=i;inside=inner(stack,item);break;}}if(index<0)return false;
        var candidate=client.player.getInventory().getStack(index);if(source!=Source.TAKE_IT_OUT_2&&source!=Source.TAKE_IT_OUT_3&&!hasSpace(item)){reason="背包已满";return false;}
        chosen=source;box=identity(candidate);boxSlot=index;wanted=item;world=client.world;connection=client.getNetworkHandler();started=tick;stage=0;owned=null;ownedSync=-1;contents=false;triedBoxes=1L<<index;before=new long[36];for(int i=0;i<36;i++)before[i]=InventoryReceipts.stamp(i);
        direct=source==Source.TAKE_IT_OUT_2||source==Source.TAKE_IT_OUT_3;
        if(source==Source.QUICK_SHULKER){var buf=PacketByteBufs.create();buf.writeInt(InventoryTransfers.playerSlot(index));LegacyPayloads.send(QUICK,buf);}
        else if(source==Source.AX_SHULKERS)client.getNetworkHandler().sendPacket(InventoryTransfers.click(client.player.playerScreenHandler,InventoryTransfers.playerSlot(index),1,SlotActionType.PICKUP));
        else LegacyPayloads.send(TAKE,takePayload(inside,index,source==Source.TAKE_IT_OUT_3));
        reason=direct?"等待补给确认":"等待潜影盒";return true;
    }
    private static ItemStack identity(ItemStack stack){return ItemDataBridge.withoutCustomKey(stack,"quickshulker");}
    private boolean hasSpace(Item item){for(int i=0;i<36;i++){var stack=client.player.getInventory().getStack(i);if(stack.isEmpty()||stack.isOf(item)&&stack.getCount()<stack.getMaxCount())return true;}return false;}
    void opened(int sync,ScreenHandlerType<?> type){
        if(!active()||direct)return;if(ownedSync!=-1||(chosen==Source.QUICK_SHULKER?type!=ScreenHandlerType.SHULKER_BOX:type!=ScreenHandlerType.SHULKER_BOX&&type!=ScreenHandlerType.GENERIC_9X3)){reset(false);throw new IllegalStateException("补给容器不匹配");}ownedSync=sync;owned=client.player.currentScreenHandler;
    }
    void inventory(int sync){if(active()&&sync==ownedSync)contents=true;}
    boolean tick(int tick){
        if(!active())return false;
        if(world!=client.world||connection!=client.getNetworkHandler()||client.player==null){reset(false);return false;}
        if(tick-started>100){Item item=wanted;reset(true);reason="补给未获确认";cool(item,tick);throw new IllegalStateException(reason);}
        if(direct){if(client.currentScreen!=null||client.player.currentScreenHandler!=client.player.playerScreenHandler){reset(false);throw new IllegalStateException("补给已中断");}if(confirmed()){reset(false);return true;}return true;}
        if(stage==2){
            if(client.currentScreen!=null||client.player.currentScreenHandler!=client.player.playerScreenHandler){reset(false);throw new IllegalStateException("补给已中断");}
            int next=-1;for(int i=0;i<36;i++)if((triedBoxes&(1L<<i))==0&&candidate(client.player.getInventory().getStack(i),wanted,Source.AX_SHULKERS)){next=i;break;}
            if(next<0){Item item=wanted;reset(false);reason="潜影盒中没有所需材料";cool(item,tick);return true;}
            boxSlot=next;triedBoxes|=1L<<next;box=identity(client.player.getInventory().getStack(next));started=tick;stage=0;
            client.getNetworkHandler().sendPacket(InventoryTransfers.click(client.player.playerScreenHandler,InventoryTransfers.playerSlot(next),1,SlotActionType.PICKUP));reason="等待潜影盒";return true;
        }
        if(stage==0){
            if(owned==null){if(client.currentScreen!=null){reset(false);throw new IllegalStateException("补给界面已变更");}return true;}
            if(!ownsScreen()){reset(false);throw new IllegalStateException("补给容器已关闭");}if(!contents)return true;
            if(!ItemStack.areEqual(box,identity(client.player.getInventory().getStack(boxSlot)))||owned.slots.size()!=63){reset(true);throw new IllegalStateException("潜影盒已更换");}
            int itemSlot=-1;for(int i=0;i<27;i++)if(owned.getSlot(i).getStack().isOf(wanted)){itemSlot=i;break;}
            if(itemSlot<0){if(chosen==Source.AX_SHULKERS){if(!owned.getCursorStack().isEmpty()){reset(false);throw new IllegalStateException("补给已由玩家接管");}client.player.closeHandledScreen();owned=null;ownedSync=-1;contents=false;stage=2;return true;}reset(true);throw new IllegalStateException("潜影盒中没有所需材料");}
            for(int i=0;i<36;i++)before[i]=InventoryReceipts.stamp(i);
            client.getNetworkHandler().sendPacket(InventoryTransfers.click(owned,itemSlot,0,SlotActionType.QUICK_MOVE));stage=1;reason="等待补给确认";return true;
        }
        if(!ownsScreen()){reset(false);throw new IllegalStateException("补给已中断");}
        if(confirmed()){reset(true);return true;}return true;
    }
    private boolean confirmed(){for(int i=0;i<36;i++)if(InventoryReceipts.stamp(i)>before[i]&&client.player.getInventory().getStack(i).isOf(wanted))return true;return false;}
    private boolean ownsScreen(){return owned!=null&&client.player.currentScreenHandler==owned&&client.currentScreen instanceof HandledScreen<?> screen&&screen.getScreenHandler()==owned;}
    void reset(boolean close){if(close&&client.player!=null&&ownsScreen()&&owned.getCursorStack().isEmpty())client.player.closeHandledScreen();wanted=null;world=null;connection=null;box=null;owned=null;before=null;ownedSync=-1;contents=false;stage=0;triedBoxes=0;}
    void clear(){reset(true);cooldown.clear();}
}
