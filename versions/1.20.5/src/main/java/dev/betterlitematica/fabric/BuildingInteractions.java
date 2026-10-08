package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.SlabType;

/** Vanilla inventory and interaction packets only. Never edits the client/server world to fake a placement. */
final class BuildingInteractions {
    void editingEntered(){pendingPick=null;dragSelection=null;dragTarget=null;transfers.reset();wasUse=false;cooldown=0;}
    boolean preservePrinterBreaking(){return controller.printer().ownsBreaking();}
    void manualPrinterInteraction(){if(!controller.printer().acting()){pendingPick=null;controller.bedrock().manualInput();controller.printer().manualContainerInteraction();controller.printer().pause("已暂停");}}
    private final MinecraftClient client;private final ProjectionController controller;
    private final InventoryTransfers transfers;private int ticks;private net.minecraft.client.world.ClientWorld transferWorld;
    private final InputBindings bindings=new InputBindings();
    private AreaSelection dragSelection,dragLatest;private Vec3i dragOffset=Vec3i.ZERO;private SelectionTarget dragTarget;private Vec3d dragAnchor;private net.minecraft.client.world.ClientWorld dragWorld;
    private boolean using,wasUse;private int cooldown;private long lastHint;
    private Item pendingPick;private int pickExpires;
    BuildingInteractions(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;this.transfers=controller.inventoryTransfers();}
    void inputEvent(long window,int code,int action){bindings.event(client,controller.options(),window,code,action);}
    void suspendInput(){bindings.suspend();}
    void tick(){
        ticks++;if(client.world!=transferWorld){transferWorld=client.world;transfers.clear();pendingPick=null;}String transferFailure=transfers.settle(ticks);if(transferFailure!=null)controller.action(()->{throw new IllegalStateException(transferFailure);});
        bindings.tick(client,controller.options(),name->controller.action(()->hotkey(name)));
        controller.temporarilyHidden(!controller.editor().claimsInput()&&bindings.held("hideProjection"));

        if(client.world==null||client.player==null||client.interactionManager==null||client.currentScreen!=null||!client.isWindowFocused()){pendingPick=null;wasUse=false;cooldown=0;return;}
        if(pendingPick!=null){if(ticks>=pickExpires||controller.printer().running())pendingPick=null;else if(controller.action(this::continuePick)==0)pendingPick=null;}
        if(cooldown>0)cooldown--;
        boolean use=client.options.useKey.isPressed();
        if(enabled()&&use&&controller.options().hold&&cooldown==0)attempt();
        wasUse=use;
    }
    private boolean enabled(){return !controller.editor().claimsInput()&&!using&&!controller.printer().running()&&controller.projectionRenderingEnabled()&&(controller.options().easyPlace||bindings.held("easyPlaceHold"))&&client.player!=null&&client.world!=null&&client.interactionManager!=null&&client.currentScreen==null&&!hasTool()&&!client.player.isSpectator()&&!client.player.isUsingItem();}
    boolean use(){
        if(!enabled()||controller.target(client.player.getBlockInteractionRange())==null)return false;
        if(cooldown==0&&(controller.options().hold||!wasUse))attempt();
        return true;
    }
    private void attempt(){cooldown=4;controller.action(this::easyPlace);}
    private void hotkey(String name){if(controller.editor().active()&&!java.util.Set.of("editUndo","editRedo","menu","layerNext","layerPrevious","toolMode","toolModePrevious","toolSettings").contains(name))return;switch(name){
        case "editUndo"->controller.editor().undoInWorld(false);case "editRedo"->controller.editor().undoInWorld(true);
        case "restriction"->{controller.options().restriction=!controller.options().restriction;controller.saveOptions();}
        case "toolMode"->controller.tool().cycle(1);case "toolModePrevious"->controller.tool().cycle(-1);
        case "toolExecute"->controller.tool().executeHotkey();case "toolSettings"->client.setScreen(new ToolScreen(client.currentScreen,controller));
        case "toolSaveSelection"->client.setScreen(new SelectionScreen(client.currentScreen,controller));case "toolClone"->controller.tool().cloneSelection();case "toolResetOrigin"->controller.tool().resetSelectionOrigin();case "toolMoveSelection"->controller.tool().moveSelectionHere();
        case "toolGrow"->controller.tool().autoSize(true);case "toolShrink"->controller.tool().autoSize(false);case "toolSelectionShape"->controller.tool().cycleSelectionShape();case "toolNudgePositive"->controller.tool().nudge(1);case "toolNudgeNegative"->controller.tool().nudge(-1);
        case "layerModePrevious"->controller.cycleLayer(-1);
        case "layerFollow"->{controller.options().followLayer=!controller.options().followLayer;if(controller.options().followLayer)controller.layerAtPlayer();controller.saveOptions();}
        case "selectionFirst"->controller.selectionCorner(true);case "selectionSecond"->controller.selectionCorner(false);case "selectionOrigin"->controller.selectionOrigin();
        case "selectionRemove"->controller.tool().removeSelection();case "selectionMode"->controller.selectionMode();case "selectionAdd"->{var names=controller.selection().boxes().stream().map(SelectionBox::name).toList();int next=1;while(names.contains("区域 "+next))next++;controller.selectionAdd("区域 "+next);}
        case "placementHere"->controller.here();case "rotate"->controller.rotateNext();case "reload"->controller.reload();
        case "printer"->client.setScreen(new PrinterScreen(client.currentScreen,controller));
        case "printerWork"->controller.printer().toggle();case "printerStop"->controller.printer().stop();
        case "printerMode"->controller.printer().cycle();
        case "information"->{controller.options().display.information=!controller.options().display.information;controller.saveOptions();}
        case "layerMode"->controller.cycleLayer();case "layerPlayer"->controller.layerAtPlayer();
        case "pickLast"->{manualPrinterInteraction();var target=controller.target(client.player.getBlockInteractionRange(),true);if(target!=null){var needs=BuildMaterials.forState(target.state());if(!needs.isEmpty())selectItem(needs.get(needs.size()-1).item());}}

        case "menu"->client.setScreen(new ProjectionScreen(controller));case "placements"->client.setScreen(new PlacementListScreen(new ProjectionScreen(controller),controller));
        case "materials"->client.setScreen(new AnalysisScreen(new ProjectionScreen(controller),controller,true));case "verifier"->client.setScreen(new AnalysisScreen(new ProjectionScreen(controller),controller,false));
        case "selection"->client.setScreen(new SelectionScreen(new ProjectionScreen(controller),controller));case "settings"->client.setScreen(new OptionsScreen(new ProjectionScreen(controller),controller));
        case "easyPlace"->{controller.options().easyPlace=!controller.options().easyPlace;controller.saveOptions();client.player.sendMessage(net.minecraft.text.Text.literal(controller.options().easyPlace?"简单放置：开":"简单放置：关"),true);}
        case "rendering"->controller.toggleRendering();case "tool"->{controller.options().tool=!controller.options().tool;controller.saveOptions();}
        case "layerNext"->controller.shiftLayer(1);case "layerPrevious"->controller.shiftLayer(-1);default->{}
    }}
    boolean hasTool(){return controller.tool().held();}
    boolean blockClick(boolean first,BlockHitResult hit){
        if(controller.editor().active())return true;
        if(using||controller.printer().acting())return false;
        if(controller.tool().blocksVanilla())return true;

        if(!first&&(controller.options().restriction||bindings.held("restrictionHold"))){var target=controller.target(client.player.getBlockInteractionRange());
            if(target==null)return true;BlockPos expected=new BlockPos(target.position().x(),target.position().y(),target.position().z());BlockPos placed=client.world.getBlockState(hit.getBlockPos()).isReplaceable()?hit.getBlockPos():hit.getBlockPos().offset(hit.getSide());
            var needs=BuildMaterials.forState(target.state());return !placed.equals(expected)||needs.isEmpty()||!client.player.getMainHandStack().isOf(needs.get(needs.size()-1).item());
        }return false;
    }
    boolean pick(){
        if(controller.editor().pick())return true;
        if(controller.tool().blocksVanilla())return true;
        if(client.player==null||client.world==null||!controller.options().pick)return false;
        var target=controller.target(client.player.getBlockInteractionRange());if(target==null)return false;var needs=BuildMaterials.forState(target.state());if(needs.isEmpty())return true;controller.action(()->selectItem(needs.get(needs.size()-1).item()));return true;
    }
    boolean scroll(double amount){return controller.tool().scroll(amount);}
    private void selectItem(Item item){pendingPick=item;pickExpires=ticks+100;continuePick();}
    private void continuePick(){var result=transfers.equip(pendingPick,ticks);if(result!=InventoryTransfers.Result.WAIT){if(result==InventoryTransfers.Result.MISSING)hint("缺少："+pendingPick.getName().getString());pendingPick=null;}}
    private void hint(String message){
        long now=System.nanoTime();if(now-lastHint<1_500_000_000L)return;lastHint=now;
        client.player.sendMessage(net.minecraft.text.Text.literal(message),true);
    }
    private void easyPlace(){
        if(client.interactionManager==null)return;double reach=client.player.getBlockInteractionRange();var target=controller.target(reach);if(target==null)return;
        var at=target.position();BlockPos pos=new BlockPos(at.x(),at.y(),at.z());if(!WorldChunks.loaded(client.world,pos))return;
        var existing=client.world.getBlockState(pos);if(existing.equals(target.state()))return;
        boolean doubleSlab=target.state().contains(Properties.SLAB_TYPE)&&target.state().get(Properties.SLAB_TYPE)==SlabType.DOUBLE;
        if(!existing.isReplaceable()&&!(doubleSlab&&existing.getBlock()==target.state().getBlock()))return;
        // Do not target projection blocks behind a real wall.
        var obstruction=client.world.raycast(new net.minecraft.world.RaycastContext(client.player.getEyePos(),Vec3d.ofCenter(pos),net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
        if(obstruction.getType()!=HitResult.Type.MISS&&!obstruction.getBlockPos().equals(pos))return;
        var needs=BuildMaterials.forState(target.state());
        if(needs.isEmpty()||!(needs.get(needs.size()-1).item() instanceof BlockItem)){hint("此方块需手动放置");return;}
        Item item=needs.get(needs.size()-1).item();
        var selected=transfers.equip(item,ticks);if(selected!=InventoryTransfers.Result.READY){if(selected==InventoryTransfers.Result.MISSING)hint("缺少："+item.getName().getString());return;}
        ItemStack stack=client.player.getMainHandStack();BlockItem blockItem=(BlockItem)stack.getItem();
        for(Direction side:Direction.values())for(double height:new double[]{0.25,0.75}){
            // Vanilla accepts a replaceable target directly, including unsupported floating cubes.
            Vec3d point=EasyPlacementRules.hitPoint(pos,side,height);
            if(client.player.getEyePos().squaredDistanceTo(point)>reach*reach)continue;
            BlockHitResult hit=new BlockHitResult(point,side,pos,false);
            ItemPlacementContext context=new ItemPlacementContext(client.player,Hand.MAIN_HAND,stack,hit);
            if(!context.canPlace()||!context.getBlockPos().equals(pos))continue;
            var mode=AccuratePlacement.resolve(client,controller.options().accurate);
            var predicted=AccuratePlacement.predict(mode,context,target.state(),hit);
            if(!EasyPlacementRules.matches(predicted,target.state())||!predicted.canPlaceAt(client.world,pos))continue;
            using=true;
            try{var result=AccuratePlacement.use(client,mode,pos,target.state(),hit);if(result.shouldSwingHand())client.player.swingHand(Hand.MAIN_HAND);}
            finally{using=false;}
            return;
        }
        hint("无法按投影状态放置");
    }
}
