package dev.betterlitematica.fabric;

import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;

/** Optional Tom terminal access through its public protocol, with an owned receipt-based transaction. */
public final class RemoteSupply {
    private static final Identifier OPEN=new Identifier("toms_storage","open_term_c2s");
    private static Object snapshotWorld,snapshotMenu;private static long observedAt;
    private static Map<Item,Long> observed;
    private final MinecraftClient client;private Item wanted;private ScreenHandler owned;
    private Object world;private int started,stage,before;private long[] receipts;private String reason="";
    private final Map<Item,Integer> retry=new HashMap<>();
    RemoteSupply(MinecraftClient client){this.client=client;}
    public static void observed(Object menu){
        var client=MinecraftClient.getInstance();
        try{var counts=new HashMap<Item,Long>();for(Object entry:(List<?>)menu.getClass().getField("itemListClient").get(menu)){
            var stack=(ItemStack)entry.getClass().getMethod("getStack").invoke(entry);long count=((Number)entry.getClass().getMethod("getQuantity").invoke(entry)).longValue();
            if(!stack.isEmpty()&&count>0)counts.merge(stack.getItem(),count,Long::sum);
        }observed=Map.copyOf(counts);snapshotWorld=client.world;snapshotMenu=menu;observedAt=System.nanoTime();
        }catch(ReflectiveOperationException|ClassCastException e){clearSnapshot();}
    }
    static void clearSnapshot(){observed=null;snapshotWorld=snapshotMenu=null;}
    static Map<Item,Long> snapshot(MinecraftClient client){return snapshotWorld==client.world&&System.nanoTime()-observedAt<5_000_000_000L?observed:null;}
    static boolean available(MinecraftClient client){
        if(client.player==null||!ClientPlayNetworking.canSend(OPEN))return false;
        for(int i=0;i<36;i++){var stack=client.player.getInventory().getStack(i);String id=Registries.ITEM.getId(stack.getItem()).toString();if(id.startsWith("toms_storage:")&&id.contains("wireless_terminal")&&stack.hasNbt()&&(stack.getNbt().contains("BindX")||stack.getNbt().containsUuid("BoundNetworkId"))){
            try{if(Boolean.TRUE.equals(stack.getItem().getClass().getMethod("canOpen",ItemStack.class).invoke(stack.getItem(),stack)))return true;}catch(ReflectiveOperationException ignored){}
        }}return false;
    }
    boolean active(){return wanted!=null;}String reason(){return reason;}
    boolean request(Item item,int tick){
        if(active())return wanted==item;
        if(!available(client)||client.currentScreen!=null||client.player.currentScreenHandler!=client.player.playerScreenHandler||!client.player.playerScreenHandler.getCursorStack().isEmpty()||retry.getOrDefault(item,0)>tick)return false;
        wanted=item;world=client.world;started=tick;owned=null;stage=0;reason="读取远程库存";clearSnapshot();
        ClientPlayNetworking.send(OPEN,PacketByteBufs.create());return true;
    }
    private int count(){int n=0;for(int i=0;i<36;i++){var s=client.player.getInventory().getStack(i);if(s.isOf(wanted))n+=s.getCount();}return n;}
    boolean tick(int tick){
        if(!active())return false;
        if(client.player==null||client.world!=world){reset(false);return false;}
        if(tick-started>100){reason="远程补给未确认";fail(tick);return true;}
        if(owned==null){var handler=client.player.currentScreenHandler;
            if(handler!=client.player.playerScreenHandler){
                if(!isTerminal(handler)){reset(false);throw new IllegalStateException("远程补给已由玩家接管");}owned=handler;
            }else if(client.currentScreen!=null){reset(false);throw new IllegalStateException("远程补给已中断");}
            return true;
        }
        if(client.player.currentScreenHandler!=owned||!(client.currentScreen instanceof HandledScreen<?> screen)||screen.getScreenHandler()!=owned){reset(false);throw new IllegalStateException("远程补给容器已关闭");}
        if(!owned.getCursorStack().isEmpty()){reset(false);throw new IllegalStateException("远程补给已由玩家接管");}
        if(stage==1){boolean confirmed=false;for(int i=0;i<36;i++)if(InventoryReceipts.stamp(i)>receipts[i]&&client.player.getInventory().getStack(i).isOf(wanted))confirmed=true;if(count()>before&&confirmed){reset(true);return true;}return true;}
        if(snapshotMenu!=owned||System.nanoTime()-observedAt<100_000_000L)return true;
        try{
            Object selected=null;for(Object entry:(List<?>)owned.getClass().getField("itemListClient").get(owned)){
                var stack=(ItemStack)entry.getClass().getMethod("getStack").invoke(entry);long quantity=((Number)entry.getClass().getMethod("getQuantity").invoke(entry)).longValue();
                if(stack.isOf(wanted)&&quantity>0){selected=entry;break;}
            }
            if(selected==null){reason="缺少"+wanted.getName().getString();fail(tick);return true;}
            boolean room=false;for(int i=0;i<36;i++){var s=client.player.getInventory().getStack(i);if(s.isEmpty()||s.isOf(wanted)&&s.getCount()<s.getMaxCount())room=true;}
            if(!room){reason="背包已满";fail(tick);return true;}
            Object sync=owned.getClass().getField("sync").get(owned);
            Class<?> action=Class.forName("com.tom.storagemod.gui.StorageTerminalMenu$SlotAction");Object pull=Arrays.stream(action.getEnumConstants()).filter(v->((Enum<?>)v).name().equals("SHIFT_PULL")).findFirst().orElseThrow();
            before=count();receipts=new long[36];for(int i=0;i<36;i++)receipts[i]=InventoryReceipts.stamp(i);
            sync.getClass().getMethod("sendInteract",selected.getClass(),action,boolean.class).invoke(sync,selected,pull,false);
            stage=1;reason="等待远程补给确认";
        }catch(ReflectiveOperationException e){reason="远程补给接口不兼容";fail(tick);}
        return true;
    }
    private static boolean isTerminal(Object object){for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())if(c.getName().equals("com.tom.storagemod.gui.StorageTerminalMenu"))return true;return false;}
    private void fail(int tick){retry.put(wanted,tick+100);reset(true);}
    void reset(boolean close){if(close&&owned!=null&&client.player!=null&&client.player.currentScreenHandler==owned&&owned.getCursorStack().isEmpty())client.player.closeHandledScreen();wanted=null;owned=null;world=null;stage=0;}
}
