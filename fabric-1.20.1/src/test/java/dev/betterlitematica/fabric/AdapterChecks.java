package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.SharedConstants;
import net.minecraft.Bootstrap;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.item.Items;
import java.util.List;

/** Headless checks against actual 1.20.1 registries; does not start a game or modify a world. */
public final class AdapterChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        SharedConstants.createGameVersion();Bootstrap.initialize();
        checks+=PreviewAdapterChecks.run();
        checks+=UiDesignChecks.run();checks+=UiTextureQueueChecks.run();checks+=HudNumberChecks.run();checks+=ToolHudChecks.run();
        checks+=ToolEditorChecks.run();checks+=ToolInventoryChecks.run();checks+=ToolInteractionChecks.run();checks+=ToolSelectionResizeChecks.run();
        checks+=ToolWorldOperationChecks.run();
        checks+=PasteSchedulingChecks.run();
        checks+=CommandWakeupChecks.run();
        checks+=PrinterContainerSettingsChecks.run();
        checks+=ContainerFillPlanChecks.run();
        checks+=ContainerPrintTargetChecks.run();
        checks+=SceneChangesChecks.run();
        checks+=WheelRenderModeChecks.run();
        checks+=WheelRenderUiChecks.run();
        checks+=PrinterThroughputChecks.run();
        checks+=PlacementValidationChecks.run();
        checks+=InputEntryChecks.run();
        checks+=PrinterCompositionChecks.run();
        checks+=NearbyProjectionChecks.run();
        checks+=NearbyMovementChecks.run();
        checks+=NearbyStabilityChecks.run();
        checks+=ProjectionTintChecks.run();
        checks+=ColorPickerChecks.run();
        checks+=ProjectionInfoDataChecks.run();
        checks+=ProjectionInventoryViewChecks.run();
        checks+=HighlightCuboidChecks.run();
        checks+=HighlightRangeChecks.run();
        checks+=ModeWheelGeometryChecks.run();
        checks+=ModeWheelChecks.run();checks+=SubregionAndSearchChecks.run();
        var heldLight=new net.minecraft.item.ItemStack(Items.LIGHT);heldLight.getOrCreateSubNbt("BlockStateTag").putString("level","5");check(StateResolver1201.itemState(Blocks.LIGHT.getDefaultState(),heldLight).get(LightBlock.LEVEL_15)==5,"Editing keeps held light level");heldLight.getOrCreateSubNbt("BlockStateTag").putString("level","99");check(StateResolver1201.itemState(Blocks.LIGHT.getDefaultState(),heldLight).equals(Blocks.LIGHT.getDefaultState()),"Invalid item property uses placement default");heldLight.getOrCreateSubNbt("BlockStateTag").putString("missing","anything");check(StateResolver1201.itemState(Blocks.STONE.getDefaultState(),heldLight).isOf(Blocks.STONE),"Unknown item property is ignored like vanilla");
        check(BuildMaterials.forState(Blocks.AIR.getDefaultState()).isEmpty(),"Air has no item cost");
        check(BuildMaterials.forState(Blocks.STONE.getDefaultState()).get(0).item()==Items.STONE,"Stone item");
        check(BuildMaterials.forState(Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.HALF,DoubleBlockHalf.UPPER)).isEmpty(),"Door upper is not counted twice");
        check(BuildMaterials.forState(Blocks.RED_BED.getDefaultState().with(BedBlock.PART,BedPart.HEAD)).isEmpty(),"Bed head is not counted twice");
        check(BuildMaterials.forState(Blocks.OAK_SLAB.getDefaultState().with(SlabBlock.TYPE,SlabType.DOUBLE)).get(0).count()==2,"Double slab cost");
        check(BuildMaterials.forState(Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS,7)).get(0).count()==7,"Snow layers");
        check(BuildMaterials.forState(Blocks.POTTED_DANDELION.getDefaultState()).size()==2,"Flower pot and contents");
        check(BuildMaterials.forState(Blocks.WATER.getDefaultState()).get(0).item()==Items.WATER_BUCKET,"Water source bucket");
        check(BuildMaterials.forState(Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL,2)).isEmpty(),"Flowing water is not a bucket");
        check(BuildMaterials.forState(Blocks.FARMLAND.getDefaultState()).get(0).item()==Items.DIRT,"Farmland build material");
        check(BuildMaterials.forState(Blocks.TURTLE_EGG.getDefaultState().with(TurtleEggBlock.EGGS,4)).get(0).count()==4,"Egg count");
        check(BuildMaterials.forState(Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES,3)).get(0).count()==3,"Candle count");
        var resolver=new StateResolver1201(List.of(BlockStateSpec.parse("minecraft:oak_stairs[facing=north,half=top]"),BlockStateSpec.parse("missing:unknown")),new PlacementTransform(Vec3i.ZERO,1,false,false));
        check(resolver.resolve(0).get(StairsBlock.FACING)==net.minecraft.util.math.Direction.EAST,"Rotate stair state");
        check(resolver.resolve(0).get(StairsBlock.HALF)==BlockHalf.TOP,"Preserve top stair");check(resolver.unresolved(1),"Unknown state is explicit");
        for(var block:List.of(Blocks.OAK_STAIRS,Blocks.OAK_DOOR,Blocks.POWERED_RAIL,Blocks.OAK_LOG,Blocks.CHEST,Blocks.WHITE_BED,Blocks.OAK_SIGN))for(var state:block.getStateManager().getStates())for(int turn=0;turn<4;turn++)for(int mirror=0;mirror<4;mirror++){
            var transform=new PlacementTransform(new Vec3i(10,-6,20),turn,(mirror&1)!=0,(mirror&2)!=0);var transformed=new StateResolver1201(List.of(StateResolver1201.spec(state)),transform).resolve(0);check(StateResolver1201.unplace(transformed,transform).equals(state),"Editor state transform round trip: "+state+" / "+turn+" / "+mirror);
        }
        var nbt=new net.minecraft.nbt.NbtCompound();nbt.putLongArray("a",new long[]{1,Long.MIN_VALUE});nbt.putByte("b",(byte)7);nbt.putString("s","测试");
        check(NbtBridge.game(NbtBridge.compound(nbt)).equals(nbt),"NBT round trip types");
        var identities=new CapturedEntities();var rootEntity=new net.minecraft.nbt.NbtCompound();rootEntity.putUuid("UUID",java.util.UUID.randomUUID());var passenger=new net.minecraft.nbt.NbtCompound();passenger.putUuid("UUID",java.util.UUID.randomUUID());var passengers=new net.minecraft.nbt.NbtList();passengers.add(passenger);rootEntity.put("Passengers",passengers);check(identities.accept(rootEntity)&&identities.size()==2,"Capture records root and passenger identities");check(!identities.accept(rootEntity.copy())&&!identities.accept(passenger.copy()),"Moving roots and detached passengers are never captured twice");var newVehicle=new net.minecraft.nbt.NbtCompound();newVehicle.putUuid("UUID",java.util.UUID.randomUUID());newVehicle.put("Passengers",passengers.copy());check(identities.accept(newVehicle)&&newVehicle.getList("Passengers",10).isEmpty()&&identities.size()==3,"A previously captured entity cannot reappear as another vehicle passenger");
        boolean limited=false;try{NbtBridge.compound(nbt,new NbtBridge.Budget(10));}catch(IllegalArgumentException expected){limited=true;}check(limited,"NBT budget enforced before copying");
        var settings=new InteractionOptions();settings.mode="SELECTION";settings.easyPlace=true;settings.keys.put("menu","CTRL+M");settings.toolItem="minecraft:blaze_rod";settings.protectedHotbar=273;settings.followLayer=true;settings.keys.put("hideProjection","ALT+H");
        var dir=java.nio.file.Files.createTempDirectory("bl-options-");var file=dir.resolve("settings.json");try{InteractionOptions.write(file,settings.snapshot());var restored=InteractionOptions.read(file);check(restored.mode.equals("SELECTION")&&restored.easyPlace&&restored.keys.get("menu").equals("CTRL+M"),"Interaction settings survive restart");check(restored.toolItem.equals("minecraft:blaze_rod")&&restored.protectedHotbar==273&&restored.followLayer&&restored.keys.get("hideProjection").equals("ALT+H"),"Tool, protected hotbar, follow layer and hold hotkey survive restart");}finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.deleteIfExists(dir);}
        var inventoryPolicy=new net.minecraft.entity.player.PlayerInventory(null);inventoryPolicy.selectedSlot=0;inventoryPolicy.setStack(0,new net.minecraft.item.ItemStack(Items.STICK));check(InventoryPolicy.destination(inventoryPolicy,0,Items.STICK)==1,"Automatic equip preserves the active selection tool");inventoryPolicy.selectedSlot=4;check(InventoryPolicy.destination(inventoryPolicy,1<<4,Items.STICK)==1,"Protected selected slot chooses another empty slot");check(InventoryPolicy.destination(inventoryPolicy,0,Items.STICK)==4,"Unprotected selected slot remains preferred");boolean fullProtection=false;try{InventoryPolicy.destination(inventoryPolicy,511,null);}catch(IllegalStateException e){fullProtection=true;}check(fullProtection,"All protected slots reject a transfer before sending");
        var retainedMaterials=new net.minecraft.entity.player.PlayerInventory(null);retainedMaterials.setStack(0,new net.minecraft.item.ItemStack(Items.STONE));retainedMaterials.setStack(1,new net.minecraft.item.ItemStack(Items.STICK));
        check(java.lang.reflect.Modifier.isPublic(net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot").getModifiers()),"Fabric exposes vanilla selected-slot synchronization before printer interactions");
        check(java.lang.reflect.Modifier.isPublic(net.minecraft.client.gl.VertexBuffer.class.getDeclaredMethod("getIndexType").getModifiers()),"Fabric exposes current shared index format for masked projection draws");
        check(InventoryPolicy.printerDestination(retainedMaterials,1<<2,Items.STICK)==3,"Printer retains held material and tool while skipping protected empty slots");
        check(InventoryPolicy.destination(retainedMaterials,1<<2,Items.STICK)==0,"Printer retention does not change ordinary pick or Easy Place destination rules");
        retainedMaterials.setStack(3,new net.minecraft.item.ItemStack(Items.DIRT));retainedMaterials.selectedSlot=3;
        check(InventoryTransfers.find(retainedMaterials,s->s.isOf(Items.STONE))==0,"Earlier printer material remains directly selectable in the hotbar");
        for(int i=2;i<9;i++)retainedMaterials.setStack(i,new net.minecraft.item.ItemStack(Items.DIRT));
        check(InventoryPolicy.printerDestination(retainedMaterials,0,Items.STICK)==3,"Full printer hotbar reuses the permitted selected slot");
        check(InventoryPolicy.printerDestination(retainedMaterials,1<<3,Items.STICK)==0,"Full printer hotbar still respects protected slots");
        var picks=new net.minecraft.entity.player.PlayerInventory(null);picks.setStack(0,new net.minecraft.item.ItemStack(Items.STICK));var pickSteps=new java.util.ArrayList<String>();
        InventoryTransfers.creativePick(picks,Items.STONE,2,stack->{check(picks.getStack(2).isOf(Items.STONE)&&picks.selectedSlot==0,"Creative item is locally present before the ordinary creative inventory packet");pickSteps.add("item");},()->{check(picks.selectedSlot==2,"Selected-slot synchronization follows creative inventory submission");pickSteps.add("slot");});
        check(pickSteps.equals(List.of("item","slot"))&&picks.getMainHandStack().isOf(Items.STONE),"Integrated creative pick is ready in the same call without a fabricated receipt");
        InventoryTransfers.creativePick(picks,Items.DIRT,3,stack->pickSteps.add("item"),()->pickSteps.add("slot"));check(picks.getStack(2).isOf(Items.STONE)&&picks.getMainHandStack().isOf(Items.DIRT),"Consecutive creative materials retain the previous hotbar item");
        boolean pickFailed=false;try{InventoryTransfers.creativePick(picks,Items.GLASS,2,stack->{throw new IllegalStateException("Injected send failure");},()->{throw new AssertionError("Unsent item cannot change selected slot");});}catch(IllegalStateException e){pickFailed=true;}check(pickFailed&&picks.getStack(2).isOf(Items.STONE)&&picks.selectedSlot==3,"Failed creative submission restores the destination without selecting it");
        var echoes=new InventoryTransfers.CreativeEchoes();var creativeA=new net.minecraft.item.ItemStack(Items.STONE);var creativeB=new net.minecraft.item.ItemStack(Items.GLASS);
        echoes.sent(2,creativeA);echoes.sent(2,creativeB);check(echoes.reassert(2,creativeA)&&!echoes.reassert(2,creativeB),"Old creative A echo cannot be used while the server's latest ordered item is B");
        echoes.sent(2,creativeA);check(!echoes.reassert(2,creativeA)&&echoes.reassert(2,creativeB),"A to B to A keeps intent even after an earlier same-item echo");
        creativeA.getOrCreateNbt().putString("custom","changed");check(echoes.reassert(2,creativeA),"Creative intent owns an immutable copy including stack data");
        check(!echoes.reassert(1,creativeB),"A tracked creative slot never contaminates another hotbar slot");echoes.clear();check(!echoes.reassert(2,creativeB),"World disconnect clears creative packet intent");
        boolean printerProtection=false;try{InventoryPolicy.printerDestination(retainedMaterials,511,null);}catch(IllegalStateException e){printerProtection=true;}check(printerProtection,"Printer cannot replace any item when all hotbar slots are protected");
        check(PrinterActions.compostMaterial(Items.WHEAT_SEEDS,List.of("minecraft:melon_seeds","wheat_seeds"),i->true)==Items.WHEAT_SEEDS,"Composting retains an allowed held material including default namespace IDs");
        check(PrinterActions.compostMaterial(Items.STONE,List.of("wheat_seeds","melon_seeds"),i->i==Items.MELON_SEEDS)==Items.MELON_SEEDS,"Composting selects available allowed material");
        check(PrinterActions.compostMaterial(Items.STONE,List.of("wheat_seeds"),i->false)==Items.WHEAT_SEEDS,"Missing compost material retains its supply request identity");
        check(PrinterActions.compostMaterial(Items.STONE,List.of(),i->true)==Items.AIR&&PrinterActions.compostMaterial(Items.STONE,List.of("stone","bad id","missing_item"),i->true)==Items.AIR,"Empty or unusable compost choices never select unrelated material");
        var toolInventory=new net.minecraft.entity.player.PlayerInventory(null);var silk=new net.minecraft.item.ItemStack(Items.DIAMOND_PICKAXE);silk.addEnchantment(net.minecraft.enchantment.Enchantments.SILK_TOUCH,1);var ordinary=new net.minecraft.item.ItemStack(Items.DIAMOND_PICKAXE);toolInventory.setStack(0,silk);toolInventory.setStack(12,ordinary);
        check(!PrinterActions.iceTool(silk)&&PrinterActions.iceTool(ordinary),"Ice tools distinguish enchantments on the same item type");check(InventoryTransfers.find(toolInventory,PrinterActions::iceTool)==12,"Filtered equip skips selected silk tool and finds matching inventory slot");toolInventory.selectedSlot=12;check(InventoryTransfers.find(toolInventory,PrinterActions::iceTool)==12,"Matching selected tool remains selected");toolInventory.setStack(12,net.minecraft.item.ItemStack.EMPTY);check(InventoryTransfers.find(toolInventory,PrinterActions::iceTool)==-1,"No valid tool is never confused with an empty slot");check(!PrinterActions.iceTool(new net.minecraft.item.ItemStack(Items.STONE)),"Blocks are not pickaxes");
        var commandSettings=new CommandSettings();commandSettings.perTick=23;commandSettings.interval=7;var exportSettings=commandSettings.copy();exportSettings.merge=false;check(exportSettings.perTick==23&&exportSettings.interval==7&&commandSettings.merge,"Export settings preserve unrelated send preferences and do not mutate their snapshot");
        check(!CommandPlans.storage("bl:session",0,12).equals(CommandPlans.storage("bl:session",1,12))&&!CommandPlans.storage("bl:session",0,12).equals(CommandPlans.storage("bl:session",0,13)),"Interleaved NBT transactions have distinct storage identities across regions and cells");
        check(ProjectionRenderer1201.hidesFace(Blocks.GLASS.getDefaultState(),Blocks.GLASS.getDefaultState(),net.minecraft.util.math.Direction.EAST),"Adjacent glass internal face removed");
        check(ProjectionRenderer1201.completedBlock(true,Blocks.GLASS.getDefaultState(),Blocks.GLASS.getDefaultState()),"Completed transparent block is hidden from the projection");
        check(!ProjectionRenderer1201.completedBlock(false,Blocks.GLASS.getDefaultState(),Blocks.GLASS.getDefaultState()),"Unloaded world block is never treated as completed");
        check(!ProjectionRenderer1201.completedBlock(true,Blocks.AIR.getDefaultState(),Blocks.GLASS.getDefaultState()),"Removing a completed world block restores its projection");
        check(!ProjectionRenderer1201.completedBlock(true,Blocks.STONE.getDefaultState(),Blocks.GLASS.getDefaultState()),"Wrong material remains visible");
        check(!ProjectionRenderer1201.completedBlock(true,Blocks.OAK_STAIRS.getDefaultState(),Blocks.OAK_STAIRS.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.SOUTH)),"Correct material with wrong orientation remains visible");
        check(ProjectionRenderer1201.completedBlock(true,Blocks.LIGHT.getDefaultState(),Blocks.LIGHT.getDefaultState())&&ProjectionRenderer1201.completedBlock(true,Blocks.CHEST.getDefaultState(),Blocks.CHEST.getDefaultState())&&ProjectionRenderer1201.completedBlock(true,Blocks.WATER.getDefaultState(),Blocks.WATER.getDefaultState()),"Completion applies to light markers, block entity models and fluids");
        var modelFailure=new StateResolver1201(List.of(BlockStateSpec.parse("minecraft:stone"),BlockStateSpec.parse("minecraft:missing_block")),new PlacementTransform(Vec3i.ZERO,0,false,false));modelFailure.markUnsupported(0);
        check(!modelFailure.unresolvedState(0)&&ProjectionRenderer1201.completedBlock(true,Blocks.STONE.getDefaultState(),modelFailure.resolve(0)),"Known completed block still hides when its projection model was unsupported");
        check(modelFailure.unresolvedState(1),"Unknown block diagnostic substitute cannot count as an actual completed state");
        check(ProjectionRenderer1201.hidesFace(Blocks.STONE.getDefaultState(),Blocks.STONE.getDefaultState(),net.minecraft.util.math.Direction.UP),"Solid internal face removed");
        check(!ProjectionRenderer1201.hidesFace(Blocks.STONE.getDefaultState(),Blocks.AIR.getDefaultState(),net.minecraft.util.math.Direction.UP),"Exposed face retained");
        check(!ProjectionRenderer1201.hidesFace(Blocks.STONE.getDefaultState(),Blocks.OAK_SLAB.getDefaultState(),net.minecraft.util.math.Direction.EAST),"Partial slab does not occlude a full face");
        check(EasyPlacementRules.matches(Blocks.STONE.getDefaultState(),Blocks.STONE.getDefaultState()),"Simple cube placement");
        check(!EasyPlacementRules.matches(Blocks.DIRT.getDefaultState(),Blocks.STONE.getDefaultState()),"Wrong material rejected");
        check(!EasyPlacementRules.matches(null,Blocks.STONE.getDefaultState()),"Invalid placement rejected");
        var slab=Blocks.OAK_SLAB.getDefaultState();
        check(EasyPlacementRules.matches(slab.with(net.minecraft.state.property.Properties.SLAB_TYPE,SlabType.BOTTOM),slab.with(net.minecraft.state.property.Properties.SLAB_TYPE,SlabType.DOUBLE)),"Double slab supports first placement");
        check(!EasyPlacementRules.matches(slab.with(net.minecraft.state.property.Properties.SLAB_TYPE,SlabType.BOTTOM),slab.with(net.minecraft.state.property.Properties.SLAB_TYPE,SlabType.TOP)),"Wrong slab half rejected");
        var stair=Blocks.OAK_STAIRS.getDefaultState();
        check(!EasyPlacementRules.matches(stair.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.NORTH),stair.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.SOUTH)),"Wrong facing rejected");
        for(var side:net.minecraft.util.math.Direction.values()){
            var point=EasyPlacementRules.hitPoint(net.minecraft.util.math.BlockPos.ORIGIN,side,0.75);
            check(point.x>=0&&point.x<=1&&point.y>=0&&point.y<=1&&point.z>=0&&point.z<=1,"Hit point stays on target block");
        }
        var protocolPos=new net.minecraft.util.math.BlockPos(-120,64,500);
        var protocolHit=new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(protocolPos),net.minecraft.util.math.Direction.UP,protocolPos,false);
        for(var block:List.of(Blocks.OAK_STAIRS,Blocks.OAK_DOOR,Blocks.REPEATER,Blocks.COMPARATOR,Blocks.OBSERVER,Blocks.NOTE_BLOCK,Blocks.CHEST,Blocks.OAK_LOG,Blocks.OAK_TRAPDOOR,Blocks.OAK_SIGN,Blocks.OAK_WALL_SIGN,Blocks.SKELETON_SKULL,Blocks.SKELETON_WALL_SKULL)){
            for(var state:block.getStateManager().getStates()){
                var wire=AccuratePlacement.encode(AccuratePlacement.Mode.V3,protocolPos,state,protocolHit);
                var decoded=AccuratePlacement.decode(AccuratePlacement.Mode.V3,state.getBlock().getDefaultState(),protocolPos,wire.getPos().x,net.minecraft.util.math.Direction.NORTH);
                for(var property:state.getProperties())if(property!=net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF&&List.of("facing","axis","half","hinge","shape","type","mode","delay","note","open","rotation").contains(property.getName()))check(decoded.get(property).equals(state.get(property)),"V3 property roundtrip "+state+" / "+property.getName());
            }
        }
        for(var state:Blocks.REPEATER.getStateManager().getStates()){
            var wire=AccuratePlacement.encode(AccuratePlacement.Mode.V2,protocolPos,state,protocolHit);var decoded=AccuratePlacement.decode(AccuratePlacement.Mode.V2,Blocks.REPEATER.getDefaultState(),protocolPos,wire.getPos().x,net.minecraft.util.math.Direction.NORTH);
            check(decoded.get(RepeaterBlock.DELAY).equals(state.get(RepeaterBlock.DELAY))&&decoded.get(RepeaterBlock.FACING)==state.get(RepeaterBlock.FACING),"V2 repeaters preserve orientation and delay");
        }
        var doubleWire=AccuratePlacement.encode(AccuratePlacement.Mode.V3,protocolPos,Blocks.OAK_SLAB.getDefaultState().with(SlabBlock.TYPE,SlabType.DOUBLE),protocolHit);
        check(AccuratePlacement.decode(AccuratePlacement.Mode.V3,Blocks.OAK_SLAB.getDefaultState(),protocolPos,doubleWire.getPos().x,net.minecraft.util.math.Direction.NORTH).get(SlabBlock.TYPE)==SlabType.BOTTOM,"Protocol never turns one slab item into a double slab");
        var dripstone=Blocks.POINTED_DRIPSTONE.getDefaultState().with(net.minecraft.state.property.Properties.VERTICAL_DIRECTION,net.minecraft.util.math.Direction.UP);
        check(AccuratePlacement.encode(AccuratePlacement.Mode.V3,protocolPos,dripstone,protocolHit).getPos().equals(protocolHit.getPos()),"Dripstone vertical direction is excluded from protocol");
        var endpointHit=new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.of(protocolPos).add(1,.5,.5),net.minecraft.util.math.Direction.EAST,protocolPos,false);
        var endpointWanted=Blocks.OBSERVER.getDefaultState().with(net.minecraft.state.property.Properties.FACING,net.minecraft.util.math.Direction.DOWN);
        var endpoint=AccuratePlacement.encode(AccuratePlacement.Mode.V3,protocolPos,endpointWanted,endpointHit);
        check(AccuratePlacement.decode(AccuratePlacement.Mode.V3,Blocks.OBSERVER.getDefaultState(),protocolPos,endpoint.getPos().x,net.minecraft.util.math.Direction.NORTH).get(net.minecraft.state.property.Properties.FACING)==net.minecraft.util.math.Direction.DOWN,"East boundary hit cannot carry into direction bits");
        var targetLabel=new OverlayLabel(10,100,"long target name");targetLabel.setY(20);check(!targetLabel.active&&targetLabel.hoveredAt(11,21)&&!targetLabel.mouseClicked(11,21,0)&&!targetLabel.hoveredAt(110,21),"Read-only target label has hover geometry without click behavior");
        var printer=new PrinterSettings();printer.fill=true;printer.perTick=17;printer.range=256;printer.skip.add("minecraft:glass");var preferences=printer.snapshot();preferences.addProperty("futureOption",true);
        check(PrinterRules.fluidMatches("minecraft:water",List.of("water"))&&PrinterRules.fluidMatches("minecraft:flowing_water",List.of("minecraft:water")),"Fluid filter accepts default namespace and flowing variant");
        check(!PrinterRules.fluidMatches("minecraft:lava",List.of("water")),"Water filter never cleans lava");
        var roundTrip=PrinterSettings.read(preferences);check(roundTrip.fill&&roundTrip.perTick==17&&roundTrip.skip.equals(printer.skip)&&roundTrip.snapshot().get("futureOption").getAsBoolean(),"Printer settings and unknown keys survive save/load");
        var zeroPrinter=PrinterSettings.read(preferences);zeroPrinter.interval=0;zeroPrinter.perTick=0;zeroPrinter.cooldown=0;zeroPrinter.validate();var zeroRestored=PrinterSettings.read(zeroPrinter.snapshot());check(zeroRestored.interval==0&&zeroRestored.perTick==0&&zeroRestored.cooldown==0,"Zero printer pacing settings survive save and load");
        check(zeroRestored.snapshot().equals(zeroPrinter.snapshot()),"Unchanged printer settings compare equal after a draft roundtrip");
        var legacyPrinter=preferences.deepCopy();legacyPrinter.remove("workBudgetMillis");check(PrinterSettings.read(legacyPrinter).workBudgetMillis==16,"Preferences without a saved budget use the measured 16ms default");zeroRestored.workBudgetMillis=8;check(PrinterSettings.read(zeroRestored.snapshot()).workBudgetMillis==8,"Saved 8ms work budget remains respected");zeroRestored.workBudgetMillis=16;check(PrinterSettings.read(zeroRestored.snapshot()).workBudgetMillis==16,"Printer work budget survives persistence");
        for(int invalidBudget:new int[]{0,17}){zeroRestored.workBudgetMillis=invalidBudget;boolean rejectedBudget=false;try{zeroRestored.validate();}catch(IllegalArgumentException e){rejectedBudget=e.getMessage().startsWith("工作预算");}check(rejectedBudget,"Unsafe printer work budgets are rejected by the matching UI field");}
        zeroPrinter.perTick=256;zeroPrinter.interval=20;zeroPrinter.cooldown=64;zeroPrinter.validate();check(PrinterSettings.read(zeroPrinter.snapshot()).perTick==256,"Maximum printer batch setting survives persistence without a hidden 32 action cap");
        check(PrinterEngine.checkedFill(printer).isOf(Blocks.STONE),"Fill uses an actual registry state");
        for(String invalid:new String[]{"minecraft:not_a_block","minecraft:stone[missing=abc]","minecraft:air"}){printer.fillState=invalid;boolean rejected=false;try{PrinterEngine.checkedFill(printer);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid fill never uses the renderer diagnostic block");}printer.fillState="minecraft:stone";
        check(InputBindings.moreSpecific("CTRL+CAPS_LOCK","CAPS_LOCK")&&!InputBindings.moreSpecific("M+B","CAPS_LOCK"),"Stop chord suppresses start chord only when it is a strict superset");
        check(InputBindings.normalize(" m + shift + ctrl ").equals("CTRL+SHIFT+M"),"Shortcut spacing, modifier order and case are canonical");
        check(InputBindings.code("F12")==org.lwjgl.glfw.GLFW.GLFW_KEY_F12&&InputBindings.code("RIGHT_CONTROL")==org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL&&InputBindings.code("MOUSE4")==-4,"Function, right modifier and mouse keys supported");
        check(InputBindings.conflict(java.util.Map.of("a","ctrl + m","b",""),"b","M+CTRL").equals("a"),"Equivalent shortcuts cannot silently trigger multiple actions");
        check(InputBindings.conflict(java.util.Map.of("a",""),"b","").isEmpty(),"Multiple disabled shortcuts do not conflict");
        check(InputBindings.conflict(java.util.Map.of("a","CTRL+P"),"b","LEFT_CONTROL+P").equals("a")&&InputBindings.normalize("RIGHT_SHIFT+F3").equals("SHIFT+F3"),"Both physical modifier keys use identical conflict and execution semantics");
        check(PrinterRules.coralSubstitute(Blocks.DEAD_TUBE_CORAL_WALL_FAN.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.WEST)).isOf(Blocks.TUBE_CORAL_WALL_FAN),"Dead wall coral maps to matching live variant");
        check(PrinterRules.coralSubstitute(Blocks.DEAD_TUBE_CORAL_WALL_FAN.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.WEST)).get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)==net.minecraft.util.math.Direction.WEST,"Coral substitution retains attachment direction");
        check(PrinterRules.coralSubstitute(Blocks.STONE.getDefaultState())==null,"Other blocks cannot be substituted as coral");
        var observer=Blocks.OBSERVER.getDefaultState().with(net.minecraft.state.property.Properties.FACING,net.minecraft.util.math.Direction.EAST);var origin=net.minecraft.util.math.BlockPos.ORIGIN;
        check(PrinterRules.observerReady(origin,p->p.getX()<3?observer:Blocks.STONE.getDefaultState(),p->p.getX()<3?observer:Blocks.STONE.getDefaultState()),"Completed observer input chain permits placement");
        check(!PrinterRules.observerReady(origin,p->observer,p->observer),"Unbounded observer chain is deferred at fixed work limit");
        check(!PrinterRules.observerReady(origin,p->observer,p->null),"Unknown world is never considered a completed observer input");
        check(PrinterRules.cycleDirection(Blocks.STONE.getDefaultState()).isOf(Blocks.STONE)&&!PrinterRules.cycleDirection(observer).equals(observer),"Fill direction applies only to actual state properties");
        check(SelectionCoordinates.decode("-12, 70, 400").equals(new Vec3i(-12,70,400)),"Coordinate clipboard permits external triples");
        check(SelectionCoordinates.decode(SelectionCoordinates.encode(new Vec3i(1,-64,3))).equals(new Vec3i(1,-64,3)),"Coordinate clipboard preserves negative heights");
        check(!PrinterRules.adjustable(Blocks.IRON_DOOR.getDefaultState(),Blocks.IRON_DOOR.getDefaultState().with(net.minecraft.state.property.Properties.OPEN,true),printer),"Iron door never scheduled for manual opening");
        check(!PrinterRules.adjustable(Blocks.IRON_TRAPDOOR.getDefaultState(),Blocks.IRON_TRAPDOOR.getDefaultState().with(net.minecraft.state.property.Properties.OPEN,true),printer),"Iron trapdoor never scheduled for manual opening");
        check(PrinterRules.adjustable(Blocks.OAK_DOOR.getDefaultState(),Blocks.OAK_DOOR.getDefaultState().with(net.minecraft.state.property.Properties.OPEN,true),printer),"Wood door remains adjustable");
        check(PrinterRules.placementMatches(Blocks.OAK_SLAB.getDefaultState(),Blocks.OAK_SLAB.getDefaultState().with(net.minecraft.state.property.Properties.SLAB_TYPE,SlabType.DOUBLE)),"Double slab may be completed in a second step");
        check(!PrinterRules.placementMatches(Blocks.OAK_STAIRS.getDefaultState(),Blocks.OAK_STAIRS.getDefaultState().with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.SOUTH)),"Incorrect stair direction is rejected");
        var base=new Vec3i(100,64,-200);var localAnchor=new Vec3i(1,2,3);
        for(int turn=0;turn<4;turn++)for(int mirror=0;mirror<4;mirror++)for(var direction:net.minecraft.util.math.Direction.values()){
            var transform=new PlacementTransform(new Vec3i(-20,90,40),turn,(mirror&1)!=0,(mirror&2)!=0);var entity=new net.minecraft.nbt.NbtCompound();
            entity.putInt("TileX",101);entity.putInt("TileY",66);entity.putInt("TileZ",-197);entity.putByte("Facing",(byte)direction.getId());
            if(direction.getAxis().isHorizontal())entity.putByte("facing",(byte)direction.getHorizontal());
            var pos=new net.minecraft.nbt.NbtList();pos.add(net.minecraft.nbt.NbtDouble.of(101.5));pos.add(net.minecraft.nbt.NbtDouble.of(66.5));pos.add(net.minecraft.nbt.NbtDouble.of(-196.5));entity.put("Pos",pos);
            EntityNbtTransform.relative(entity,base);check(entity.getInt("TileX")==1&&entity.getInt("TileY")==2&&entity.getInt("TileZ")==3,"Capture relativizes hanging anchor");
            EntityNbtTransform.placed(entity,Vec3i.ZERO,transform);var anchor=transform.apply(localAnchor);var vector=transform.apply(new Vec3i(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ())).subtract(transform.origin());var expectedDirection=net.minecraft.util.math.Direction.fromVector(vector.x(),vector.y(),vector.z());
            check(entity.getInt("TileX")==anchor.x()&&entity.getInt("TileY")==anchor.y()&&entity.getInt("TileZ")==anchor.z(),"Hanging anchor follows placement transform");
            check(entity.getByte("Facing")==expectedDirection.getId(),"Item-frame full direction transformed");
            if(direction.getAxis().isHorizontal())check(entity.getByte("facing")==expectedDirection.getHorizontal(),"Painting horizontal direction transformed independently");
            var transformed=entity.getList("Pos",6);check(Math.abs(transformed.getDouble(0)-(anchor.x()+0.5))<0.00001&&Math.abs(transformed.getDouble(2)-(anchor.z()+0.5))<0.00001,"Entity center remains attached to transformed anchor");
        }
        var fileMetadata=new BlueprintMetadata("counts",0,"0".repeat(64),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:stone"),BlockStateSpec.parse("minecraft:oak_slab[type=double]"),BlockStateSpec.parse("minecraft:oak_door[half=upper]"),BlockStateSpec.parse("missing:unknown")),List.of(new Region("large",Vec3i.ZERO,new Vec3i(1000,100,100))),List.of());
        var fileMaterials=new FileMaterials(fileMetadata,new long[]{0,9_999_970,10,10,10},new PlacementTransform(Vec3i.ZERO,0,false,false));
        for(int i=0;i<1000&&!fileMaterials.finished();i++)fileMaterials.tick();
        check(fileMaterials.finished(),"File materials complete with no world or renderer");
        check(fileMaterials.total(Items.STONE)==9_999_970,"Large histogram is not preview capped");
        check(fileMaterials.total(Items.OAK_SLAB)==20,"Double slab histogram converts to two items");
        check(fileMaterials.total(Items.OAK_DOOR)==0,"Door upper does not duplicate item cost");
        check(fileMaterials.unsupported()==10,"Unsupported source states remain explicit");
        for(int level=0;level<16;level++){
            var marker=LightProjectionMarker.stack(Blocks.LIGHT.getDefaultState().with(net.minecraft.state.property.Properties.LEVEL_15,level));
            check(marker.isOf(Items.LIGHT)&&marker.getSubNbt("BlockStateTag").getString("level").equals(Integer.toString(level)),"Light marker preserves brightness level");
        }
        check(AnalysisScreen.validMultiplierInput("25")&&AnalysisScreen.validMultiplierInput("2147483647"),"Material multiplier accepts positive integers beyond old cycle");
        check(AnalysisScreen.validMultiplierInput(""),"Multiplier allows temporary empty edit");
        for(String invalid:new String[]{"0","-1","1.5","abc","2147483648","999999999999"})check(!AnalysisScreen.validMultiplierInput(invalid),"Invalid multiplier rejected");
        var grid=new MaterialGrid(0,552,220);var materialRows=new java.util.ArrayList<PlacementAnalysis.Material>();
        for(int i=0;i<100;i++)materialRows.add(new PlacementAnalysis.Material(Items.STONE,i+1,i+1,0,0));
        grid.rows(materialRows,true);
        check(grid.mouseScrolled(10,10,-1)&&grid.scrollOffset()==36,"Material grid scrolls continuously");
        grid.rows(materialRows,false);check(grid.scrollOffset()==36,"Live refresh preserves material scroll");
        grid.mouseScrolled(10,10,-10000);check(grid.scrollOffset()==1220,"Material scroll clamps at final row");
        grid.rows(java.util.List.of(materialRows.get(0)),false);check(grid.scrollOffset()==0,"Filter shrink clamps scroll");
        var opened=new java.util.ArrayList<dev.betterlitematica.runtime.SessionIo.FileEntry>();
        var browser=new BrowserGrid(0,552,90,opened::add);
        var files=new java.util.ArrayList<dev.betterlitematica.runtime.SessionIo.FileEntry>();
        for(int i=0;i<30;i++)files.add(new dev.betterlitematica.runtime.SessionIo.FileEntry("file"+i,"file"+i,i==0));
        browser.entries(files,"");browser.mouseClicked(370,10,0);
        check(opened.size()==1&&opened.get(0).equals(files.get(0)),"Browser entire row opens its file");
        browser.mouseClicked(543,10,0);browser.mouseClicked(10,27,0);
        check(opened.size()==1,"Browser gaps do not open files");
        browser.mouseScrolled(10,10,-1);browser.mouseClicked(10,10,0);
        check(browser.scrollOffset()==30&&opened.get(1).equals(files.get(1)),"Browser scrolled hit matches visible row");
        browser.mouseScrolled(10,10,-1000);check(browser.scrollOffset()==810,"Browser clamps final row");
        int openedBefore=opened.size();browser.mouseClicked(548,20,0);browser.mouseReleased(548,20,0);
        check(opened.size()==openedBefore,"Scrollbar does not open files");
        browser.keyPressed(269,0,0);check(browser.scrollOffset()==810,"Browser End reaches final row");
        browser.entries(files.subList(0,1),"");check(browser.scrollOffset()==0,"Directory change resets scroll");
        check(BrowserGrid.marqueeOffset(56,0.4)==0,"Marquee pauses at start");
        check(Math.abs(BrowserGrid.marqueeOffset(56,1.8)-28)<0.001,"Marquee moves forward");
        check(BrowserGrid.marqueeOffset(56,3)==56,"Marquee pauses at end");
        check(Math.abs(BrowserGrid.marqueeOffset(56,4.6)-28)<0.001,"Marquee returns smoothly");
        check(BrowserGrid.marqueeOffset(0,100)==0,"Short names do not scroll");
        var clipSource=new dev.betterlitematica.core.Placement(java.util.UUID.randomUUID(),"source","a.litematic",new dev.betterlitematica.core.PlacementTransform(new dev.betterlitematica.core.Vec3i(-12,55,193),3,true,true),false,false,0.78f);
        var clipTarget=new dev.betterlitematica.core.Placement(java.util.UUID.randomUUID(),"target","b.litematic",new dev.betterlitematica.core.PlacementTransform(new dev.betterlitematica.core.Vec3i(0,0,0),0,false,false),true,false);
        var clipState=PlacementClipboard.decode(PlacementClipboard.encode(clipSource));var pasted=clipState.apply(clipTarget);
        check(pasted.transform().equals(clipSource.transform())&&!pasted.enabled()&&pasted.opacity()==0.78f,"Clipboard transfers transform visibility and opacity");
        check(pasted.id().equals(clipTarget.id())&&pasted.name().equals("target")&&pasted.source().equals("b.litematic"),"Clipboard preserves target identity and source");
        boolean lockedRejected=false;try{clipState.apply(clipTarget.locked(true));}catch(IllegalStateException e){lockedRejected=true;}check(lockedRejected,"Clipboard respects placement lock");
        for(String bad:new String[]{"hello",PlacementClipboard.encode(clipSource).replace("0.78","NaN"),PlacementClipboard.encode(clipSource).replace("true","yes")}){
            boolean rejected=false;try{PlacementClipboard.decode(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid clipboard rejected before mutation");
        }
        var regionA=new Region("A",Vec3i.ZERO,new Vec3i(2,2,2));var regionB=new Region("B",new Vec3i(8,0,0),new Vec3i(2,2,2));
        var sourceWithRegions=clipSource.region(regionA,new RegionPlacement(new Vec3i(9,9,9),1,true,false,false,false));
        var targetWithRegions=clipTarget.region(regionB,new RegionPlacement(new Vec3i(7,7,7),2,false,true,true,false));
        var mergedClip=PlacementClipboard.decode(PlacementClipboard.encode(sourceWithRegions,List.of(regionA))).matching(List.of(regionA,regionB)).apply(targetWithRegions);
        check(mergedClip.region(regionB).equals(targetWithRegions.region(regionB)),"Cross-file clipboard preserves unrelated target region settings");check(mergedClip.region(regionA).equals(sourceWithRegions.region(regionA)),"Matching region settings transfer");
        var resetClip=PlacementClipboard.decode(PlacementClipboard.encode(clipSource,List.of(regionA))).matching(List.of(regionA,regionB)).apply(mergedClip);
        check(resetClip.region(regionA).equals(RegionPlacement.original(regionA)),"Copied default region clears the matching override");check(resetClip.region(regionB).equals(targetWithRegions.region(regionB)),"Default reset does not affect other regions");
        var oldClip=PlacementClipboard.decode("BetterLitematica:placement:1;0;0;0;0;false;false;true;0.5").apply(targetWithRegions);check(oldClip.regions().equals(targetWithRegions.regions()),"Legacy clipboard leaves all region settings intact");
        var materials=List.of(new PlacementAnalysis.Material(Items.STONE,100,100,0,95),new PlacementAnalysis.Material(Items.DIRT,50,50,0,0));var materialSorted=new java.util.ArrayList<>(materials);materialSorted.sort(FileMaterials.comparator(FileMaterials.Sort.MISSING,true));check(materialSorted.get(0).item()==Items.DIRT,"Missing-material sort subtracts inventory, not just total");
        var texture=new net.minecraft.util.Identifier("minecraft","textures/entity/chest/normal.png");var captured=new ProjectionModels();var consumer=captured.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutout(texture));for(int i=0;i<4;i++)consumer.vertex(i,2,3).texture(.25f,.5f).color(255,128,64,255).next();check(captured.layers().containsKey(new ProjectionModels.Material(texture,false))&&captured.vertices()==4,"Actual render layer mixins preserve special-model texture and geometry");captured.clear();captured.getBuffer(net.minecraft.client.render.RenderLayer.getTextIntensity(texture));check(captured.layers().containsKey(new ProjectionModels.Material(texture,true)),"Intensity glyphs retain the correct texture channel");captured.clear();check(captured.vertices()==0,"Capture reset cannot retain a previous block's model");
        for(Block block:List.of(Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.RED_BED,Blocks.OAK_SIGN,Blocks.OAK_HANGING_SIGN,Blocks.WHITE_BANNER,Blocks.LECTERN,Blocks.ENDER_CHEST,Blocks.PLAYER_HEAD)){var state=block.getDefaultState();var entity=((BlockEntityProvider)block).createBlockEntity(new net.minecraft.util.math.BlockPos(-13,74,28),state);check(entity!=null&&entity.getCachedState().equals(state),"Special block creates its native entity without spawning into a world: "+block);}
        for(int turn=0;turn<4;turn++)for(int mirror=0;mirror<4;mirror++){
            var transform=new PlacementTransform(Vec3i.ZERO,turn,(mirror&1)!=0,(mirror&2)!=0);var tag=new net.minecraft.nbt.NbtCompound();tag.putString("id","minecraft:piston");tag.putInt("facing",net.minecraft.util.math.Direction.EAST.getId());tag.put("blockState",net.minecraft.nbt.NbtHelper.fromBlockState(Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING,net.minecraft.util.math.Direction.EAST)));
            BlockEntityNbtTransform.placed(tag,transform);var expected=transform.apply(new Vec3i(1,0,0));check(tag.getInt("facing")==net.minecraft.util.math.Direction.fromVector(expected.x(),expected.y(),expected.z()).getId(),"Piston NBT follows combined region orientation");var state=new StateResolver1201(List.of(BlockStateSpec.parse("oak_stairs[facing=east]")),transform).resolve(0);check(tag.getCompound("blockState").getCompound("Properties").getString("facing").equals(state.get(StairsBlock.FACING).asString()),"Pushed block state follows the same transform");
        }
        class TestCamera extends net.minecraft.client.render.Camera {void angle(float yaw,float pitch){setRotation(yaw,pitch);}}
        var firstCamera=new TestCamera();firstCamera.angle(20,-15);var nextCamera=new TestCamera();nextCamera.angle(145,30);
        for(int mode=1;mode<=4;mode++){var initial=ProjectionEntities.Billboard.rotation(mode,70,25,firstCamera);var billboard=new ProjectionEntities.Billboard(mode,70,25,new org.joml.Quaternionf(initial).invert());var point=new org.joml.Vector3f(.2f,.7f,1);var baked=new org.joml.Vector3f(point).rotate(initial);baked.rotate(billboard.delta(nextCamera));var expected=new org.joml.Vector3f(point).rotate(ProjectionEntities.Billboard.rotation(mode,70,25,nextCamera));check(baked.distance(expected)<.0001f,"Cached billboard updates camera orientation without rebuilding vertices");}
        var versions=new SourceVersions1201();
        var oldPath=versions.fix(dev.betterlitematica.io.SourceVersions.Kind.STATE,java.util.Map.of("Name","minecraft:grass_path"),2586);check(oldPath.get("Name").equals("minecraft:dirt_path"),"Native data fixer migrates renamed source blocks");
        var legacyStone=versions.legacy(1,0,null);check(legacyStone.get("Name").equals("minecraft:stone"),"Classic numeric stone mapping");var legacyGranite=versions.legacy(1,1,null);check(legacyGranite.get("Name").equals("minecraft:granite"),"Classic metadata maps to the correct modern block");
        check(versions.legacy(1,0,"minecraft:dirt").get("Name").equals("minecraft:dirt"),"File block name mapping overrides numerical IDs");check(versions.legacy(4000,14,"minecraft:wool").get("Name").equals("minecraft:red_wool"),"Named legacy metadata retains color");
        dev.betterlitematica.io.SourceVersions.install(versions);var legacyRoot=new java.util.LinkedHashMap<String,Object>();legacyRoot.put("Materials","Alpha");legacyRoot.put("Width",(short)2);legacyRoot.put("Height",(short)1);legacyRoot.put("Length",(short)1);legacyRoot.put("Blocks",new byte[]{1,1});legacyRoot.put("Data",new byte[]{0,1});legacyRoot.put("TileEntities",List.of());legacyRoot.put("Entities",List.of());
        var upgraded=dev.betterlitematica.io.SourceVersions.litematic(dev.betterlitematica.io.SchematicFormats.canonical(legacyRoot,Cancellation.NEVER),Cancellation.NEVER);check(upgraded.get("MinecraftDataVersion").equals(versions.version()),"Full legacy document is stamped only after migration");var tags=dev.betterlitematica.io.NbtReader.compound(dev.betterlitematica.io.NbtReader.compound(upgraded.get("Regions"),"Regions").get("main"),"main");check(((List<?>)tags.get("BlockStatePalette")).size()==2,"Legacy metadata variants keep distinct palette indices");
        legacyRoot.put("Blocks",new byte[]{1,2});legacyRoot.put("Data",new byte[]{0,0});legacyRoot.put("AddBlocks",new byte[]{0x12});legacyRoot.put("BlockIDs",java.util.Map.of("257","minecraft:stone","514","minecraft:dirt"));var added=dev.betterlitematica.io.SchematicFormats.canonical(legacyRoot,Cancellation.NEVER);var addedRegion=dev.betterlitematica.io.NbtReader.compound(dev.betterlitematica.io.NbtReader.compound(added.get("Regions"),"Regions").get("main"),"main");var addedPalette=(List<?>)addedRegion.get("BlockStatePalette");check(dev.betterlitematica.io.NbtReader.compound(addedPalette.get(0),"state").get("Name").equals("minecraft:stone")&&dev.betterlitematica.io.NbtReader.compound(addedPalette.get(1),"state").get("Name").equals("minecraft:dirt"),"AddBlocks high nibble is even cell and MCEdit BlockIDs are authoritative");
        for(int x:new int[]{-29999999,-1,0,29999998})for(var side:net.minecraft.util.math.Direction.values())for(var mode:List.of(AccuratePlacement.Mode.V2,AccuratePlacement.Mode.V3)){
            var destination=new net.minecraft.util.math.BlockPos(x,80,-100);var clicked=destination.offset(side.getOpposite());var hit=new net.minecraft.util.hit.BlockHitResult(EasyPlacementRules.hitPoint(clicked,side,.75),side,clicked,false);var wanted=Blocks.REPEATER.getDefaultState().with(RepeaterBlock.FACING,net.minecraft.util.math.Direction.WEST).with(RepeaterBlock.DELAY,4);
            var encoded=AccuratePlacement.encode(mode,destination,wanted,hit);var buf=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();try{buf.writeBlockHitResult(encoded);var wire=buf.readBlockHitResult();var decoded=AccuratePlacement.decode(mode,Blocks.REPEATER.getDefaultState(),destination,wire.getPos().x,net.minecraft.util.math.Direction.NORTH);check(decoded.get(RepeaterBlock.FACING)==net.minecraft.util.math.Direction.WEST&&decoded.get(RepeaterBlock.DELAY)==4,"Real wire float preserves support-relative protocol at world borders");}finally{buf.release();}
        }
        for(boolean modern:new boolean[]{false,true}){var payload=PrinterSupply.takePayload(26,35,modern);try{check(payload.readVarInt()==26&&payload.readVarInt()==35,"TakeItOut slot order matches selected protocol");check(modern?!payload.readBoolean()&&payload.readableBytes()==0:payload.readableBytes()==0,"TakeItOut wire versions never guess optional fields");}finally{payload.release();}}
        check(InventoryTransfers.playerSlot(0)==36&&InventoryTransfers.playerSlot(8)==44&&InventoryTransfers.playerSlot(9)==9&&InventoryTransfers.playerSlot(35)==35,"Inventory and handler slot numbering stay distinct");
        check(InventoryReceipts.completePlayerInventory((1L<<36)-1)&&!InventoryReceipts.completePlayerInventory(((1L<<36)-1)^(1L<<17)),"Container takeover requires a complete authoritative player inventory, not a partial slot receipt");
        check(!InventoryTransfers.confirmed(501,1,true,10,10,true),"A delayed inventory transaction stays unconfirmed beyond timeout");check(!InventoryTransfers.confirmed(501,1,true,10,11,false),"An unrelated server inventory update is not the expected transfer");check(InventoryTransfers.confirmed(502,1,true,10,11,true),"A delayed authoritative transfer can still complete without retransmitting SWAP");
        check(PrinterSupply.candidate(new net.minecraft.item.ItemStack(Items.SHULKER_BOX),Items.STONE,PrinterSupply.Source.AX_SHULKERS),"AxShulkers database-only box is inspected through its real container");check(!PrinterSupply.candidate(new net.minecraft.item.ItemStack(Items.SHULKER_BOX),Items.STONE,PrinterSupply.Source.QUICK_SHULKER),"QuickShulker does not open a known empty vanilla box");
        for(int targetX:new int[]{-29999999,0,29999998})for(int supportDelta:new int[]{-1,0,1})for(int packed:new int[]{0,126,4194302}){double wire=AccuratePlacement.encodedX(targetX,targetX+supportDelta,targetX+1,packed);double received=targetX+supportDelta+(double)(float)(wire-targetX-supportDelta);check((int)Math.floor(received-targetX)-2==packed,"Packet float rounding cannot carry into a different placement state");}
        var carpetData=new net.minecraft.nbt.NbtCompound();check(AccuratePlacement.carpetRule(carpetData)==null,"Carpet presence alone does not prove accurate placement support");var carpetRules=new net.minecraft.nbt.NbtCompound();var carpetRule=new net.minecraft.nbt.NbtCompound();carpetRule.putString("Rule","accurateBlockPlacement");carpetRule.putString("Manager","carpet-extra");carpetRule.putString("Value","false");carpetRules.put("accurateBlockPlacement2",carpetRule);carpetData.put("Rules",carpetRules);check(Boolean.FALSE.equals(AccuratePlacement.carpetRule(carpetData)),"Disabled Carpet Extra rule keeps ordinary placement");carpetRule.putString("Value","true");check(Boolean.TRUE.equals(AccuratePlacement.carpetRule(carpetData)),"Enabled rule under an extension manager advertises V2");
        var bag=new net.minecraft.item.ItemStack(Items.SHULKER_BOX);var bagNbt=new net.minecraft.nbt.NbtCompound();var bagItems=new net.minecraft.nbt.NbtList();var bagItem=new net.minecraft.item.ItemStack(Items.STONE,64).writeNbt(new net.minecraft.nbt.NbtCompound());bagItem.putByte("Slot",(byte)26);bagItems.add(bagItem);bagNbt.put("Items",bagItems);bag.setSubNbt("BlockEntityTag",bagNbt);check(PrinterSupply.inner(bag,Items.STONE)==26&&PrinterSupply.inner(bag,Items.DIRT)==-1,"Supply searches actual shulker contents and exact inner slots");
        var playerInventory=new net.minecraft.entity.player.PlayerInventory(null);var container=new net.minecraft.inventory.SimpleInventory(27);container.setStack(0,new net.minecraft.item.ItemStack(Items.STONE,32));var handler=net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(7,playerInventory,container);var packet=InventoryTransfers.click(handler,0,0,net.minecraft.screen.slot.SlotActionType.QUICK_MOVE);
        check(playerInventory.getStack(0).isEmpty()&&container.getStack(0).getCount()==32&&packet.getModifiedStacks().isEmpty(),"Supply packet leaves local inventory unmodified until server response");
        java.util.List<Integer> receipts=new java.util.ArrayList<>();handler.updateSyncHandler(new net.minecraft.screen.ScreenHandlerSyncHandler(){public void updateState(net.minecraft.screen.ScreenHandler h,net.minecraft.util.collection.DefaultedList<net.minecraft.item.ItemStack> items,net.minecraft.item.ItemStack cursor,int[] properties){}public void updateSlot(net.minecraft.screen.ScreenHandler h,int slot,net.minecraft.item.ItemStack stack){receipts.add(slot);}public void updateCursorStack(net.minecraft.screen.ScreenHandler h,net.minecraft.item.ItemStack stack){}public void updateProperty(net.minecraft.screen.ScreenHandler h,int property,int value){}});handler.disableSyncing();handler.quickMove(null,packet.getSlot());handler.setPreviousCursorStack(packet.getStack());handler.enableSyncing();handler.sendContentUpdates();
        check(container.getStack(0).isEmpty()&&java.util.stream.IntStream.range(0,36).map(i->playerInventory.getStack(i).isOf(Items.STONE)?playerInventory.getStack(i).getCount():0).sum()==32,"Native container executes exactly the requested transfer");check(receipts.contains(0)&&receipts.stream().anyMatch(i->i>=27),"Server native content sync confirms source and destination when no local changes were claimed");
        check(LayerScreen.first(LayerRange.ALL)==0&&LayerScreen.second(LayerRange.ALL)==0,"Unbounded layer sentinels never enter numeric fields");var aboveLayer=LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.ABOVE,100,0);check(LayerScreen.first(aboveLayer)==100&&LayerScreen.second(aboveLayer)==100,"Switching above to range starts from a valid finite interval");
        commandNbtChecks();commandExportChecks();minerCancellationChecks();
        var font=dev.betterlitematica.runtime.OutlineFont.system();
        System.out.println("UI FONT: "+font.family());
        check(font.supports("加载投影 材料清单 独立叠加层 Minecraft 1.20.1"),"System outline font covers actual Chinese labels");
        var small=font.raster("加载投影",20,0xffffffff);var large=font.raster("加载投影",32,0xffffffff);
        check(large.width()>small.width()&&large.height()>small.height(),"Actual new raster generated at each final pixel size");
        check(java.util.Arrays.stream(small.argb()).anyMatch(c->(c>>>24)>0&&(c>>>24)<255),"Grayscale antialias coverage exists");
        check(java.util.Arrays.stream(small.argb()).anyMatch(c->(c>>>24)==255),"Solid glyph strokes exist");
        check(font.hit("加载投影",20,font.width("加载",20))==2,"Caret hit uses outline advances");
        String clipped=font.trim("加载投影与材料清单",20,90,true);check(font.width(clipped,20)<=90&&clipped.endsWith("…"),"Measured ellipsis respects physical width");
        int start=font.startForCursor("文件路径/加载投影",10,20,80);check(start>0&&font.width("文件路径/加载投影".substring(start),20)<=80,"Long input cursor stays inside visible width");
        var field=new OverlayTextField(0,0,120,"名称");field.setMaxLength(64);field.setText("中文abc");field.setSelectionStart(2);field.setSelectionEnd(5);check(field.getSelectedText().equals("abc")&&field.selectionAnchor()==5,"Native edit model works with no game TextRenderer");field.write("投影");check(field.getText().equals("中文投影"),"Replace selection without native font measurement");
        var preview=new java.awt.image.BufferedImage(1080,360,java.awt.image.BufferedImage.TYPE_INT_ARGB);var graphics=preview.createGraphics();graphics.setColor(new java.awt.Color(0x111b28));graphics.fillRect(0,0,1080,360);
        int rowY=24;for(int size:new int[]{18,22,28}){var raster=font.raster("加载投影    材料清单    投影校验    设置与快捷键  ·  "+size+" px",size,0xffedf6ff);var line=new java.awt.image.BufferedImage(raster.width(),raster.height(),java.awt.image.BufferedImage.TYPE_INT_ARGB);line.setRGB(0,0,raster.width(),raster.height(),raster.argb(),0,raster.width());graphics.drawImage(line,24,rowY,null);rowY+=92;}graphics.dispose();
        var previewFile=java.nio.file.Path.of("build","independent-ui-font.png");java.nio.file.Files.createDirectories(previewFile.toAbsolutePath().getParent());javax.imageio.ImageIO.write(preview,"png",previewFile.toFile());
        System.out.println("ADAPTER RESULT: "+checks+" checks passed");
    }
    private static void commandNbtChecks()throws Exception{
        var source=new net.minecraft.block.entity.ChestBlockEntity(net.minecraft.util.math.BlockPos.ORIGIN,Blocks.CHEST.getDefaultState());
        for(int i=0;i<27;i++){var stack=new net.minecraft.item.ItemStack(Items.STONE,i+1);var data=new net.minecraft.nbt.NbtCompound();for(int k=0;k<5;k++)data.putString("key."+k,"x".repeat(60)+i);stack.setNbt(data);source.setStack(i,stack);}
        var nbt=source.createNbtWithId();nbt.remove("x");nbt.remove("y");nbt.remove("z");
        for(var rule:List.of(ReplaceRule.ALL,ReplaceRule.NONE))for(boolean accepted:new boolean[]{true,false}){
            var lines=CommandNbt.block(new Vec3i(1,64,-3),"minecraft:chest[facing=north,type=single,waterlogged=false]",nbt,rule,256,"bl:test");
            check(lines.size()>30&&lines.stream().allMatch(s->s.length()<=256),"Large inventory becomes bounded vanilla commands");
            check(lines.stream().filter(s->s.contains("data modify block")).count()==1,"Only a complete storage payload reaches the block entity");
            var store=new net.minecraft.nbt.NbtCompound();var chest=new net.minecraft.block.entity.ChestBlockEntity(net.minecraft.util.math.BlockPos.ORIGIN,Blocks.CHEST.getDefaultState());if(rule==ReplaceRule.NONE&&!accepted)chest.setStack(0,new net.minecraft.item.ItemStack(Items.DIAMOND,5));var original=chest.createNbtWithId();
            for(String line:lines){
                if(line.startsWith("data modify storage bl:test ")){var reader=new com.mojang.brigadier.StringReader(line.substring("data modify storage bl:test ".length()));var path=net.minecraft.command.argument.NbtPathArgumentType.nbtPath().parse(reader);reader.skipWhitespace();String action=reader.readUnquotedString();reader.skipWhitespace();check(reader.readUnquotedString().equals("value"),"Storage writes carry literal NBT");reader.skipWhitespace();var value=new net.minecraft.nbt.StringNbtReader(reader).parseElement();if(action.equals("set"))path.put(store,value);else if(action.equals("append"))path.insert(-1,store,List.of(value));else throw new AssertionError(action);}
                else if(line.contains("data modify block")&&(rule!=ReplaceRule.NONE||accepted)){var current=chest.createNbtWithId();var root=net.minecraft.command.argument.NbtPathArgumentType.nbtPath().parse(new com.mojang.brigadier.StringReader("{}"));for(var target:root.get(current))((net.minecraft.nbt.NbtCompound)target).copyFrom(store.getCompound("n"));chest.readNbt(current);}
                // Every command boundary uses the game's real block entity normalization.
                chest.readNbt(chest.createNbtWithId());
            }
            if(rule==ReplaceRule.NONE&&!accepted)check(chest.createNbtWithId().equals(original),"Failed keep cannot modify a pre-existing inventory");else for(int i=0;i<27;i++)check(net.minecraft.item.ItemStack.areEqual(chest.getStack(i),source.getStack(i)),"NBT and slot survive real ChestBlockEntity read/write: "+i);
        }
        var impossible=new net.minecraft.nbt.NbtCompound();impossible.putString("CustomName","x".repeat(1000));boolean rejected=false;try{CommandNbt.block(Vec3i.ZERO,"minecraft:chest",impossible,ReplaceRule.ALL,256,"bl:test");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"An indivisible long value fails before any world command is returned");
    }
    private static void minerCancellationChecks()throws Exception{
        class Driver extends ExternalMiner.Driver {final java.util.List<Object> jobs=new java.util.ArrayList<>();final Object added=new Object();int failures=2;boolean failBegin,failAdd,running;Driver(){super(new Object());}java.util.List<java.util.Collection<Object>> queues(){return List.of(jobs);}boolean extra(){return false;}void begin(){running=true;if(failBegin)throw new IllegalStateException("Expected begin fault");}Object add(net.minecraft.client.world.ClientWorld world,net.minecraft.util.math.BlockPos pos){jobs.add(added);if(failAdd)throw new IllegalStateException("Expected add fault");return added;}boolean matches(Object candidate,net.minecraft.client.world.ClientWorld world,net.minecraft.util.math.BlockPos pos){return candidate==added;}void release(Object task){if(failures-->0)throw new IllegalStateException("Expected cancellation fault");remove(task);running=false;}}
        var driver=new Driver();Object own=new String("same"),foreign=new String("same");driver.jobs.add(own);driver.jobs.add(foreign);var miner=new ExternalMiner(null,()->false);
        for(var entry:java.util.Map.of("driver",driver,"task",own).entrySet()){var field=ExternalMiner.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(miner,entry.getValue());}var owner=ExternalMiner.class.getDeclaredField("owner");owner.setAccessible(true);owner.set(null,miner);
        System.out.println("EXPECTED FAULT: optional miner cancellation fails twice to verify the execution barrier");
        try{miner.reset();check(miner.active()&&driver.jobs.contains(own),"Failed cancellation keeps its owner and exact task");check(!ExternalMiner.entering(driver.manager)&&miner.active(),"External tick cannot execute while cancellation is unresolved");check(ExternalMiner.entering(driver.manager)&&!miner.active(),"A later successful release clears the barrier");check(driver.jobs.size()==1&&driver.jobs.get(0)==foreign,"Cancelling one task preserves a distinct but equal foreign task");}finally{owner.set(null,null);}
        var afterAdd=new Driver();afterAdd.failures=0;afterAdd.failAdd=true;var lease=new ExternalMiner(null,()->false);var adapter=ExternalMiner.class.getDeclaredField("driver");adapter.setAccessible(true);adapter.set(lease,afterAdd);
        try{lease.acquire(null,net.minecraft.util.math.BlockPos.ORIGIN);throw new AssertionError("Expected acquisition fault");}catch(IllegalStateException expected){check(!lease.active()&&afterAdd.jobs.isEmpty()&&!afterAdd.running,"An external API that queues then throws still releases its exact task and running flag");}
        afterAdd.failures=2;
        try{lease.acquire(null,net.minecraft.util.math.BlockPos.ORIGIN);throw new AssertionError("Expected acquisition fault");}catch(IllegalStateException expected){check(lease.active()&&afterAdd.jobs.get(0)==afterAdd.added,"Failed release after partial add retains the claimed object");check(!ExternalMiner.entering(afterAdd.manager),"Partial acquisition retains a tick barrier until cleanup succeeds");check(ExternalMiner.entering(afterAdd.manager)&&afterAdd.jobs.isEmpty(),"Partial acquisition later cleans up without re-adding a task");}
        var afterBegin=new Driver();afterBegin.failBegin=true;afterBegin.failures=0;adapter.set(lease,afterBegin);try{lease.acquire(null,net.minecraft.util.math.BlockPos.ORIGIN);throw new AssertionError("Expected begin fault");}catch(IllegalStateException expected){check(!lease.active()&&!afterBegin.running,"Beginning failure before enqueue restores the owned running change");}
    }
    private static dev.betterlitematica.io.SchematicDocument commandDocument(String block,Vec3i size,java.util.Map<String,Object> nbt)throws Exception{
        var region=new Region("main",Vec3i.ZERO,size);var blocks=PackedBits.pack(2,new int[Math.toIntExact(region.volume())]);var palette=List.of(BlockStateSpec.parse(block));var index=SourceBlockIndex.build(blocks,size,new boolean[]{palette.get(0).isAir()},Cancellation.NEVER);
        var entities=List.<java.util.Map<String,Object>>of(java.util.Map.of("id","minecraft:armor_stand","Pos",List.of(.5,1d,.5),"Rotation",List.of(0f,0f)));
        return new dev.betterlitematica.io.SchematicDocument(3465,List.of(new dev.betterlitematica.io.SchematicDocument.Part(region,palette,blocks,nbt==null?java.util.Map.of():java.util.Map.of(0,nbt),entities,List.of(),List.of(),index,java.util.Set.of())));
    }
    private static void finish(CommandOperation task)throws Exception{long until=System.nanoTime()+15_000_000_000L;while(!task.result().isDone()&&System.nanoTime()<until){task.tick();Thread.sleep(2);}check(task.result().isDone(),"Offline command preparation and export terminate within the bounded check");}
    private static void commandExportChecks()throws Exception{
        var directory=java.nio.file.Files.createTempDirectory("betterlitematica-command-check-");var placement=new Placement(java.util.UUID.randomUUID(),"fixture","fixture.litematic",new PlacementTransform(new Vec3i(2,64,3),0,false,false),true,false);var settings=new CommandSettings();
        var good=directory.resolve("good.mcfunction");var task=new CommandOperation(null,commandDocument("stone",new Vec3i(32,2,16),null),placement,LayerRange.ALL,ReplaceRule.NON_AIR,true,true,good,settings);finish(task);task.result().join();var lines=java.nio.file.Files.readAllLines(good);check(lines.stream().anyMatch(s->s.startsWith("fill "))&&lines.stream().anyMatch(s->s.startsWith("summon minecraft:armor_stand ")),"Real export contains merged blocks and source entities");check(!task.cancel()&&java.nio.file.Files.exists(good),"Cancellation cannot report success after artifact commit");
        var bad=directory.resolve("bad.mcfunction");var invalid=new CommandOperation(null,commandDocument("missing:unknown",new Vec3i(1,1,1),null),placement,LayerRange.ALL,ReplaceRule.ALL,true,bad);finish(invalid);check(!invalid.result().isCompletedExceptionally()&&java.nio.file.Files.readAllLines(bad).isEmpty(),"All-unknown source completes with no world-writing commands");check(invalid.status().contains("跳过未知 1 格"),"All-unknown export reports the skipped cell rather than a failed document");
        unknownSourceChecks(directory,placement);
        var partial=directory.resolve("partial.mcfunction");var stopped=new CommandOperation(null,commandDocument("stone",new Vec3i(64,64,64),null),placement,LayerRange.ALL,ReplaceRule.ALL,true,partial);var generated=CommandOperation.class.getDeclaredField("generated");generated.setAccessible(true);long until=System.nanoTime()+10_000_000_000L;while(generated.getLong(stopped)==0&&!stopped.result().isDone()&&System.nanoTime()<until){stopped.tick();Thread.sleep(2);}check(generated.getLong(stopped)>0&&!stopped.result().isDone(),"Failure is injected after an actual output batch");stopped.fail(new IllegalStateException("Injected producer failure"));Thread.sleep(150);check(stopped.result().isCompletedExceptionally()&&!java.nio.file.Files.exists(partial),"Producer failure cannot publish a partial export");
        var background=new java.util.concurrent.atomic.AtomicBoolean();var raw=java.util.Map.<String,Object>of("id","minecraft:chest","bulk",new byte[3_800_000]);var checked=new java.util.AbstractMap<String,Object>(){public java.util.Set<java.util.Map.Entry<String,Object>> entrySet(){background.set(Thread.currentThread().getName().equals("betterlitematica-command-prepare"));return raw.entrySet();}};
        var huge=directory.resolve("huge.mcfunction");var heavy=new CommandOperation(null,commandDocument("chest",new Vec3i(1,1,1),checked),placement,LayerRange.ALL,ReplaceRule.ALL,true,huge);finish(heavy);Thread.sleep(150);check(background.get(),"Near-4MiB block entity conversion never runs on the UI tick thread");check(heavy.result().isCompletedExceptionally()&&!java.nio.file.Files.exists(huge),"Oversize command plan fails without a published artifact");
        var cancelled=directory.resolve("cancelled.mcfunction");var cancelledTask=new CommandOperation(null,commandDocument("stone",new Vec3i(1,1,1),null),placement,LayerRange.ALL,ReplaceRule.ALL,true,cancelled);check(cancelledTask.cancel(),"Cancellation wins before command generation");finish(cancelledTask);check(!java.nio.file.Files.exists(cancelled),"Cancelled generation publishes no file");
        Thread.sleep(150);try(var files=java.nio.file.Files.list(directory)){check(files.noneMatch(p->p.getFileName().toString().endsWith(".part")),"Finished, failed and cancelled writers release temporary files");}try(var files=java.nio.file.Files.list(directory)){for(var path:files.toList())java.nio.file.Files.delete(path);}java.nio.file.Files.delete(directory);
    }
    private static dev.betterlitematica.io.SchematicDocument.Part commandPart(String name,Vec3i origin,List<BlockStateSpec> palette,int[] values,java.util.Map<Integer,java.util.Map<String,Object>> nbt)throws Exception{
        var size=new Vec3i(values.length,1,1);var bits=PackedBits.pack(Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1)),values);boolean[] air=new boolean[palette.size()];for(int i=0;i<air.length;i++)air[i]=palette.get(i).isAir();
        return new dev.betterlitematica.io.SchematicDocument.Part(new Region(name,origin,size),palette,bits,nbt,List.of(),List.of(),List.of(),SourceBlockIndex.build(bits,size,air,Cancellation.NEVER),java.util.Set.of());
    }
    private static List<String> exported(CommandOperation task,java.nio.file.Path file)throws Exception{finish(task);task.result().join();return java.nio.file.Files.readAllLines(file);}
    private static void unknownSourceChecks(java.nio.file.Path directory,Placement placement)throws Exception{
        var stone=BlockStateSpec.parse("stone");var missing=BlockStateSpec.parse("absent_mod:unknown");var invalid=BlockStateSpec.parse("oak_stairs[facing=sideways]");
        var palette=List.of(stone,missing,invalid,BlockStateSpec.AIR,BlockStateSpec.parse("dirt"));
        var mixed=new dev.betterlitematica.io.SchematicDocument(3465,List.of(commandPart("mixed",Vec3i.ZERO,palette,new int[]{0,1,2,3,0,4},java.util.Map.of())));
        for(var rule:ReplaceRule.values()){
            var file=directory.resolve("mixed-"+rule+".mcfunction");var operation=new CommandOperation(null,mixed,placement,LayerRange.ALL,rule,true,file);var lines=exported(operation,file);
            check(lines.stream().anyMatch(s->s.startsWith("setblock 2 64 3 minecraft:stone"))&&lines.stream().anyMatch(s->s.startsWith("setblock 6 64 3 minecraft:stone"))&&lines.stream().anyMatch(s->s.startsWith("setblock 7 64 3 minecraft:dirt")),"Known cells on both sides of unknowns still export under "+rule);
            check(lines.stream().noneMatch(s->s.startsWith("fill ")||s.startsWith("setblock 3 64 3 ")||s.startsWith("setblock 4 64 3 ")||s.contains("magenta_stained_glass")||s.contains("absent_mod:")),"Unknown block/property has no write or diagnostic-marker command under "+rule);
            check(lines.stream().anyMatch(s->s.startsWith("setblock 5 64 3 minecraft:air"))==(rule==ReplaceRule.ALL),"Real air keeps the original replace-rule semantics under "+rule);
            check(operation.status().contains("跳过未知 2 格"),"Skip count measures actual unknown cells under "+rule);
        }
        var gap=new dev.betterlitematica.io.SchematicDocument(3465,List.of(commandPart("gap",Vec3i.ZERO,List.of(stone,missing),new int[]{0,1,0},java.util.Map.of())));
        var gapFile=directory.resolve("merge-gap.mcfunction");var gapLines=exported(new CommandOperation(null,gap,placement,LayerRange.ALL,ReplaceRule.ALL,true,gapFile),gapFile);
        check(gapLines.size()==2&&gapLines.stream().allMatch(s->s.startsWith("setblock "))&&gapLines.stream().noneMatch(s->s.startsWith("setblock 3 64 3 ")),"Same-state fill batching never bridges an unknown-cell hole");
        var unused=new dev.betterlitematica.io.SchematicDocument(3465,List.of(commandPart("unused",Vec3i.ZERO,List.of(stone,missing,invalid),new int[]{0},java.util.Map.of())));
        var unusedFile=directory.resolve("unused.mcfunction");var unusedTask=new CommandOperation(null,unused,placement,LayerRange.ALL,ReplaceRule.ALL,true,unusedFile);var unusedLines=exported(unusedTask,unusedFile);
        check(unusedLines.size()==1&&unusedLines.get(0).startsWith("setblock 2 64 3 minecraft:stone"),"Unused unknown palette entries never block a valid source");check(!unusedTask.status().contains("跳过未知"),"Unused palette IDs are not counted as skipped source positions");
        var piston=java.util.Map.<String,Object>of("id","minecraft:piston","facing",5,"blockState",java.util.Map.of("Name","absent_mod:pushed_block"));
        var pistonDocument=new dev.betterlitematica.io.SchematicDocument(3465,List.of(commandPart("piston",Vec3i.ZERO,List.of(BlockStateSpec.parse("moving_piston"),stone),new int[]{0,1},java.util.Map.of(0,piston))));
        var pistonFile=directory.resolve("piston.mcfunction");var pistonTask=new CommandOperation(null,pistonDocument,placement,LayerRange.ALL,ReplaceRule.ALL,true,pistonFile);var pistonLines=exported(pistonTask,pistonFile);
        check(pistonLines.size()==1&&pistonLines.get(0).startsWith("setblock 3 64 3 minecraft:stone"),"Unknown pushed state skips its whole piston cell while later cells continue");check(pistonTask.status().contains("跳过未知 1 格"),"Nested unknown block state contributes one skipped cell");
        var noNbtFile=directory.resolve("piston-no-nbt.mcfunction");var noNbt=exported(new CommandOperation(null,pistonDocument,placement,LayerRange.ALL,ReplaceRule.ALL,false,noNbtFile),noNbtFile);check(noNbt.stream().anyMatch(s->s.startsWith("setblock 2 64 3 minecraft:moving_piston")),"NBT-disabled paste does not reject unrelated ignored piston payload");
        var overlap=new dev.betterlitematica.io.SchematicDocument(3465,List.of(commandPart("lower",Vec3i.ZERO,List.of(stone),new int[]{0},java.util.Map.of()),commandPart("upper",Vec3i.ZERO,List.of(missing,stone),new int[]{0,1},java.util.Map.of())));
        var overlapFile=directory.resolve("unknown-owner.mcfunction");var overlappingTask=new CommandOperation(null,overlap,placement.overlap(ReplaceRule.ALL),LayerRange.ALL,ReplaceRule.ALL,true,overlapFile);var overlapLines=exported(overlappingTask,overlapFile);
        check(overlapLines.size()==1&&overlapLines.get(0).startsWith("setblock 3 64 3 minecraft:stone"),"Unknown winning subregion preserves target instead of exposing the lower known block");check(overlappingTask.status().contains("跳过未知 1 格"),"Overlapped unknown owner counted only once");
        for(var spec:List.of(missing,invalid)){try{StateResolver1201.checked(spec);throw new AssertionError("Invalid explicit block input accepted");}catch(IllegalArgumentException expected){check(true,"Explicit invalid input remains strict: "+spec);}}
        var raw=(net.minecraft.nbt.NbtCompound)NbtBridge.game(piston);try{BlockEntityNbtTransform.placed(raw,placement.transform());throw new AssertionError("Unknown piston state accepted");}catch(IllegalArgumentException expected){check(expected.getClass().getSimpleName().equals("UnknownSourceState"),"Unknown piston state is distinguishable from malformed data and other failures");}
        var unknownEntity=java.util.Map.<String,Object>of("id","absent_mod:creature","Pos",List.of(.5,1d,.5));
        var unknownPassenger=java.util.Map.<String,Object>of("id","minecraft:pig","Pos",List.of(.5,1d,.5),"Passengers",List.of(unknownEntity));
        var knownEntity=java.util.Map.<String,Object>of("id","minecraft:armor_stand","Pos",List.of(.5,1d,.5));
        var entitiesFile=directory.resolve("mixed-entities.mcfunction");var entityTask=new CommandOperation(null,withEntities(unused,List.of(unknownEntity,unknownPassenger,knownEntity)),placement,LayerRange.ALL,ReplaceRule.ALL,true,true,entitiesFile,new CommandSettings());var entityLines=exported(entityTask,entitiesFile);
        check(entityLines.stream().filter(s->s.startsWith("summon ")).count()==1&&entityLines.stream().anyMatch(s->s.startsWith("summon minecraft:armor_stand ")),"Unknown entity and unknown passenger groups do not block later valid entities");
        check(entityLines.stream().noneMatch(s->s.contains("absent_mod:")||s.startsWith("summon minecraft:pig ")),"Partially unsupported passenger group is skipped as a whole");
        check(entityTask.status().contains("跳过未知实体 2 组")&&entityLines.stream().anyMatch(s->s.startsWith("setblock ")),"Skipped entity-group count is separate from successfully exported blocks");
        int badEntity=0;for(var malformed:List.of(java.util.Map.<String,Object>of("id","bad id!","Pos",List.of(.5,1d,.5)),java.util.Map.<String,Object>of("id","absent_mod:creature","Pos",List.of(Double.NaN,1d,.5)))){
            var file=directory.resolve("malformed-entity-"+(badEntity++)+".mcfunction");var operation=new CommandOperation(null,withEntities(unused,List.of(malformed)),placement,LayerRange.ALL,ReplaceRule.ALL,true,true,file,new CommandSettings());finish(operation);
            check(operation.result().isCompletedExceptionally()&&!java.nio.file.Files.exists(file),"Malformed entity ID/coordinates still fail preflight rather than being silently skipped");
        }
    }
    private static dev.betterlitematica.io.SchematicDocument withEntities(dev.betterlitematica.io.SchematicDocument original,List<java.util.Map<String,Object>> entities){var p=original.parts().get(0);return new dev.betterlitematica.io.SchematicDocument(original.dataVersion(),List.of(new dev.betterlitematica.io.SchematicDocument.Part(p.region(),p.palette(),p.blocks(),p.blockEntities(),entities,p.blockTicks(),p.fluidTicks(),p.nonAir(),p.tickCells())));}
}
