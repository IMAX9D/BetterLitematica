package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

/** Registry and transient admission checks; does not create a client, server or world. */
public final class PrinterThroughputChecks {
    private PrinterThroughputChecks(){}
    private static final class CodecProperties {static final Set<Property<?>> V3=Set.of(Properties.INVERTED,Properties.OPEN,Properties.PERSISTENT,
        Properties.AXIS,Properties.BLOCK_HALF,Properties.CHEST_TYPE,Properties.COMPARATOR_MODE,Properties.DOOR_HINGE,
        Properties.SLAB_TYPE,Properties.STAIR_SHAPE,Properties.WALL_MOUNT_LOCATION,Properties.BITES,Properties.DELAY,Properties.NOTE,Properties.ROTATION);}
    private static final class Checks {int count;void require(boolean value,String message){count++;if(!value)throw new AssertionError(message);}}
    public static int run(){
        var checks=new Checks();var visited=new HashSet<Property<?>>();
        creativeInventory(checks);
        exactCreativeInventory(checks);
        var wantedLight=net.minecraft.block.Blocks.LIGHT.getDefaultState().with(net.minecraft.block.LightBlock.LEVEL_15,10);
        var lightStack=net.minecraft.block.LightBlock.addNbtForLevel(new net.minecraft.item.ItemStack(net.minecraft.item.Items.LIGHT),10);
        checks.require(StateResolver1201.itemState(net.minecraft.block.Blocks.LIGHT.getDefaultState(),lightStack).equals(wantedLight),"Vanilla light item tag produces requested level after placement");
        var otherLight=net.minecraft.block.Blocks.LIGHT.getDefaultState().with(net.minecraft.block.LightBlock.LEVEL_15,15);
        checks.require(PrinterActions.materialKey(wantedLight)==PrinterActions.heldMaterial(lightStack),"Held light level joins its matching material batch");
        checks.require(PrinterActions.materialKey(wantedLight)!=PrinterActions.materialKey(otherLight)&&PrinterActions.materialKey(otherLight)==PrinterActions.heldMaterial(new net.minecraft.item.ItemStack(net.minecraft.item.Items.LIGHT)),"Different light levels never share a hand-switch batch");
        var grouped=new dev.betterlitematica.core.PrinterQueue(4);
        var light15Job=new dev.betterlitematica.core.PrinterQueue.Job(1,1,15,0,dev.betterlitematica.core.PrinterQueue.Kind.PLACE);
        var light10Job=new dev.betterlitematica.core.PrinterQueue.Job(1,2,10,0,dev.betterlitematica.core.PrinterQueue.Kind.PLACE);
        grouped.offer(light15Job,PrinterActions.materialKey(otherLight));grouped.offer(light10Job,PrinterActions.materialKey(wantedLight));
        checks.require(grouped.pollBatch(PrinterActions.heldMaterial(lightStack))==light10Job&&grouped.pollBatch(PrinterActions.heldMaterial(lightStack))==light15Job,"Held light level is consumed first without interleaved hand changes");
        reachableFaceGeometry(checks);
        var target=new BlockPos(-73,21,105);var clicked=target.west();
        var hit=new BlockHitResult(new Vec3d(target.getX()+.25,target.getY()+.75,target.getZ()+.5),Direction.EAST,clicked,false);
        for(var block:Registries.BLOCK){
            for(var property:block.getStateManager().getProperties())if(visited.add(property)){
                var table=AccuratePlacement.propertyValues(property);var uncached=ordered(property);
                checks.require(table.values().equals(uncached),"Cached natural property order: "+property);
                checks.require(table.bits()==32-Integer.numberOfLeadingZeros(uncached.size()-1),"Cached property bit width: "+property);
                checks.require(table==AccuratePlacement.propertyValues(property),"Property table reused: "+property);
                boolean immutable=false;try{table.values().clear();}catch(UnsupportedOperationException expected){immutable=true;}
                checks.require(immutable,"Cached values immutable: "+property);
            }
            for(var state:block.getStateManager().getStates()){
                int packed=uncachedPayload(state);var wire=AccuratePlacement.encode(AccuratePlacement.Mode.V3,target,state,hit);
                double expectedX=packed<0?hit.getPos().x:AccuratePlacement.encodedX(target.getX(),clicked.getX(),hit.getPos().x,packed);
                checks.require(Double.doubleToLongBits(wire.getPos().x)==Double.doubleToLongBits(expectedX)&&wire.getPos().y==hit.getPos().y&&wire.getPos().z==hit.getPos().z,"Cached encoder preserves uncached wire payload: "+state);
                double transportedX=clicked.getX()+(double)(float)(wire.getPos().x-clicked.getX());
                var decoded=AccuratePlacement.decode(AccuratePlacement.Mode.V3,block.getDefaultState(),target,transportedX,Direction.NORTH);
                checks.require(matchesEncodedProperties(state,decoded),"Cached decoder preserves transported properties: "+state);
            }
        }
        var slots=new AccuratePlacement.PendingSlots<Integer,Object>();var first=new Object();
        checks.require(slots.offer(0,first,100,0),"First local scope admitted");
        for(int i=1;i<128;i++)checks.require(slots.offer(i,new Object(),100,0),"Local scope admitted below exact cap");
        checks.require(!slots.available(99)&&!slots.offer(128,new Object(),100,99),"128 scopes apply backpressure without eviction");
        checks.require(slots.get(0,99)==first,"Backpressure preserves existing scope");
        checks.require(!slots.remove(0,new Object())&&slots.get(0,99)==first,"Foreign completion cannot remove owned scope");
        var encoded=AccuratePlacement.encode(AccuratePlacement.Mode.V3,target,net.minecraft.block.Blocks.OAK_LOG.getDefaultState(),hit);
        checks.require(!AccuratePlacement.localCapacity(true,AccuratePlacement.Mode.V3,target,encoded,slots,99),"Local V3 payload waits at capacity");
        checks.require(!AccuratePlacement.localCapacity(true,AccuratePlacement.Mode.V2,target,encoded,slots,99),"Local V2 payload waits at capacity");
        checks.require(AccuratePlacement.localCapacity(false,AccuratePlacement.Mode.V3,target,encoded,slots,99),"Remote protocol unaffected by integrated scope capacity");
        for(var mode:List.of(AccuratePlacement.Mode.NONE,AccuratePlacement.Mode.SLAB,AccuratePlacement.Mode.AUTO))checks.require(AccuratePlacement.localCapacity(true,mode,target,encoded,slots,99),"Unencoded mode does not consume local slots: "+mode);
        checks.require(AccuratePlacement.localCapacity(true,AccuratePlacement.Mode.V3,target,hit,slots,99),"V3 without encoded properties does not require a scope");
        checks.require(slots.available(100)&&slots.get(0,100)==null,"Expiry frees scopes at the exact deadline");
        var second=new Object();checks.require(slots.offer(0,second,200,100),"Admission resumes after expiry");
        checks.require(!slots.remove(0,first)&&slots.get(0,100)==second,"Late earlier completion preserves replacement scope");
        checks.require(slots.remove(0,second)&&slots.available(100),"Authoritative completion releases scope");
        checks.require(!slots.offer(0,first,100,100),"Already expired scope is never admitted");
        slots.offer(1,first,200,100);slots.clear();checks.require(slots.get(1,100)==null&&slots.available(100),"Disconnect clears scopes and admission deadline");
        return checks.count;
    }
    private static void reachableFaceGeometry(Checks checks){
        for(var shape:dev.betterlitematica.core.PrinterRange.Shape.values()){
            checks.require(!PrinterReach.intersectsBuildHeight(new Vec3d(0,410,0),5,shape,-64,320),"Out-of-height idle reason covers the reported Y410 case: "+shape);
            checks.require(!PrinterReach.intersectsBuildHeight(new Vec3d(0,-80,0),5,shape,-64,320),"Below-world reach reports the same limitation: "+shape);
            checks.require(PrinterReach.intersectsBuildHeight(new Vec3d(0,320,0),5,shape,-64,320),"Standing above the limit can still reach the top legal blocks: "+shape);
            checks.require(PrinterReach.intersectsBuildHeight(new Vec3d(0,410,0),5,shape,-64,512),"Custom dimension height is respected: "+shape);
        }
        checks.require(PrinterReach.intersectsBuildHeight(new Vec3d(0,325,0),5,dev.betterlitematica.core.PrinterRange.Shape.SPHERE,-64,320),"Topmost block surface exactly at reach remains eligible");
        checks.require(!PrinterReach.intersectsBuildHeight(new Vec3d(0,325.001,0),5,dev.betterlitematica.core.PrinterRange.Shape.SPHERE,-64,320),"Beyond the last reachable surface reports height limitation");
        var eye=new Vec3d(498.0010838,176.62,567.9090834);
        var below=new BlockPos(496,171,566);
        var oldBelow=EasyPlacementRules.hitPoint(below,Direction.UP,.25);
        var closeBelow=EasyPlacementRules.nearestHitPoint(below,Direction.UP,eye);
        checks.require(eye.squaredDistanceTo(oldBelow)>25&&eye.squaredDistanceTo(closeBelow)<25,"Previously missed upper face is reachable at its near edge");
        var scanCenter=new dev.betterlitematica.core.Vec3i((int)Math.round(eye.x),(int)Math.round(eye.y),(int)Math.round(eye.z));
        var scanPos=new dev.betterlitematica.core.Vec3i(below.getX(),below.getY(),below.getZ());
        checks.require(PrinterReach.distanceSquared(eye,below)<25&&!dev.betterlitematica.core.PrinterRange.contains(scanPos,scanCenter,5,dev.betterlitematica.core.PrinterRange.Shape.SPHERE)&&dev.betterlitematica.core.PrinterRange.contains(scanPos,scanCenter,PrinterReach.candidateRadius(5),dev.betterlitematica.core.PrinterRange.Shape.SPHERE),"Real-reach block survives conservative scan despite rounded-center distance");
        checks.require(PrinterReach.distanceSquared(eye,new BlockPos(501,175,560))>25,"Real-reach filter excludes truly distant candidates");
        var ahead=new BlockPos(501,175,563);
        var oldAhead=EasyPlacementRules.hitPoint(ahead,Direction.SOUTH,.25);
        var closeAhead=EasyPlacementRules.nearestHitPoint(ahead,Direction.SOUTH,eye);
        checks.require(eye.squaredDistanceTo(oldAhead)>25&&eye.squaredDistanceTo(closeAhead)<25,"Previously missed side face is reachable at its near edge");
        for(var side:Direction.values()){
            var point=EasyPlacementRules.nearestHitPoint(ahead,side,eye);
            checks.require(point.x>=ahead.getX()&&point.x<=ahead.getX()+1&&point.y>=ahead.getY()&&point.y<=ahead.getY()+1&&point.z>=ahead.getZ()&&point.z<=ahead.getZ()+1,"Nearest point stays inside clicked face: "+side);
            checks.require(eye.squaredDistanceTo(point)<=eye.squaredDistanceTo(EasyPlacementRules.hitPoint(ahead,side,.25)),"Nearest face does not lose interaction reach: "+side);
        }
    }
    private static void creativeInventory(Checks checks){
        var inventory=new net.minecraft.entity.player.PlayerInventory(null);
        var source=new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE,37);
        var nested=new net.minecraft.nbt.NbtCompound();nested.putString("facing","east");source.getOrCreateNbt().put("BlockStateTag",nested);
        source.setCustomName(net.minecraft.text.Text.literal("source material"));inventory.setStack(12,source);
        var original=source.copy();var previous=new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT,9);inventory.setStack(2,previous);inventory.selectedSlot=0;
        var steps=new ArrayList<String>();var echoes=new InventoryTransfers.CreativeEchoes();
        InventoryTransfers.creativePick(inventory,inventory.getStack(12),2,stack->{
            checks.require(stack!=source&&net.minecraft.item.ItemStack.areEqual(stack,original),"Creative bag pick copies count, name and nested NBT");
            checks.require(inventory.getStack(2)==stack&&inventory.selectedSlot==0,"Copied stack is installed before ordinary creative packet and slot sync");
            checks.require(inventory.getStack(12)==source&&net.minecraft.item.ItemStack.areEqual(source,original),"Creative pick never swaps or edits the source bag slot");
            echoes.sent(2,stack);steps.add("creative");
        },()->{checks.require(inventory.selectedSlot==2,"Slot selection follows creative packet");steps.add("slot");});
        checks.require(steps.equals(List.of("creative","slot")),"Full-stack creative pick completes synchronously in packet order");
        checks.require(!echoes.reassert(2,inventory.getMainHandStack()),"Current full copied stack agrees with the latest creative intent");
        inventory.getMainHandStack().setCount(4);inventory.getMainHandStack().getOrCreateNbt().getCompound("BlockStateTag").putString("facing","west");
        checks.require(net.minecraft.item.ItemStack.areEqual(source,original),"Editing copied count and nested NBT cannot mutate source material");
        checks.require(echoes.reassert(2,inventory.getMainHandStack()),"Saved creative intent owns an independent full-stack copy");
        var latest=new net.minecraft.item.ItemStack(net.minecraft.item.Items.GLASS,13);echoes.sent(2,latest);
        inventory.setStack(2,original.copy());checks.require(echoes.reassert(2,inventory.getStack(2)),"Delayed copied-material receipt requires reassertion after a newer material");
        echoes.sent(2,inventory.getStack(2));checks.require(!echoes.reassert(2,original)&&echoes.reassert(2,latest),"Reasserted copied stack protects against a later stale receipt including ABA");
        var failedDestination=inventory.getStack(2);inventory.selectedSlot=1;boolean failed=false;
        try{InventoryTransfers.creativePick(inventory,source,2,stack->{stack.setCount(1);stack.getOrCreateNbt().putString("failed","mutation");throw new IllegalStateException("Injected send failure");},()->{throw new AssertionError("Failed submission cannot synchronize selection");});}
        catch(IllegalStateException expected){failed=true;}
        checks.require(failed&&inventory.getStack(2)==failedDestination&&inventory.selectedSlot==1,"Failed full-stack creative packet restores prior destination and selection");
        checks.require(inventory.getStack(12)==source&&net.minecraft.item.ItemStack.areEqual(source,original),"Failed submission preserves the original bag stack and tags");
        InventoryTransfers.creativePick(inventory,net.minecraft.item.ItemStack.EMPTY,3,stack->checks.require(stack.isEmpty(),"Creative empty-hand pick sends an empty stack"),()->{});
        checks.require(inventory.selectedSlot==3&&inventory.getMainHandStack().isEmpty(),"Empty source bag slot supports creative empty-hand adjustments");
        checks.require(!InventoryTransfers.confirmed(20,20,true,2,3,true),"Creative optimization does not settle a real swap in its sending tick");
        checks.require(!InventoryTransfers.confirmed(21,20,true,2,2,true)&&InventoryTransfers.confirmed(21,20,true,2,3,true),"Real swap still requires later matching server receipt");
    }
    private static void exactCreativeInventory(Checks checks){
        var wanted=new net.minecraft.item.ItemStack(net.minecraft.item.Items.LIGHT,1);
        wanted.getOrCreateSubNbt("BlockStateTag").putString("level","10");
        var level15=wanted.copy();level15.getOrCreateSubNbt("BlockStateTag").putString("level","15");
        var full=wanted.copy();full.setCount(37);
        checks.require(InventoryTransfers.matchesStack(full,wanted),"Exact printer material ignores count when item and full NBT match");
        checks.require(!InventoryTransfers.matchesStack(level15,wanted),"Level 15 light cannot satisfy requested level 10");
        checks.require(!InventoryTransfers.matchesStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.LIGHT),wanted),"Untagged light cannot satisfy requested level 10");
        var other=new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE);other.setNbt(wanted.getNbt().copy());
        checks.require(!InventoryTransfers.matchesStack(other,wanted),"Matching NBT on another item is not the requested material");
        var named=wanted.copy();named.setCustomName(net.minecraft.text.Text.literal("different"));
        checks.require(!InventoryTransfers.matchesStack(named,wanted),"Exact material matching includes tags beyond block state");
        checks.require(!InventoryTransfers.matchesStack(net.minecraft.item.ItemStack.EMPTY,wanted)&&InventoryTransfers.matchesStack(net.minecraft.item.ItemStack.EMPTY,net.minecraft.item.ItemStack.EMPTY),"Empty stack matching never supplies a nonempty requested item");
        checks.require(InventoryTransfers.canCreate(true,true,null,wanted),"Integrated creative may create the exact requested stack");
        checks.require(!InventoryTransfers.canCreate(true,false,null,wanted)&&!InventoryTransfers.canCreate(false,true,null,wanted)&&!InventoryTransfers.canCreate(false,false,null,wanted),"Exact tagged stacks are not synthesized remotely or without creative permission");
        checks.require(!InventoryTransfers.canCreate(true,true,null,net.minecraft.item.ItemStack.EMPTY),"Missing empty-hand slot is not synthesized");
        checks.require(InventoryTransfers.canCreate(true,false,net.minecraft.item.Items.STONE,null)&&!InventoryTransfers.canCreate(false,false,net.minecraft.item.Items.STONE,null)&&!InventoryTransfers.canCreate(true,true,net.minecraft.item.Items.AIR,null),"Legacy Item-only creative and survival admission is unchanged");
        var inventory=new net.minecraft.entity.player.PlayerInventory(null);inventory.setStack(0,level15);inventory.setStack(1,new net.minecraft.item.ItemStack(net.minecraft.item.Items.LIGHT));inventory.setStack(12,full);
        var original=full.copy();inventory.selectedSlot=0;
        checks.require(InventoryTransfers.find(inventory,stack->InventoryTransfers.matchesStack(stack,wanted))==12,"Existing exact material is found in the bag instead of wrong-level hotbar light");
        int destination=InventoryPolicy.printerDestination(inventory,1<<2,null);
        checks.require(destination==3,"Exact material creation obeys protected hotbar slots");
        var echoes=new InventoryTransfers.CreativeEchoes();
        InventoryTransfers.creativePick(inventory,full,destination,stack->echoes.sent(destination,stack),()->{});
        checks.require(inventory.getStack(12)==full&&net.minecraft.item.ItemStack.areEqual(full,original)&&inventory.getStack(destination)!=full&&net.minecraft.item.ItemStack.areEqual(inventory.getStack(destination),original),"Level-specific bag pick preserves full source stack and copied count and NBT");
        inventory.getStack(destination).getOrCreateSubNbt("BlockStateTag").putString("level","15");
        checks.require(net.minecraft.item.ItemStack.areEqual(full,original)&&echoes.reassert(destination,inventory.getStack(destination)),"Level-tag edits cannot mutate source or latest creative intent");
        echoes.sent(destination,level15);checks.require(echoes.reassert(destination,wanted),"Stale level 10 receipt after sending level 15 requires creative reassertion");
        echoes.sent(destination,wanted);checks.require(!echoes.reassert(destination,wanted)&&echoes.reassert(destination,level15),"Level 10 reassertion protects against later stale level 15 receipt");
        var before=inventory.getStack(destination);int selected=inventory.selectedSlot;boolean failed=false;
        try{InventoryTransfers.creativePick(inventory,wanted,destination,stack->{stack.getOrCreateSubNbt("BlockStateTag").putString("level","0");throw new IllegalStateException("Injected exact-stack packet failure");},()->{throw new AssertionError("Failed exact-stack send cannot sync slot");});}catch(IllegalStateException expected){failed=true;}
        checks.require(failed&&inventory.getStack(destination)==before&&inventory.selectedSlot==selected&&wanted.getSubNbt("BlockStateTag").getString("level").equals("10"),"Exact-stack send failure restores destination without mutating requested NBT");
        boolean protectedAll=false;try{InventoryPolicy.printerDestination(inventory,511,null);}catch(IllegalStateException expected){protectedAll=true;}
        checks.require(protectedAll,"Exact creative material cannot replace a fully protected hotbar");
        checks.require(!InventoryTransfers.confirmed(4,4,true,2,3,true)&&!InventoryTransfers.confirmed(5,4,true,2,2,true),"Tagged material matching cannot acknowledge an in-flight real swap early");
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static List<Comparable> ordered(Property<?> property){var values=new ArrayList<Comparable>((Collection)property.getValues());values.sort(Comparator.naturalOrder());return values;}
    /** Old uncached property ordering is an independent oracle for the transmitted payload. */
    @SuppressWarnings({"rawtypes","unchecked"}) private static int uncachedPayload(BlockState state){
        DirectionProperty facing=null;var properties=new ArrayList<Property<?>>();
        for(var property:state.getProperties()){if(facing==null&&property instanceof DirectionProperty direction)facing=direction;if(CodecProperties.V3.contains(property))properties.add(property);}
        properties.sort(Comparator.comparing(Property::getName));int packed=0,shift=1;boolean data=false;
        if(facing!=null&&facing!=Properties.VERTICAL_DIRECTION){packed=state.get(facing).getId()<<1;shift+=3;data=true;}
        for(Property property:properties){var values=ordered(property);int width=32-Integer.numberOfLeadingZeros(values.size()-1);if(shift+width>22)throw new AssertionError("Unexpected vanilla payload width");packed|=values.indexOf(state.get(property))<<shift;shift+=width;data=true;}
        return data?packed:-1;
    }
    private static boolean matchesEncodedProperties(BlockState wanted,BlockState decoded){
        DirectionProperty facing=null;for(var property:wanted.getProperties())if(property instanceof DirectionProperty direction){facing=direction;break;}
        if(facing!=null&&facing!=Properties.VERTICAL_DIRECTION&&!wanted.get(facing).equals(decoded.get(facing)))return false;
        for(var property:CodecProperties.V3)if(wanted.contains(property)&&!(property==Properties.SLAB_TYPE&&wanted.get(Properties.SLAB_TYPE)==SlabType.DOUBLE)&&!wanted.get(property).equals(decoded.get(property)))return false;
        return true;
    }
    public static void main(String[] args){net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();System.out.println("PASS PrinterThroughputChecks: "+run()+" checks");}
}
