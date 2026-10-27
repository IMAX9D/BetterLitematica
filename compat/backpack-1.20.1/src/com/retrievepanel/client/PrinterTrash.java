package com.retrievepanel.client;

import com.retrievepanel.printer.RefillLog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.*;
import java.util.*;

/** Uses the reviewed TrashSlot single-slot packet. Never clears a slot locally. */
public final class PrinterTrash {
    private static final class_2960 CONTENT=new class_2960("trashslot","trash_slot_content"),DELETE=new class_2960("trashslot","delete_from_slot");
    private static Object connection,world,player;
    private static class_1799 mirror=class_1799.field_8037,owned=class_1799.field_8037;
    private static long revision,ownedRevision=-1;
    private static boolean valid;
    private static List<class_1799> pending=List.of();private static int received;
    private static String failure;
    private static final ClassValue<Boolean> BACKPACK=new ClassValue<>(){protected Boolean computeValue(Class<?> c){for(Class<?> t=c;t!=null;t=t.getSuperclass())if(t.getName().toLowerCase(Locale.ROOT).contains("backpack"))return true;return false;}};
    public static boolean ordinary(class_1799 stack){
        if(stack.method_7960()||!(stack.method_7909() instanceof class_1747 block)||stack.method_7985()||stack.method_7938()||stack.method_7963()||stack.method_19267())return false;
        String id=class_7923.field_41178.method_10221(stack.method_7909()).toString();
        return !id.contains("backpack")&&!id.contains("cake")&&!id.startsWith("inmis:")&&!id.startsWith("toms_storage:")&&!BACKPACK.get(stack.method_7909().getClass())
            &&!block.method_7711().getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("cake");
    }
    /** Runs INSIDE Fabric's handler before Balm decoding. Copies without changing the packet reader index. */
    public static void payload(Object source,class_2658 packet){
        if(!CONTENT.equals(packet.method_11456()))return;
        try{
            class_2540 copy=new class_2540(packet.method_11458().duplicate());
            var stack=copy.method_10819().method_7972();
            class_310.method_1551().execute(()->observe(source,stack));
        }catch(RuntimeException e){RefillLog.warn("无法解析垃圾桶同步：{}",e.getClass().getSimpleName());}
    }
    private static boolean context(){var c=class_310.method_1551();return connection==c.method_1562()&&world==c.field_1687&&player==c.field_1724;}
    private static void observe(Object source,class_1799 stack){
        var c=class_310.method_1551();if(source!=c.method_1562()||c.field_1724==null)return;
        if(!context()){reset();connection=source;world=c.field_1687;player=c.field_1724;}
        if(!valid)RefillLog.info("trash mirror synchronized from server, empty={}",stack.method_7960());
        mirror=stack.method_7972();valid=true;revision++;
        if(received<pending.size()&&class_1799.method_7973(pending.get(received),stack)){
            received++;owned=stack.method_7972();ownedRevision=revision;
        }else if(class_1799.method_7973(owned,stack)&&ownedRevision>=0)ownedRevision=revision;
    }
    public static String unavailable(){
        if(!FabricLoader.getInstance().getModContainer("trashslot").map(m->m.getMetadata().getVersion().getFriendlyString().equals("15.1.1")).orElse(false))return "垃圾槽版本不兼容";
        if(!ClientPlayNetworking.canSend(DELETE))return "服务器未提供垃圾桶回收通道";
        if(!valid||!context())return "等待服务器垃圾桶同步";
        if(!pending.isEmpty()&&!confirmed())return "上次垃圾桶操作尚未确认";
        if(!mirror.method_7960()&&!(ownedRevision==revision&&class_1799.method_7973(owned,mirror)))return "垃圾桶内有玩家原有物品，请先取回或手动清空";
        try{
            var config=Class.forName("net.blay09.mods.trashslot.config.TrashSlotConfig");
            Object active=config.getMethod("getActive").invoke(null);
            if(active.getClass().getField("instantDeletion").getBoolean(active))return "垃圾桶当前不是生存回收模式";
        }catch(ReflectiveOperationException|LinkageError e){return "垃圾桶配置接口不可用";}
        return null;
    }
    public static boolean allowed(class_1799 stack){
        try{var c=Class.forName("net.blay09.mods.trashslot.config.TrashSlotConfig");Object active=c.getMethod("getActive").invoke(null);
            Object list=active.getClass().getField("deletionDenyList").get(active);
            return list instanceof List<?> denied&&!denied.contains(class_7923.field_41178.method_10221(stack.method_7909()).toString());
        }catch(ReflectiveOperationException|LinkageError e){return false;}
    }
    public static void send(List<Integer> menuSlots,List<class_1799> stacks){
        String unavailable=unavailable();if(unavailable!=null)throw new IllegalStateException(unavailable);
        pending=stacks.stream().map(class_1799::method_7972).toList();received=0;failure=null;
        for(int slot:menuSlots)ClientPlayNetworking.send(DELETE,com.retrievepanel.printer.TrashPacket.payload(slot));
        RefillLog.info("trash send slots={}, count={}",menuSlots,stacks.stream().mapToInt(class_1799::method_7947).sum());
    }
    public static boolean confirmed(){return !pending.isEmpty()&&received==pending.size()&&failure==null;}
    public static void consumed(){if(confirmed()){pending=List.of();received=0;}}
    public static boolean unresolved(){return !pending.isEmpty();}
    public static void reset(){valid=false;connection=world=player=null;mirror=owned=class_1799.field_8037;ownedRevision=-1;revision=0;pending=List.of();received=0;failure=null;}
}
