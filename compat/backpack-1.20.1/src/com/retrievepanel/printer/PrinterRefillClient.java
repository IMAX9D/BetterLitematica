package com.retrievepanel.printer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import net.minecraft.class_2561;

public final class PrinterRefillClient implements ClientModInitializer {
    public static volatile boolean compatible;
    public static boolean ipnCompatible;
    public static String lastStatus="尚未补货";
    public void onInitializeClient(){
        PrinterRefillConfig.load();
        var loader=net.fabricmc.loader.api.FabricLoader.getInstance();
        compatible=loader.getModContainer("betterlitematica").map(m->m.getMetadata().getVersion().getFriendlyString().equals("0.4.17")).orElse(false)
            &&loader.getModContainer("travelersbackpack").map(m->m.getMetadata().getVersion().getFriendlyString().equals("9.1.50")).orElse(false);
        ipnCompatible=loader.getModContainer("inventoryprofilesnext").map(m->m.getMetadata().getVersion().getFriendlyString().equals("1.10.20")).orElse(false);
        if(compatible){
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client->com.retrievepanel.client.PrinterRefillSession.backgroundTick());
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->com.retrievepanel.client.PrinterRefillSession.disconnected());
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->{
                if(ipnCompatible&&PrinterRefillConfig.current.coordinateIpn&&!PrinterRefillConfig.current.ipnNoticeShown&&client.field_1724!=null){
                    client.field_1724.method_7353(class_2561.method_43470("背包补货：打印期间暂缓 IPN 自动补充，结束后自动恢复。可用 /backpack-printer status 查看状态。"),false);
                    PrinterRefillConfig.current.ipnNoticeShown=true;try{PrinterRefillConfig.save();}catch(java.io.IOException e){RefillLog.warn("无法保存兼容提示状态");}
                }
            });
        }
        RefillLog.info("build=2026-10-25-full-stock.5-bl0.4.17 compatible={}, enabled={}, trash={}, emptyDivisor={}, rotationDivisor={}, ipn={}",compatible,PrinterRefillConfig.current.enabled,PrinterRefillConfig.current.trashSurplus,PrinterRefillConfig.current.refillEmptyDivisor,PrinterRefillConfig.current.rotationDivisor,ipnCompatible);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->dispatcher.register(
            literal("backpack-printer")
                .executes(ctx->{ctx.getSource().sendFeedback(class_2561.method_43470(status()));return 1;})
                .then(literal("status").executes(ctx->{ctx.getSource().sendFeedback(class_2561.method_43470(status()));return 1;}))
                .then(literal("on").executes(ctx->toggle(ctx.getSource(),true)))
                .then(literal("off").executes(ctx->toggle(ctx.getSource(),false)))));
    }
    private static String status(){return "打印机后台补货："+(compatible?(PrinterRefillConfig.current.enabled?"开":"关"):"当前版本不兼容")+"；整组备货、批量补满；"+(compatible?"材料轮换待选 "+dev.betterlitematica.fabric.BackpackPrinterBridge.stockPolicy.waitingCount()+" 种；":"")+(ipnCompatible&&PrinterRefillConfig.current.coordinateIpn?"IPN 自动补充：打印期间暂缓；":"")+lastStatus;}
    private static int toggle(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,boolean enabled){
        PrinterRefillConfig.current.enabled=enabled;
        try{PrinterRefillConfig.save();}catch(java.io.IOException e){source.sendError(class_2561.method_43470("配置保存失败，本次设置只在当前游戏有效"));}
        source.sendFeedback(class_2561.method_43470(status()));return 1;
    }
}
