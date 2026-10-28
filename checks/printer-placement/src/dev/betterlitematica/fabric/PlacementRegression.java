package dev.betterlitematica.fabric;

import com.google.gson.GsonBuilder;
import dev.betterlitematica.checks.mixin.LoaderInvoker;
import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Local throwaway world only. Uses the real printer and confirms the integrated server's states. */
public final class PlacementRegression implements ClientModInitializer {
    private final Map<String,Boolean> checks=new LinkedHashMap<>();
    private final Map<BlockPos,BlockState> wanted=new LinkedHashMap<>();
    private final Path root=Path.of(System.getProperty("placementCheck.root","placement-check"));
    private ProjectionController controller;
    private boolean opened,loaded,started,finished;
    private int ticks,worldTicks,printTicks,focusWaitTicks,stable;
    private long operationsAtCompletion=-1;
    private CompletableFuture<?> setup;
    @Override public void onInitializeClient(){
        if(!Boolean.getBoolean("placementCheck.enabled"))throw new IllegalStateException("Regression mod requires explicit placementCheck.enabled");
        ClientTickEvents.END_CLIENT_TICK.register(mc->{try{tick(mc);}catch(Throwable failure){finish(mc,failure.toString());}});
    }
    private void check(String label,boolean result){checks.put(label,result);}
    private void rules(){
        var fence=Blocks.OAK_FENCE.getDefaultState();var pane=Blocks.GLASS_PANE.getDefaultState();
        check("connected_fence_can_start",PrinterRules.placementMatches(fence,fence.with(Properties.EAST,true)));
        check("connected_pane_can_start",PrinterRules.placementMatches(pane,pane.with(Properties.WEST,true)));
        var wall=Blocks.COBBLESTONE_WALL.getDefaultState();
        check("wall_shape_can_settle",PrinterRules.placementMatches(wall,wall.with(Properties.EAST_WALL_SHAPE,WallShape.LOW).with(Properties.UP,false)));
        for(var block:List.of(Blocks.OAK_TRAPDOOR,Blocks.OAK_FENCE_GATE,Blocks.OAK_DOOR)){
            var closed=block.getDefaultState();var open=closed.with(Properties.OPEN,true);
            check(block.getTranslationKey()+"_place_closed",PrinterRules.placementMatches(closed,open));
            check(block.getTranslationKey()+"_must_adjust",!PrinterRules.constructionMatches(closed,open)&&PrinterRules.adjustable(closed,open,new PrinterSettings()));
        }
        var gate=Blocks.OAK_FENCE_GATE.getDefaultState();
        check("gate_in_wall_is_derived",PrinterRules.placementMatches(gate,gate.with(Properties.IN_WALL,true)));
        var stairs=Blocks.OAK_STAIRS.getDefaultState();
        check("stairs_shape_is_derived",PrinterRules.placementMatches(stairs,stairs.with(Properties.STAIR_SHAPE,StairShape.INNER_LEFT)));
        check("reject_wrong_material",!PrinterRules.placementMatches(fence,Blocks.DARK_OAK_FENCE.getDefaultState()));
        check("reject_wrong_facing",!PrinterRules.placementMatches(gate,gate.with(Properties.HORIZONTAL_FACING,gate.get(Properties.HORIZONTAL_FACING).getOpposite())));
        check("reject_wrong_half",!PrinterRules.placementMatches(Blocks.OAK_TRAPDOOR.getDefaultState(),Blocks.OAK_TRAPDOOR.getDefaultState().with(Properties.BLOCK_HALF,BlockHalf.TOP)));
        check("waterlog_requires_adjustment",PrinterRules.placementMatches(fence,fence.with(Properties.WATERLOGGED,true))&&!PrinterRules.constructionMatches(fence,fence.with(Properties.WATERLOGGED,true)));
        check("iron_is_not_hand_toggle",!PrinterRules.placementMatches(Blocks.IRON_TRAPDOOR.getDefaultState(),Blocks.IRON_TRAPDOOR.getDefaultState().with(Properties.OPEN,true)));
        check("gate_power_is_not_ignored",!PrinterRules.placementMatches(gate,gate.with(Properties.POWERED,true)));
        if(FabricLoader.getInstance().isModLoaded("diagonalblocks")){
            var item=(net.minecraft.item.BlockItem)Blocks.OAK_FENCE.asItem();
            check("real_diagonal_item_conversion",item.getBlock()!=Blocks.OAK_FENCE);
            check("diagonal_fence_equivalent",PrinterRules.placementMatches(item.getBlock().getDefaultState(),fence));
            var window=(net.minecraft.item.BlockItem)Blocks.GLASS_PANE.asItem();
            check("diagonal_pane_equivalent",PrinterRules.constructionMatches(window.getBlock().getDefaultState(),pane));
            check("diagonal_waterlog_adjust",PrinterRules.adjustable(item.getBlock().getDefaultState(),fence.with(Properties.WATERLOGGED,true),new PrinterSettings()));
            check("diagonal_other_material_rejected",!PrinterRules.placementMatches(item.getBlock().getDefaultState(),Blocks.DARK_OAK_FENCE.getDefaultState()));
        }
    }
    private void fixture()throws Exception{
        for(int x=0;x<3;x++){
            wanted.put(new BlockPos(x,4,0),Blocks.OAK_FENCE.getDefaultState().with(Properties.WEST,x>0).with(Properties.EAST,x<2));
            wanted.put(new BlockPos(x,4,2),Blocks.GLASS_PANE.getDefaultState().with(Properties.WEST,x>0).with(Properties.EAST,x<2));
            wanted.put(new BlockPos(x,4,4),Blocks.COBBLESTONE_WALL.getDefaultState().with(Properties.WEST_WALL_SHAPE,x>0?WallShape.LOW:WallShape.NONE).with(Properties.EAST_WALL_SHAPE,x<2?WallShape.LOW:WallShape.NONE));
        }
        wanted.put(new BlockPos(3,4,1),Blocks.OAK_FENCE.getDefaultState());
        wanted.put(new BlockPos(3,4,3),Blocks.GLASS_PANE.getDefaultState());
        wanted.put(new BlockPos(4,4,0),Blocks.OAK_FENCE_GATE.getDefaultState().with(Properties.OPEN,true));
        for(int x=0;x<3;x++)wanted.put(new BlockPos(x,4,1),Blocks.OAK_FENCE_GATE.getDefaultState()
            .with(Properties.HORIZONTAL_FACING,List.of(Direction.EAST,Direction.SOUTH,Direction.WEST).get(x)).with(Properties.OPEN,true));
        wanted.put(new BlockPos(4,4,1),Blocks.DARK_OAK_FENCE.getDefaultState().with(Properties.WATERLOGGED,true));
        wanted.put(new BlockPos(4,4,3),Blocks.OAK_TRAPDOOR.getDefaultState().with(Properties.OPEN,true));
        var door=Blocks.OAK_DOOR.getDefaultState().with(Properties.OPEN,true);
        wanted.put(new BlockPos(4,4,4),door.with(Properties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER));
        wanted.put(new BlockPos(4,5,4),door.with(Properties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
        for(var pos:List.of(new BlockPos(0,5,0),new BlockPos(0,5,2),new BlockPos(4,5,3)))wanted.put(pos,Blocks.QUARTZ_BLOCK.getDefaultState());
        var palette=new ArrayList<BlockStateSpec>();palette.add(BlockStateSpec.parse("minecraft:air"));
        int[] cells=new int[50];
        for(var e:wanted.entrySet()){
            var spec=StateResolver1201.spec(e.getValue());int id=palette.indexOf(spec);if(id<0){id=palette.size();palette.add(spec);}
            var p=e.getKey();cells[((p.getY()-4)*5+p.getZ())*5+p.getX()]=id;
        }
        var region=new Region("checks",Vec3i.ZERO,new Vec3i(5,2,5));
        var capture=new LitematicExport.Capture(region,palette,cells,List.of(),List.of(),List.of(),List.of());
        Path file=MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics/placement-check.litematic");Files.createDirectories(file.getParent());
        NbtWriter.writeSettings(file,LitematicExport.create("Placement regression","BetterLitematica checks",3465,Vec3i.ZERO,List.of(capture)));
    }
    private void tick(MinecraftClient mc)throws Exception{
        if(finished)return;if(++ticks>2600){finish(mc,"startup timeout");return;}
        if(!opened&&ticks>40&&mc.currentScreen!=null&&mc.getOverlay()==null){opened=true;((LoaderInvoker)mc.createIntegratedServerLoader()).startFixture(mc.currentScreen,"fixture",false,false);}
        if(mc.world==null||mc.player==null||mc.getServer()==null||++worldTicks<60)return;
        if(controller==null){
            var field=BetterLitematicaClient.class.getDeclaredField("activeController");field.setAccessible(true);controller=(ProjectionController)field.get(null);if(controller==null)return;
            rules();if(Boolean.getBoolean("placementCheck.rulesOnly")){finish(mc,null);return;}
            fixture();controller.printer().stop();controller.unloadAll();controller.options().capturePreviews=false;controller.options().accurate=AccuratePlacement.Mode.NONE;
            mc.setScreen(null);mc.player.setPosition(2,5.1,2);mc.player.setNoGravity(true);mc.player.getAbilities().flying=true;mc.player.setYaw(135);mc.player.setPitch(60);
            setup=new CompletableFuture<>();mc.getServer().execute(()->{
                try{
                    var player=mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());player.changeGameMode(GameMode.CREATIVE);player.getAbilities().flying=true;player.sendAbilitiesUpdate();player.teleport(player.getServerWorld(),2,5.1,2,135,60);
                    var world=player.getServerWorld();
                    for(int x=-2;x<=6;x++)for(int z=-2;z<=6;z++)for(int y=3;y<=8;y++)world.setBlockState(new BlockPos(x,y,z),y==3?Blocks.STONE.getDefaultState():Blocks.AIR.getDefaultState(),3);
                    setup.complete(null);
                }catch(Throwable e){setup.completeExceptionally(e);}
            });return;
        }
        if(!setup.isDone())return;setup.join();
        if(!loaded){controller.allLayers();controller.load("placement-check.litematic");loaded=true;return;}
        if(controller.printerPlacements().isEmpty()||!controller.entryStatus(controller.selectedId()).equals("已加载"))return;
        if(!started){
            controller.move(0,4,0);
            var s=new PrinterSettings();s.interval=0;s.perTick=0;s.cooldown=3;s.airPlace=false;s.range=0;s.containerFill=false;s.breakState=true;s.breakWrong=true;s.highlights=false;s.missingHud=true;
            controller.printer().configure(s);controller.printer().start();started=true;return;
        }
        if(!mc.isWindowFocused()){if(++focusWaitTicks>1200)finish(mc,"window never regained focus");return;}
        if(!controller.printer().running())controller.printer().start();
        printTicks++;
        var world=mc.getServer().getOverworld();boolean all=mc.getServer().submit(()->wanted.entrySet().stream().allMatch(e->PrinterRules.constructionMatches(world.getBlockState(e.getKey()),e.getValue()))).get();
        if(all){
            if(stable++==0)operationsAtCompletion=controller.printer().operations();
            if(stable>=60){
                check("all_server_targets",true);check("no_break_rebuild_loop",controller.printer().operations()==operationsAtCompletion);
                if(FabricLoader.getInstance().isModLoaded("diagonalblocks")){
                    var state=world.getBlockState(new BlockPos(3,4,3));
                    check("server_diagonal_connection",state.getEntries().entrySet().stream().anyMatch(e->e.getKey().getName().contains("_")&&Boolean.TRUE.equals(e.getValue())));
                }
                finish(mc,null);
            }
        }else stable=0;
        if(printTicks>=900){check("all_server_targets",false);finish(mc,"printing timeout: "+controller.printer().status());}
    }
    private void finish(MinecraftClient mc,String error){
        if(finished)return;finished=true;
        try{
            Files.createDirectories(root);var states=new LinkedHashMap<String,String>();
            if(mc.getServer()!=null)mc.getServer().submit(()->wanted.keySet().forEach(p->states.put(p.toShortString(),mc.getServer().getOverworld().getBlockState(p).toString()))).get();
            var result=new LinkedHashMap<String,Object>();result.put("passed",error==null&&checks.values().stream().allMatch(Boolean::booleanValue));result.put("checks",checks);result.put("error",error);result.put("serverStates",states);result.put("ticks",printTicks);result.put("operations",controller==null?0:controller.printer().operations());
            Files.writeString(root.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
            try(var image=ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())){image.writeTo(root.resolve("scene.png"));}
        }catch(Exception e){e.printStackTrace();}finally{if(controller!=null)controller.printer().stop();mc.scheduleStop();}
    }
}
