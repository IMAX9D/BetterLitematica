package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Vanilla inventory and interaction packets only. Never edits the client/server world to fake a placement. */
final class BuildingInteractions {
    void editingEntered(){pendingPick=null;dragSelection=null;dragTarget=null;transfers.reset();wasUse=false;cooldown=0;}
    boolean preservePrinterBreaking(){return controller.printer().ownsBreaking();}
    void manualPrinterInteraction(){if(!controller.printer().acting()){pendingPick=null;controller.bedrock().manualInput();controller.printer().manualContainerInteraction();controller.printer().pause("已暂停");}}
    private final Minecraft client;private final ProjectionController controller;
    private final InventoryTransfers transfers;private int ticks;private net.minecraft.client.multiplayer.ClientLevel transferWorld;
    private final InputBindings bindings=new InputBindings();
    private AreaSelection dragSelection,dragLatest;private Vec3i dragOffset=Vec3i.ZERO;private SelectionTarget dragTarget;private Vec3 dragAnchor;private net.minecraft.client.multiplayer.ClientLevel dragWorld;
    private boolean using,wasUse;private int cooldown;private long lastHint;
    private Item pendingPick;private int pickExpires;
    BuildingInteractions(Minecraft client,ProjectionController controller){this.client=client;this.controller=controller;this.transfers=controller.inventoryTransfers();}
    void inputEvent(long window,int code,int action){bindings.event(client,controller.options(),window,code,action);}
    void suspendInput(){bindings.suspend();}
    void tick(){
        ticks++;if(client.level!=transferWorld){transferWorld=client.level;transfers.clear();pendingPick=null;}String transferFailure=transfers.settle(ticks);if(transferFailure!=null)controller.action(()->{throw new IllegalStateException(transferFailure);});
        bindings.tick(client,controller.options(),name->controller.action(()->hotkey(name)));
        controller.temporarilyHidden(!controller.editor().claimsInput()&&bindings.held("hideProjection"));

        if(client.level==null||client.player==null||client.gameMode==null||ClientUi.screen(client)!=null||!client.isWindowActive()){pendingPick=null;wasUse=false;cooldown=0;return;}
        if(pendingPick!=null){if(ticks>=pickExpires||controller.printer().running())pendingPick=null;else if(controller.action(this::continuePick)==0)pendingPick=null;}
        if(cooldown>0)cooldown--;
        boolean use=client.options.keyUse.isDown();
        if(enabled()&&use&&controller.options().hold&&cooldown==0)attempt();
        wasUse=use;
    }
    private boolean enabled(){return !controller.editor().claimsInput()&&!using&&!controller.printer().running()&&controller.projectionRenderingEnabled()&&(controller.options().easyPlace||bindings.held("easyPlaceHold"))&&client.player!=null&&client.level!=null&&client.gameMode!=null&&ClientUi.screen(client)==null&&!hasTool()&&!client.player.isSpectator()&&!client.player.isUsingItem();}
    boolean use(){
        if(!enabled()||controller.target(client.player.blockInteractionRange())==null)return false;
        if(cooldown==0&&(controller.options().hold||!wasUse))attempt();
        return true;
    }
    private void attempt(){cooldown=4;controller.action(this::easyPlace);}
    private void hotkey(String name){if(controller.editor().active()&&!java.util.Set.of("editUndo","editRedo","menu","layerNext","layerPrevious","toolMode","toolModePrevious","toolSettings").contains(name))return;switch(name){
        case "editUndo"->controller.editor().undoInWorld(false);case "editRedo"->controller.editor().undoInWorld(true);
        case "restriction"->{controller.options().restriction=!controller.options().restriction;controller.saveOptions();}
        case "toolMode"->controller.tool().cycle(1);case "toolModePrevious"->controller.tool().cycle(-1);
        case "toolExecute"->controller.tool().executeHotkey();case "toolSettings"->ClientUi.setScreen(client,new ToolScreen(ClientUi.screen(client),controller));
        case "toolSaveSelection"->ClientUi.setScreen(client,new SelectionScreen(ClientUi.screen(client),controller));case "toolClone"->controller.tool().cloneSelection();case "toolResetOrigin"->controller.tool().resetSelectionOrigin();case "toolMoveSelection"->controller.tool().moveSelectionHere();
        case "toolGrow"->controller.tool().autoSize(true);case "toolShrink"->controller.tool().autoSize(false);case "toolSelectionShape"->controller.tool().cycleSelectionShape();case "toolNudgePositive"->controller.tool().nudge(1);case "toolNudgeNegative"->controller.tool().nudge(-1);
        case "layerModePrevious"->controller.cycleLayer(-1);
        case "layerFollow"->{controller.options().followLayer=!controller.options().followLayer;if(controller.options().followLayer)controller.layerAtPlayer();controller.saveOptions();}
        case "selectionFirst"->controller.selectionCorner(true);case "selectionSecond"->controller.selectionCorner(false);case "selectionOrigin"->controller.selectionOrigin();
        case "selectionRemove"->controller.tool().removeSelection();case "selectionMode"->controller.selectionMode();case "selectionAdd"->{var names=controller.selection().boxes().stream().map(SelectionBox::name).toList();int next=1;while(names.contains("区域 "+next))next++;controller.selectionAdd("区域 "+next);}
        case "placementHere"->controller.here();case "rotate"->controller.rotateNext();case "reload"->controller.reload();
        case "printer"->ClientUi.setScreen(client,new PrinterScreen(ClientUi.screen(client),controller));
        case "printerWork"->controller.printer().toggle();case "printerStop"->controller.printer().stop();
        case "printerMode"->controller.printer().cycle();
        case "information"->{controller.options().display.information=!controller.options().display.information;controller.saveOptions();}
        case "layerMode"->controller.cycleLayer();case "layerPlayer"->controller.layerAtPlayer();
        case "pickLast"->{manualPrinterInteraction();var target=controller.target(client.player.blockInteractionRange(),true);if(target!=null){var needs=BuildMaterials.forState(target.state());if(!needs.isEmpty())selectItem(needs.get(needs.size()-1).item());}}

        case "menu"->ClientUi.setScreen(client,new ProjectionScreen(controller));case "placements"->ClientUi.setScreen(client,new PlacementListScreen(new ProjectionScreen(controller),controller));
        case "materials"->ClientUi.setScreen(client,new AnalysisScreen(new ProjectionScreen(controller),controller,true));case "verifier"->ClientUi.setScreen(client,new AnalysisScreen(new ProjectionScreen(controller),controller,false));
        case "selection"->ClientUi.setScreen(client,new SelectionScreen(new ProjectionScreen(controller),controller));case "settings"->ClientUi.setScreen(client,new OptionsScreen(new ProjectionScreen(controller),controller));
        case "easyPlace"->{controller.options().easyPlace=!controller.options().easyPlace;controller.saveOptions();client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(controller.options().easyPlace?"简单放置：开":"简单放置：关"));}
        case "rendering"->controller.toggleRendering();case "tool"->{controller.options().tool=!controller.options().tool;controller.saveOptions();}
        case "layerNext"->controller.shiftLayer(1);case "layerPrevious"->controller.shiftLayer(-1);default->{}
    }}
    boolean hasTool(){return controller.tool().held();}
    boolean blockClick(boolean first,BlockHitResult hit){
        if(controller.editor().active())return true;
        if(using||controller.printer().acting())return false;
        if(controller.tool().blocksVanilla())return true;

        if(!first&&(controller.options().restriction||bindings.held("restrictionHold"))){var target=controller.target(client.player.blockInteractionRange());
            if(target==null)return true;BlockPos expected=new BlockPos(target.position().x(),target.position().y(),target.position().z());BlockPos placed=client.level.getBlockState(hit.getBlockPos()).canBeReplaced()?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());
            var needs=BuildMaterials.forState(target.state());return !placed.equals(expected)||needs.isEmpty()||!client.player.getMainHandItem().is(needs.get(needs.size()-1).item());
        }return false;
    }
    boolean pick(){
        if(controller.editor().pick())return true;
        if(controller.tool().blocksVanilla())return true;
        if(client.player==null||client.level==null||!controller.options().pick)return false;
        var target=controller.target(client.player.blockInteractionRange());if(target==null)return false;var needs=BuildMaterials.forState(target.state());if(needs.isEmpty())return true;controller.action(()->selectItem(needs.get(needs.size()-1).item()));return true;
    }
    boolean scroll(double amount){return controller.tool().scroll(amount);}
    private void selectItem(Item item){pendingPick=item;pickExpires=ticks+100;continuePick();}
    private void continuePick(){var result=transfers.equip(pendingPick,ticks);if(result!=InventoryTransfers.Result.WAIT){if(result==InventoryTransfers.Result.MISSING)hint("缺少："+pendingPick.getName(new ItemStack(pendingPick)).getString());pendingPick=null;}}
    private void hint(String message){
        long now=System.nanoTime();if(now-lastHint<1_500_000_000L)return;lastHint=now;
        client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(message));
    }
    private void easyPlace(){
        if(client.gameMode==null)return;double reach=client.player.blockInteractionRange();var target=controller.target(reach);if(target==null)return;
        var at=target.position();BlockPos pos=new BlockPos(at.x(),at.y(),at.z());if(!WorldChunks.loaded(client.level,pos))return;
        var existing=client.level.getBlockState(pos);if(existing.equals(target.state()))return;
        boolean doubleSlab=target.state().hasProperty(BlockStateProperties.SLAB_TYPE)&&target.state().getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE;
        if(!existing.canBeReplaced()&&!(doubleSlab&&existing.getBlock()==target.state().getBlock()))return;
        // Do not target projection blocks behind a real wall.
        var obstruction=client.level.clip(new net.minecraft.world.level.ClipContext(client.player.getEyePosition(),Vec3.atCenterOf(pos),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,client.player));
        if(obstruction.getType()!=HitResult.Type.MISS&&!obstruction.getBlockPos().equals(pos))return;
        var needs=BuildMaterials.forState(target.state());
        if(needs.isEmpty()||!(needs.get(needs.size()-1).item() instanceof BlockItem)){hint("此方块需手动放置");return;}
        Item item=needs.get(needs.size()-1).item();
        var selected=transfers.equip(item,ticks);if(selected!=InventoryTransfers.Result.READY){if(selected==InventoryTransfers.Result.MISSING)hint("缺少："+item.getName(new ItemStack(item)).getString());return;}
        ItemStack stack=client.player.getMainHandItem();BlockItem blockItem=(BlockItem)stack.getItem();
        for(Direction side:Direction.values())for(double height:new double[]{0.25,0.75}){
            // Vanilla accepts a replaceable target directly, including unsupported floating cubes.
            Vec3 point=EasyPlacementRules.hitPoint(pos,side,height);
            if(client.player.getEyePosition().distanceToSqr(point)>reach*reach)continue;
            BlockHitResult hit=new BlockHitResult(point,side,pos,false);
            BlockPlaceContext context=new BlockPlaceContext(client.player,InteractionHand.MAIN_HAND,stack,hit);
            if(!context.canPlace()||!context.getClickedPos().equals(pos))continue;
            var mode=AccuratePlacement.resolve(client,controller.options().accurate);
            var predicted=AccuratePlacement.predict(mode,context,target.state(),hit);
            if(!EasyPlacementRules.matches(predicted,target.state())||!predicted.canSurvive(client.level,pos))continue;
            using=true;
            try{var result=AccuratePlacement.use(client,mode,pos,target.state(),hit);VersionGameplay.swingUse(client.player,InteractionHand.MAIN_HAND,result);}
            finally{using=false;}
            return;
        }
        hint("无法按投影状态放置");
    }
}
