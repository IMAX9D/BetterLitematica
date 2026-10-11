package dev.betterlitematica.fabric;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;

/** Capture only explicit, non-identifying runtime fields; never serialize the player's full configuration. */
final class BugReports {
    private static BugReportClient service;
    private static boolean enteredWorld;
    private BugReports(){}
    static void register(MinecraftClient client,ProjectionController controller){
        // Persist the launch boundary even if the player never opens the feedback form.
        service();net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler,sender,mc)->enteredWorld=true);
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc,screen,width,height)->{
            if(screen instanceof net.minecraft.client.gui.screen.TitleScreen){
                var window=mc.getWindow();var viewport=dev.betterlitematica.core.UiViewport.fit(window.getFramebufferWidth(),window.getFramebufferHeight(),window.getScaledWidth(),window.getScaledHeight(),window.getScaleFactor());
                int w=Math.max(60,(int)Math.round(92*viewport.drawScale())),h=Math.max(14,(int)Math.round(20*viewport.drawScale()));
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(new TitleFeedbackButton(width-w-12,12,w,h,()->mc.setScreen(new BugReportScreen(screen,controller))));
            }
        });
    }
    static boolean previousSession(boolean titleScreen){return titleScreen&&!enteredWorld;}
    static synchronized BugReportClient service(){if(service==null){var client=MinecraftClient.getInstance();service=new BugReportClient(client.runDirectory.toPath(),BugReportClient.API,()->client.getSession().getUsername(),java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());}return service;}
    private static String version(String id){return FabricLoader.getInstance().getModContainer(id).map(mod->mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");}
    static String versionSummary(){return "Minecraft "+version("minecraft")+"  ·  BetterLitematica "+version("betterlitematica");}
    static JsonObject environment(ProjectionController controller){
        var result=new JsonObject();result.addProperty("mod_version",BugReportClient.bounded(version("betterlitematica"),80));
        result.addProperty("minecraft_version",BugReportClient.bounded(version("minecraft"),40));
        result.addProperty("loader","fabric");result.addProperty("loader_version",BugReportClient.bounded(version("fabricloader"),80));
        result.addProperty("java_version",BugReportClient.bounded(System.getProperty("java.version","unknown"),120));
        result.addProperty("os",BugReportClient.bounded(System.getProperty("os.name","unknown")+" "+System.getProperty("os.version","")+" "+System.getProperty("os.arch",""),200));
        var diagnostics=new JsonObject();var settings=controller.options().printer;
        diagnostics.addProperty("printer_state",controller.printer().state().name());
        diagnostics.addProperty("print",settings.print);diagnostics.addProperty("fill",settings.fill);diagnostics.addProperty("drain",settings.fluid);diagnostics.addProperty("bedrock",settings.bedrock);
        diagnostics.addProperty("range",settings.range);diagnostics.addProperty("interval",settings.interval);diagnostics.addProperty("per_tick",settings.perTick);diagnostics.addProperty("cooldown",settings.cooldown);
        diagnostics.addProperty("easy_place",controller.options().easyPlace);diagnostics.addProperty("hold",controller.options().hold);
        result.add("diagnostics",diagnostics);return result;
    }
}
