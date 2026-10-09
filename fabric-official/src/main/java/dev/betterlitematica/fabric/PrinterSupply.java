package dev.betterlitematica.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.*;
/** One bounded, owned material request. GUI exceptions never authorize world actions. */
final class PrinterSupply {
    enum Source {NONE,AUTO,QUICK_SHULKER,AX_SHULKERS,TAKE_IT_OUT_2,TAKE_IT_OUT_3;
        String label(){return switch(this){case NONE->"关闭";case AUTO->"自动";case QUICK_SHULKER->"Quick Shulker";case AX_SHULKERS->"AxShulkers";case TAKE_IT_OUT_2->"TakeItOut 旧协议";case TAKE_IT_OUT_3->"TakeItOut 新协议";};}}
    private static final Identifier QUICK=Identifier.fromNamespaceAndPath("quickshulker","open_shulker_packet"),TAKE=Identifier.fromNamespaceAndPath("takeitout","getstack");
    private final Minecraft client;private final MaterialStock stock;
    MaterialStock stock(){return stock;}
    private ClientLevel world;private Object connection;private Item wanted;private int boxSlot,started,stage,ownedSync=-1;private boolean direct,contents;
    private Source chosen;private ItemStack box;private AbstractContainerMenu owned;private long[] before;private PrinterReason reason=PrinterReason.NONE;private long triedBoxes;
    private record Cooldown(int until,PrinterReason reason){}
    private final Map<Item,Cooldown> cooldown=new LinkedHashMap<>();
    PrinterSupply(Minecraft client){this.client=client;stock=new MaterialStock(client);}
    String reason(){return reason.description();} PrinterReason typedReason(){return reason;}boolean active(){return wanted!=null;}
    private void cool(Item item,int tick){if(cooldown.size()>=128&&!cooldown.containsKey(item))cooldown.remove(cooldown.keySet().iterator().next());cooldown.put(item,new Cooldown(tick+100,reason));}
    static boolean candidate(ItemStack stack,Item item,Source source){return source==Source.AX_SHULKERS?stack.getItem() instanceof BlockItem block&&block.getBlock() instanceof ShulkerBoxBlock:inner(stack,item)>=0;}
    static int inner(ItemStack stack,Item wanted){
        if(!(stack.getItem() instanceof BlockItem block)||!(block.getBlock() instanceof ShulkerBoxBlock))return -1;
        try{var items=ItemDataBridge.contents(stack,27);for(int i=0;i<items.size();i++)if(items.get(i).is(wanted))return i;}catch(RuntimeException malformed){return -1;}return -1;
    }
    static net.minecraft.network.FriendlyByteBuf takePayload(int inside,int box,boolean modern){if(inside<0||inside>=27||box<0||box>=36)throw new IllegalArgumentException("无效潜影盒格");var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());buf.writeVarInt(inside);buf.writeVarInt(box);if(modern)buf.writeBoolean(false);return buf;}
    boolean request(Item item,Source source,int tick){
        if(active())return wanted==item;reason=PrinterReason.of(PrinterReason.Id.NONE,"");if(source==Source.NONE||client.player.isCreative())return false;var delay=cooldown.get(item);if(delay!=null&&delay.until()>tick){reason=delay.reason();return false;}
        if(ClientUi.screen(client)!=null||client.player.containerMenu!=client.player.inventoryMenu||!client.player.inventoryMenu.getCarried().isEmpty())return false;
        if(source==Source.AUTO)source=ClientPlayNetworking.canSend(QUICK)?Source.QUICK_SHULKER:Source.NONE;
        if(source==Source.NONE)return false;
        if(source==Source.QUICK_SHULKER&&!ClientPlayNetworking.canSend(QUICK)||(source==Source.TAKE_IT_OUT_2||source==Source.TAKE_IT_OUT_3)&&!ClientPlayNetworking.canSend(TAKE)){reason=PrinterReason.of(PrinterReason.Id.SUPPLY_UNAVAILABLE,"服务器未提供所选补给通道");cool(item,tick);return false;}
        int index=-1,inside=-1;for(int i=0;i<36;i++){var stack=client.player.getInventory().getItem(i);if(candidate(stack,item,source)){index=i;inside=inner(stack,item);break;}}if(index<0)return false;
        var candidate=client.player.getInventory().getItem(index);if(source!=Source.TAKE_IT_OUT_2&&source!=Source.TAKE_IT_OUT_3&&!hasSpace(item)){reason=PrinterReason.of(PrinterReason.Id.INVENTORY_FULL,"背包已满");return false;}
        chosen=source;box=identity(candidate);boxSlot=index;wanted=item;world=client.level;connection=client.getConnection();started=tick;stage=0;owned=null;ownedSync=-1;contents=false;triedBoxes=1L<<index;before=new long[36];for(int i=0;i<36;i++)before[i]=InventoryReceipts.stamp(i);
        direct=source==Source.TAKE_IT_OUT_2||source==Source.TAKE_IT_OUT_3;
        if(source==Source.QUICK_SHULKER){var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());buf.writeInt(InventoryTransfers.playerSlot(index));LegacyPayloads.send(QUICK,buf);}
        else if(source==Source.AX_SHULKERS)client.getConnection().send(InventoryTransfers.click(client.player.inventoryMenu,InventoryTransfers.playerSlot(index),1,ContainerInput.PICKUP));
        else LegacyPayloads.send(TAKE,takePayload(inside,index,source==Source.TAKE_IT_OUT_3));
        reason=direct?PrinterReason.of(PrinterReason.Id.SUPPLY,"等待补给确认"):PrinterReason.of(PrinterReason.Id.SUPPLY,"等待潜影盒");return true;
    }
    private static ItemStack identity(ItemStack stack){return ItemDataBridge.withoutCustomKey(stack,"quickshulker");}
    private boolean hasSpace(Item item){for(int i=0;i<36;i++){var stack=client.player.getInventory().getItem(i);if(stack.isEmpty()||stack.is(item)&&stack.getCount()<stack.getMaxStackSize())return true;}return false;}
    void opened(int sync,MenuType<?> type){
        if(!active()||direct)return;if(ownedSync!=-1||(chosen==Source.QUICK_SHULKER?type!=MenuType.SHULKER_BOX:type!=MenuType.SHULKER_BOX&&type!=MenuType.GENERIC_9x3)){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给容器不匹配"));}ownedSync=sync;owned=client.player.containerMenu;
    }
    void inventory(int sync){if(active()&&sync==ownedSync)contents=true;}
    boolean tick(int tick){
        if(!active())return false;
        if(world!=client.level||connection!=client.getConnection()||client.player==null){reset(false);return false;}
        if(tick-started>100){Item item=wanted;reset(true);reason=PrinterReason.of(PrinterReason.Id.CONFIRM_TIMEOUT,"补给未获确认");cool(item,tick);throw new PrinterReason.Failure(reason);}
        if(direct){if(ClientUi.screen(client)!=null||client.player.containerMenu!=client.player.inventoryMenu){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已中断"));}if(confirmed()){reset(false);return true;}return true;}
        if(stage==2){
            if(ClientUi.screen(client)!=null||client.player.containerMenu!=client.player.inventoryMenu){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已中断"));}
            int next=-1;for(int i=0;i<36;i++)if((triedBoxes&(1L<<i))==0&&candidate(client.player.getInventory().getItem(i),wanted,Source.AX_SHULKERS)){next=i;break;}
            if(next<0){Item item=wanted;reset(false);reason=PrinterReason.of(PrinterReason.Id.MISSING,"潜影盒中没有所需材料");cool(item,tick);return true;}
            boxSlot=next;triedBoxes|=1L<<next;box=identity(client.player.getInventory().getItem(next));started=tick;stage=0;
            client.getConnection().send(InventoryTransfers.click(client.player.inventoryMenu,InventoryTransfers.playerSlot(next),1,ContainerInput.PICKUP));reason=PrinterReason.of(PrinterReason.Id.SUPPLY,"等待潜影盒");return true;
        }
        if(stage==0){
            if(owned==null){if(ClientUi.screen(client)!=null){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给界面已变更"));}return true;}
            if(!ownsScreen()){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给容器已关闭"));}if(!contents)return true;
            if(!ItemStack.matches(box,identity(client.player.getInventory().getItem(boxSlot)))||owned.slots.size()!=63){reset(true);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"潜影盒已更换"));}
            int itemSlot=-1;for(int i=0;i<27;i++)if(owned.getSlot(i).getItem().is(wanted)){itemSlot=i;break;}
            if(itemSlot<0){if(chosen==Source.AX_SHULKERS){if(!owned.getCarried().isEmpty()){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已由玩家接管"));}client.player.closeContainer();owned=null;ownedSync=-1;contents=false;stage=2;return true;}reset(true);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.MISSING,"潜影盒中没有所需材料"));}
            for(int i=0;i<36;i++)before[i]=InventoryReceipts.stamp(i);
            client.getConnection().send(InventoryTransfers.click(owned,itemSlot,0,ContainerInput.QUICK_MOVE));stage=1;reason=PrinterReason.of(PrinterReason.Id.SUPPLY,"等待补给确认");return true;
        }
        if(!ownsScreen()){reset(false);throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已中断"));}
        if(confirmed()){reset(true);return true;}return true;
    }
    private boolean confirmed(){for(int i=0;i<36;i++)if(InventoryReceipts.stamp(i)>before[i]&&client.player.getInventory().getItem(i).is(wanted))return true;return false;}
    private boolean ownsScreen(){return owned!=null&&client.player.containerMenu==owned&&ClientUi.screen(client) instanceof AbstractContainerScreen<?> screen&&screen.getMenu()==owned;}
    void reset(boolean close){if(close&&client.player!=null&&ownsScreen()&&owned.getCarried().isEmpty())client.player.closeContainer();wanted=null;world=null;connection=null;box=null;owned=null;before=null;ownedSync=-1;contents=false;stage=0;triedBoxes=0;}
    void clear(){reset(true);cooldown.clear();}
}
