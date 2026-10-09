package dev.betterlitematica.fabric;
import com.mojang.brigadier.arguments.*;
import dev.betterlitematica.core.LayerRange;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.*;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;
public final class BetterLitematicaClient implements ClientModInitializer {
    private static volatile ProjectionController activeController;
    private static ModeWheelInput modeWheelInput;
    private static StatusHud statusHud;
    public static boolean constructionOwnsInput(){var c=activeController;return c!=null&&(c.printer().running()||c.bedrock().enabled()||NativeMiner.acting()||AccuratePlacement.activeAction());}
    static boolean scrollNearby(double x,double y,double amount){return statusHud!=null&&statusHud.scroll(x,y,amount);}
    public static boolean wheelKey(long window,int key,int scan,int action){boolean consumed=modeWheelInput!=null&&modeWheelInput.key(window,key,scan,action);if(consumed&&interactions!=null)interactions.suspendInput();return consumed;}
    public static boolean wheelMouse(long window,int button,int action){return modeWheelInput!=null&&modeWheelInput.mouse(window,button,action);}
    public static boolean wheelBlocksWorldInput(){return modeWheelInput!=null&&modeWheelInput.blocksWorldInput();}
    public static boolean wheelSharesPlayerListHold(){return modeWheelInput!=null&&modeWheelInput.sharesPlayerListHold();}
    private final DeferredMenuOpen menuOpen=new DeferredMenuOpen();
    public static boolean toolInput(long window,int key,int action){return activeController!=null&&activeController.tool().event(window,key,action);}
    public static boolean toolBlocksWorld(){return activeController!=null&&activeController.tool().blocksVanilla();}
    public static void inputEvent(long window,int key,int action){if(interactions!=null)interactions.inputEvent(window,key,action);}
    private void openFromCommand(MinecraftClient client,ProjectionController controller,java.util.function.Function<net.minecraft.client.gui.screen.Screen,net.minecraft.client.gui.screen.Screen> factory){
        var origin=client.currentScreen;
        var parent=origin instanceof net.minecraft.client.gui.screen.ChatScreen?null:origin;
        menuOpen.request(client.world,client.getNetworkHandler(),origin,()->controller.action(()->client.setScreen(factory.apply(parent))));
    }
    public static boolean editUse(){return activeController!=null&&(activeController.tool().edit(true)||activeController.editor().use());}
    public static boolean editAttack(){return activeController!=null&&(activeController.tool().edit(false)||activeController.editor().attack());}
    public static boolean editActive(){return activeController!=null&&activeController.editor().claimsInput();}
    public static boolean editEscape(){return activeController!=null&&activeController.editor().escape();}
    public static void supplyOpened(int sync,net.minecraft.screen.ScreenHandlerType<?> type){var c=activeController;if(c!=null)c.printer().supplyOpened(sync,type);}
    public static void containerOpening(net.minecraft.screen.ScreenHandlerType<?> type){var c=activeController;if(c!=null&&MinecraftClient.getInstance().isOnThread())c.printer().containerOpening(type);}
    public static void supplyInventory(int sync){var c=activeController;if(c!=null)c.printer().supplyInventory(sync);}
    public static void containerInventory(net.minecraft.network.packet.s2c.play.InventoryS2CPacket packet){var c=activeController;if(c!=null)c.printer().containerInventory(packet);}
    public static void containerSlot(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket packet){var c=activeController;if(c!=null)c.printer().containerSlot(packet);}
    public static void manualContainerInteraction(){var c=activeController;if(c!=null)c.printer().manualContainerInteraction();}
    public static boolean printerSignOpened(net.minecraft.block.entity.SignBlockEntity sign,boolean front){var c=activeController;return c!=null&&MinecraftClient.getInstance().isOnThread()&&c.printer().signOpened(sign,front);}
    public static void manualSignInteraction(net.minecraft.util.math.BlockPos pos){var c=activeController;if(c!=null&&MinecraftClient.getInstance().isOnThread())c.printer().manualSignInteraction(pos);}
    public static void manualInventory(){var c=activeController;if(c!=null)c.printer().manualInventory();}
    public static void confirmedProjectionBlock(net.minecraft.util.math.BlockPos pos,net.minecraft.block.BlockState state){var controller=activeController;if(controller!=null&&MinecraftClient.getInstance().isOnThread()){controller.printer().confirmed(pos,state);NativeMiner.update(pos,state);}}
    public static boolean nativeMiningBusy(){var c=activeController;return NativeMiner.busy()||c!=null&&(c.bedrock().enabled()||c.printer().running()&&c.options().printer.bedrock);}
    public static float miningYaw(float original){return NativeMiner.packetYaw(original);}
    public static float miningPitch(float original){return NativeMiner.packetPitch(original);}
    public static void projectionBlockChanged(net.minecraft.world.BlockView world,net.minecraft.util.math.BlockPos pos){var controller=activeController;if(controller!=null)controller.worldBlockChanged(world,pos);}
    public static void projectionChunkChanged(net.minecraft.world.BlockView world,int x,int z){var controller=activeController;if(controller!=null)controller.worldChunkChanged(world,x,z);}
    static int wheelKeyCode(){return activeController==null?GLFW.GLFW_KEY_TAB:ModeWheelInput.bindingCode(activeController.options().keys.getOrDefault("wheel","TAB"));}
    static BuildingInteractions interactions;
    public static boolean useProjection(){return interactions!=null&&interactions.use();}
    public static boolean attackProjection(){return interactions!=null&&interactions.attack();}
    public static boolean preserveProjectionBlocks(){return interactions!=null&&interactions.claimsAttack();}
    public static boolean pickProjection(){return interactions!=null&&interactions.pick();}
    public static boolean scrollTool(double amount){return interactions!=null&&interactions.scroll(amount);}
    public static boolean preservePrinterBreaking(){return interactions!=null&&interactions.preservePrinterBreaking();}
    public static void manualPrinterInteraction(){if(interactions!=null)interactions.manualPrinterInteraction();}
    static final Logger LOGGER=LoggerFactory.getLogger("betterlitematica");
    @Override public void onInitializeClient(){
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client->LegacyPayloads.initialize());
        dev.betterlitematica.io.SourceVersions.install(new SourceVersions1201());
        try{PrinterColdWarmup.initialize();}catch(RuntimeException e){LOGGER.warn("Printer read-only startup preparation unavailable",e);}
        ProjectionShaders.register();
        MinecraftClient client=MinecraftClient.getInstance();ProjectionController controller=new ProjectionController(client);activeController=controller;modeWheelInput=new ModeWheelInput(client,controller);
        interactions=new BuildingInteractions(client,controller);
        ClientTickEvents.START_CLIENT_TICK.register(mc->{modeWheelInput.tick();menuOpen.tick(mc.world,mc.getNetworkHandler(),mc.currentScreen);});
        // Shortcut transitions are captured by inputEvent, including taps between ticks.
        ClientTickEvents.END_CLIENT_TICK.register(mc->{controller.tick();CompatibilityNotice.tick(mc);interactions.tick();if(!controller.printer().running())PrinterColdWarmup.step(mc);controller.printer().tickHud();controller.bedrock().tick();if(!controller.bedrock().enabled())controller.printer().tick();});
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player,world,hand,pos,direction)->{
            if(world==client.world&&controller.bedrock().attack(pos))return net.minecraft.util.ActionResult.FAIL;
            if(world==client.world&&interactions.blockClick(true,new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(pos),direction,pos,false)))return net.minecraft.util.ActionResult.FAIL;return net.minecraft.util.ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player,world,hand,hit)->world==client.world&&(controller.bedrock().use(hit.getBlockPos())||interactions.blockClick(false,hit))?net.minecraft.util.ActionResult.FAIL:net.minecraft.util.ActionResult.PASS);
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->mc.execute(()->{
            // Network-driven disconnects may arrive off-thread, after another session has opened.
            if(mc.getNetworkHandler()!=null&&mc.getNetworkHandler()!=handler)return;
            modeWheelInput.clear();menuOpen.clear();controller.disconnect();EntityOverlayMask.close();
        }));
        IndependentUi ui=IndependentUi.INSTANCE;ProjectionInformation information=new ProjectionInformation();ToolHud toolHud=new ToolHud();statusHud=new StatusHud();final int[] informationTick={0};ClientTickEvents.END_CLIENT_TICK.register(mc->{if(++informationTick[0]%4==0)information.update(mc,controller);});
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc->{modeWheelInput.clear();controller.close();ui.close();EntityOverlayMask.close();});
        WorldRenderEvents.START.register(context->{EntityOverlayMask.reset();interactions.frame();});
        WorldRenderEvents.BEFORE_ENTITIES.register(context->EntityOverlayMask.before(client,controller.entityOverlayMaskNeeded(context)));
        WorldRenderEvents.AFTER_ENTITIES.register(context->EntityOverlayMask.after(client));
        WorldRenderEvents.END.register(context->EntityOverlayMask.reset());
        WorldRenderEvents.LAST.register(context->{controller.render(context);controller.capturePreview();});
        HudRenderCallback.EVENT.register((context,delta)->{
            if(client.world==null||client.player==null||client.options.hudHidden||HudLayout.menuHidesHud(client.currentScreen))return;
            var layout=HudLayout.of(client,false);if(!ui.beginHud(context,layout.viewport()))return;
            try{
                layout=information.arrange(ui,layout);
                double toolTop=toolHud.top(client,ui,controller,layout);
                statusHud.draw(ui,controller,layout,toolTop);
                information.draw(client,ui,layout);
                toolHud.draw(client,ui,controller,layout,toolTop);
            }finally{ui.end();}
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){
            @Override public Identifier getFabricId(){return Identifier.of("betterlitematica","projection_models");}
            @Override public void reload(ResourceManager manager){client.execute(()->{controller.resourcesReloaded();ui.clearTextures();});}
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registryAccess)->{
            var root=literal("bl").executes(c->{controller.report("/bl load <file> | list | select <number> | duplicate | rename <name> | lock | reload | unload | unloadall | here | move x y z | rotate degrees | mirror none/x/z/xz | layer all | layer x/y/z min max | alpha value | show | rendering | info | counts");return 1;});
            root.then(literal("list").executes(c->controller.action(controller::listPlacements)));
            root.then(literal("select").then(argument("number",IntegerArgumentType.integer(1,8)).executes(c->controller.action(()->controller.selectNumber(IntegerArgumentType.getInteger(c,"number"))))));
            root.then(literal("duplicate").executes(c->controller.action(controller::duplicate)));
            root.then(literal("rename").then(argument("name",StringArgumentType.string()).executes(c->controller.action(()->controller.rename(StringArgumentType.getString(c,"name"))))));
            root.then(literal("lock").executes(c->controller.action(controller::toggleLock)));
            root.then(literal("reload").executes(c->controller.action(controller::reload)));
            root.then(literal("unloadall").executes(c->controller.action(controller::unloadAll)));
            root.then(literal("rendering").executes(c->controller.action(controller::toggleRendering)));
            root.then(literal("verify").executes(c->controller.action(controller::startAnalysis)));
            root.then(literal("pause").executes(c->controller.action(controller::pauseAnalysis)));
            root.then(literal("cancel").executes(c->controller.action(controller::cancelAnalysis)));
            root.then(literal("materials").executes(c->controller.action(()->openFromCommand(client,controller,parent->new AnalysisScreen(parent,controller,true)))));
            root.then(literal("pos1").executes(c->controller.action(()->controller.selectionCorner(true))));
            root.then(literal("pos2").executes(c->controller.action(()->controller.selectionCorner(false))));
            root.then(literal("area").then(argument("name",StringArgumentType.string()).executes(c->controller.action(()->controller.selectionAdd(StringArgumentType.getString(c,"name"))))));
            root.then(literal("capture").then(argument("file",StringArgumentType.string()).executes(c->controller.action(()->controller.capture(StringArgumentType.getString(c,"file"))))));
            root.then(literal("replace").then(argument("from",StringArgumentType.string()).then(argument("to",StringArgumentType.string()).then(argument("output",StringArgumentType.string()).executes(c->controller.action(()->controller.editReplace(StringArgumentType.getString(c,"from"),StringArgumentType.getString(c,"to"),StringArgumentType.getString(c,"output"),false)))))));
            root.then(literal("settings").executes(c->controller.action(()->openFromCommand(client,controller,parent->new OptionsScreen(parent,controller)))));
            root.then(literal("convert").then(argument("format",StringArgumentType.word()).then(argument("output",StringArgumentType.string()).executes(c->controller.action(()->controller.convertSource(StringArgumentType.getString(c,"format"),StringArgumentType.getString(c,"output")))))));
            root.then(literal("fill").then(argument("state",StringArgumentType.string()).executes(c->controller.action(()->controller.fill(StringArgumentType.getString(c,"state"),null)))));
            root.then(literal("replaceworld").then(argument("from",StringArgumentType.string()).then(argument("to",StringArgumentType.string()).executes(c->controller.action(()->controller.fill(StringArgumentType.getString(c,"to"),StringArgumentType.getString(c,"from")))))));
            root.then(literal("deletearea").executes(c->controller.action(()->controller.fill("minecraft:air",null))));
            root.then(literal("printer").executes(c->controller.action(()->openFromCommand(client,controller,parent->new PrinterScreen(parent,controller)))));
            root.then(literal("bedrock").executes(c->controller.action(()->openFromCommand(client,controller,parent->new BedrockScreen(parent,controller))))
                .then(literal("toggle").executes(c->controller.action(()->controller.bedrock().toggle())))
                .then(literal("clear").executes(c->controller.action(()->controller.bedrock().clear()))));
            root.then(literal("tasks").executes(c->controller.action(()->openFromCommand(client,controller,parent->new TaskScreen(parent,controller)))));
            root.then(literal("mcfunction").then(argument("output",StringArgumentType.string()).executes(c->controller.action(()->controller.commands(StringArgumentType.getString(c,"output"),dev.betterlitematica.core.ReplaceRule.ALL,true)))));
            root.then(literal("pastecommands").then(argument("replace",StringArgumentType.word()).executes(c->controller.action(()->controller.commands(null,dev.betterlitematica.core.ReplaceRule.valueOf(StringArgumentType.getString(c,"replace").toUpperCase(java.util.Locale.ROOT)),false)))));
            root.then(literal("projects").executes(c->controller.action(()->openFromCommand(client,controller,parent->new ProjectScreen(parent,controller)))));
            root.then(literal("load").then(argument("file",StringArgumentType.string()).executes(c->controller.action(()->controller.load(StringArgumentType.getString(c,"file"))))));
            root.then(literal("unload").executes(c->controller.action(controller::unload)));
            root.then(literal("here").executes(c->controller.action(controller::here)));
            root.then(literal("rotate").then(argument("degrees",IntegerArgumentType.integer(-360,360)).executes(c->controller.action(()->controller.rotate(IntegerArgumentType.getInteger(c,"degrees"))))));
            root.then(literal("mirror").then(argument("axis",StringArgumentType.word()).executes(c->controller.action(()->controller.mirror(StringArgumentType.getString(c,"axis"))))));
            root.then(literal("move").then(argument("x",IntegerArgumentType.integer(-30_000_000,30_000_000))
                .then(argument("y",IntegerArgumentType.integer(-30_000_000,30_000_000))
                    .then(argument("z",IntegerArgumentType.integer(-30_000_000,30_000_000))
                        .executes(c->controller.action(()->controller.move(IntegerArgumentType.getInteger(c,"x"),IntegerArgumentType.getInteger(c,"y"),IntegerArgumentType.getInteger(c,"z"))))))));
            var layer=literal("layer");layer.then(literal("all").executes(c->controller.action(controller::allLayers)));
            for(LayerRange.Axis axis:LayerRange.Axis.values())layer.then(literal(axis.name().toLowerCase(java.util.Locale.ROOT))
                .then(argument("min",IntegerArgumentType.integer(-30_000_000,30_000_000))
                    .then(argument("max",IntegerArgumentType.integer(-30_000_000,30_000_000))
                        .executes(c->controller.action(()->controller.layer(axis,IntegerArgumentType.getInteger(c,"min"),IntegerArgumentType.getInteger(c,"max")))))));
            root.then(layer);
            root.then(literal("alpha").then(argument("opacity",FloatArgumentType.floatArg(0.05f,1f)).executes(c->controller.action(()->controller.opacity(FloatArgumentType.getFloat(c,"opacity"))))));
            root.then(literal("show").executes(c->controller.action(controller::toggle)));
            root.then(literal("info").executes(c->controller.action(controller::info)));
            root.then(literal("counts").executes(c->controller.action(controller::blockCounts)));
            dispatcher.register(root);
        });
    }
}
