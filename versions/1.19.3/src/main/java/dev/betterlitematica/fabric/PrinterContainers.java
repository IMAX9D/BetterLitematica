package dev.betterlitematica.fabric;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.screen.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import java.util.function.Predicate;

/** One owned vanilla container session; source discovery never scans a whole schematic. */
final class PrinterContainers {
    private static final int MAX_QUEUED=512,MAX_CACHE=256,MAX_BYTES=8*1024*1024;
    private record Key(long pos,boolean joined){}
    private record Batch(ContainerFillPlan.Plan plan,BitSet touched,long since,int tick,boolean full){}
    private record Created(int slot,ItemStack stack,long since){}
    private final MinecraftClient client;private final ProjectionController controller;
    private final InventoryPolicy.ToolProtection tools=new InventoryPolicy.ToolProtection();
    private final LinkedHashSet<Long> queue=new LinkedHashSet<>();
    private final LinkedHashMap<Long,Block> completed=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Integer> retry=new LinkedHashMap<>();
    private final LinkedHashMap<Key,ContainerPrintTarget> cache=new LinkedHashMap<>(16,.75f,true);
    private int cacheBytes,tick,started;private boolean acting,cancelled,contents,acknowledged;
    private ClientWorld world;private Object connection;private ContainerPrintTarget target;
    private ScreenHandler owned;private HandledScreen<?> screen;private net.minecraft.client.gui.screen.Screen returnScreen;private boolean manualRequested;private Batch batch;
    private List<ItemStack> serverSlots;private ItemStack serverCursor=ItemStack.EMPTY;
    private long receipts,fullReceipt;private long[] slotReceipts;private final BitSet fulfilled=new BitSet();
    private final List<Created> creating=new ArrayList<>();private int creationTick;
    private String reason="";private ItemStack missing=ItemStack.EMPTY;
    private long openCount,clickCount,filledCount;

    PrinterContainers(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    boolean acting(){return acting;}boolean active(){return target!=null;}
    String reason(){return reason;}ItemStack missing(){return missing;}
    ItemStack takeMissing(){var value=missing;missing=ItemStack.EMPTY;return value;}
    long opens(){return openCount;}long clicks(){return clickCount;}long filled(){return filledCount;}
    private Predicate<ItemStack> protection(){var o=controller.options();return tools.get(o.tool,o.toolItem);}
    void observe(BlockPos pos,BlockState wanted,BlockState actual,int tick){
        if(!ContainerPrintTarget.supported(wanted))return;long key=pos.asLong();
        if(actual.getBlock()!=wanted.getBlock()){completed.remove(key);return;}
        if(completed.get(key)==wanted.getBlock()||tick<retry.getOrDefault(key,0)||queue.size()>=MAX_QUEUED)return;
        queue.add(key);
    }
    private ContainerPrintTarget source(BlockPos pos,boolean joined){
        var key=new Key(pos.asLong(),joined);var found=cache.get(key);if(found!=null)return found;
        var value=ContainerPrintTarget.read(controller,pos,joined);
        while(!cache.isEmpty()&&(cache.size()>=MAX_CACHE||cacheBytes+value.bytes()>MAX_BYTES)){
            var it=cache.entrySet().iterator();cacheBytes-=it.next().getValue().bytes();it.remove();
        }
        if(value.bytes()>MAX_BYTES)throw new IllegalStateException("容器内容超过预算");
        cache.put(key,value);cacheBytes+=value.bytes();return value;
    }
    ContainerPrintTarget placement(BlockPos pos){return source(pos,false);}
    private void defer(long pos,int until){if(retry.size()>=MAX_QUEUED&&!retry.containsKey(pos))retry.remove(retry.keySet().iterator().next());retry.put(pos,until);}
    private boolean nearby(BlockPos pos){
        if(client.player==null||client.world==null||client.interactionManager==null||!WorldChunks.loaded(client.world,pos))return false;
        double configured=controller.options().printer.range,reach=client.interactionManager.getReachDistance();if(configured>0)reach=Math.min(reach,configured);
        return PrinterReach.distanceSquared(client.player.getEyePos(),pos)<=reach*reach;
    }
    /** Called after ready block work, so opening a container never steals the initial printing burst. */
    boolean startNext(int tick){
        this.tick=tick;if(active()||queue.isEmpty()||client.currentScreen!=null||client.player==null
            ||client.player.currentScreenHandler!=client.player.playerScreenHandler||!client.player.playerScreenHandler.getCursorStack().isEmpty())return false;
        long deadline=System.nanoTime()+500_000L;int inspected=0;
        while(!queue.isEmpty()&&inspected++<8&&System.nanoTime()<deadline){
            var it=queue.iterator();long key=it.next();it.remove();var pos=BlockPos.fromLong(key);if(!nearby(pos)||tick<retry.getOrDefault(key,0))continue;
            try{
                var next=source(pos,true);if(next.empty()){done(next);continue;}
                if(!next.matchesWorld(client.world)){defer(key,tick+20);continue;}
                target=next;world=client.world;connection=client.getNetworkHandler();started=tick;cancelled=false;contents=false;owned=null;screen=null;returnScreen=null;manualRequested=false;batch=null;fulfilled.clear();missing=ItemStack.EMPTY;
                var eye=client.player.getEyePos();Vec3d hit=null;Direction face=Direction.UP;
                for(var side:Direction.values()){var point=EasyPlacementRules.nearestHitPoint(pos,side,eye);if(hit==null||point.squaredDistanceTo(eye)<hit.squaredDistanceTo(eye)){hit=point;face=side;}}
                if(hit==null||hit.squaredDistanceTo(eye)>Math.pow(client.interactionManager.getReachDistance(),2)){forget();continue;}
                boolean sneaking=client.player.isSneaking();acting=true;
                try{
                    if(sneaking){client.player.input.sneaking=false;client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player,ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));}
                    client.interactionManager.interactBlock(client.player,Hand.MAIN_HAND,new BlockHitResult(hit,face,pos,false));openCount++;reason="填充容器";
                }finally{if(sneaking){client.player.input.sneaking=true;client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player,ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));}acting=false;}
                return true;
            }catch(RuntimeException failure){reason=failure.getMessage();defer(key,tick+40);forget();}
        }return false;
    }
    void opening(ScreenHandlerType<?> type){
        if(active()&&owned==null&&!manualRequested&&world==client.world&&connection==client.getNetworkHandler()&&type==target.screenType())returnScreen=client.currentScreen;
    }
    void opened(int sync,ScreenHandlerType<?> type){
        if(!active()||world!=client.world||connection!=client.getNetworkHandler()||owned!=null)return;
        if(manualRequested){forget();return;}
        if(type!=target.screenType()||!(client.currentScreen instanceof HandledScreen<?> opened)||opened.getScreenHandler().syncId!=sync){reason="容器已由玩家接管";forget();return;}
        owned=opened.getScreenHandler();screen=opened;
        if(owned.slots.size()!=target.items().size()+36){reason="不支持的容器槽位";forget();return;}
        serverSlots=new ArrayList<>(Collections.nCopies(owned.slots.size(),ItemStack.EMPTY));slotReceipts=new long[owned.slots.size()];serverCursor=ItemStack.EMPTY;
        // The server handler remains open. Client-side removal only closes its detached UI inventory.
        if(returnScreen!=null)cancelled=true;
        client.setScreen(cancelled?returnScreen:null);
    }
    void inventory(InventoryS2CPacket packet){
        if(!owns()||packet.getSyncId()!=owned.syncId||packet.getContents().size()!=owned.slots.size())return;
        fullReceipt=++receipts;for(int i=0;i<serverSlots.size();i++){serverSlots.set(i,packet.getContents().get(i).copy());slotReceipts[i]=receipts;}
        serverCursor=packet.getCursorStack().copy();contents=true;if(batch==null)rememberSatisfied(serverSlots);checkAcknowledgement();
    }
    void slot(ScreenHandlerSlotUpdateS2CPacket packet){
        if(!owns()||!contents)return;
        if(packet.getSyncId()==-1&&packet.getSlot()==-1){serverCursor=packet.getItemStack().copy();receipts++;checkAcknowledgement();return;}
        if(packet.getSyncId()!=owned.syncId||packet.getSlot()<0||packet.getSlot()>=serverSlots.size())return;
        int index=packet.getSlot();serverSlots.set(index,packet.getItemStack().copy());slotReceipts[index]=++receipts;checkAcknowledgement();
    }
    private boolean owns(){return active()&&owned!=null&&world==client.world&&connection==client.getNetworkHandler()&&client.player!=null&&client.player.currentScreenHandler==owned;}
    private static boolean satisfied(ItemStack actual,ItemStack wanted){return wanted.isEmpty()||!actual.isEmpty()&&ItemStack.canCombine(actual,wanted)&&actual.getCount()>=wanted.getCount();}
    private void rememberSatisfied(List<ItemStack> observed){for(int i=0;i<target.items().size();i++)if(satisfied(observed.get(i),target.items().get(i)))fulfilled.set(i);}
    private void checkAcknowledgement(){
        if(batch==null||acknowledged||!serverCursor.isEmpty()||batch.full()&&fullReceipt<=batch.since())return;
        for(int i=batch.touched().nextSetBit(0);i>=0;i=batch.touched().nextSetBit(i+1))
            if(slotReceipts[i]<=batch.since()||!ItemStack.areEqual(serverSlots.get(i),batch.plan().after().get(i)))return;
        acknowledged=true;rememberSatisfied(batch.plan().after());
    }
    /** Services already-sent inventory work even after pause; never starts new clicks while paused. */
    boolean tick(int tick,boolean mayWork){
        this.tick=tick;if(!active())return false;
        if(client.world!=world||client.getNetworkHandler()!=connection||client.player==null){forget();return false;}
        if(!mayWork)cancelled=true;
        if(owned==null){if(tick-started>40){defer(target.position().asLong(),tick+40);reason="容器无法打开";forget();}return true;}
        if(!owns()){forget();return false;}
        if(client.currentScreen!=null){cancelled=true;}
        if(!contents){if(tick-started>60)reveal("容器未同步");return true;}
        if(!creating.isEmpty()){
            boolean received=true;for(var entry:creating)if(slotReceipts[entry.slot()]<=entry.since()||!ItemStack.areEqual(serverSlots.get(entry.slot()),entry.stack())){received=false;break;}
            if(!received){if(tick-creationTick>60)reveal("创造取物未获确认");return true;}creating.clear();
        }
        if(batch!=null){
            if(!acknowledged){if(tick-batch.tick()>60)reveal("容器操作未获确认");return true;}
            batch=null;acknowledged=false;
        }
        if(cancelled){release(false);return true;}
        if(!nearby(target.position())||!target.matchesWorld(world)){release(false);return true;}
        if(fulfilled.cardinality()==target.items().size()){done(target);filledCount++;release(true);return true;}
        try{
            var remaining=new ArrayList<ItemStack>(target.items());for(int i=fulfilled.nextSetBit(0);i>=0;i=fulfilled.nextSetBit(i+1))remaining.set(i,ItemStack.EMPTY);
            var plan=ContainerFillPlan.plan(owned,client.player,remaining,controller.options().protectedHotbar,protection(),64);
            if(!plan.clicks().isEmpty()){send(plan);reason="填充容器";return true;}
            if(plan.complete()){rememberSatisfied(serverSlots);if(fulfilled.cardinality()==target.items().size()){done(target);filledCount++;release(true);}else reveal("容器内容未确认");return true;}
            missing=plan.missing();if(!missing.isEmpty()&&client.player.isCreative()&&createMaterials(remaining))return true;
            reason=!plan.blocked().isEmpty()?plan.blocked():missing.isEmpty()?"容器无法填充":"缺少"+missing.getName().getString();release(false);return true;
        }catch(RuntimeException failure){reveal(failure.getMessage()==null?"容器操作中断":failure.getMessage());return true;}
    }
    private boolean createMaterials(List<ItemStack> remaining){
        var inv=client.player.getInventory();var protection=protection();
        var groups=new ArrayList<ItemStack>();
        for(int i=0;i<remaining.size();i++){
            var wanted=remaining.get(i);var slot=owned.getSlot(i);var actual=slot.getStack();
            if(wanted.isEmpty()||satisfied(actual,wanted)||protection.test(wanted)||!slot.canInsert(wanted)
                ||wanted.getCount()>Math.min(wanted.getMaxCount(),slot.getMaxItemCount(wanted))||!actual.isEmpty()&&!ItemStack.canCombine(actual,wanted))continue;
            ItemStack group=null;for(var value:groups)if(ItemStack.canCombine(value,wanted)){group=value;break;}
            if(group==null){group=wanted.copy();group.setCount(0);groups.add(group);}group.increment(wanted.getCount()-actual.getCount());
        }
        long deadline=System.nanoTime()+1_000_000L;long before=receipts;
        for(var group:groups){
            if(!client.getNetworkHandler().hasFeature(group.getItem().getRequiredFeatures()))continue;
            int needed=group.getCount();
            for(int i=0;i<36;i++)if((i>=9||(controller.options().protectedHotbar&(1<<i))==0)&&!protection.test(inv.getStack(i))&&ItemStack.canCombine(inv.getStack(i),group))needed-=inv.getStack(i).getCount();
            for(int i=0;i<36&&needed>0&&creating.size()<32&&System.nanoTime()<deadline;i++)if(inv.getStack(i).isEmpty()&&(i>=9||(controller.options().protectedHotbar&(1<<i))==0)){
                int handlerSlot=owned.getSlotIndex(inv,i).orElse(-1);if(handlerSlot<remaining.size())continue;
                var made=group.copy();made.setCount(Math.min(made.getMaxCount(),needed));
                inv.setStack(i,made);acting=true;try{client.interactionManager.clickCreativeStack(made,InventoryTransfers.playerSlot(i));}catch(RuntimeException e){inv.setStack(i,ItemStack.EMPTY);throw e;}finally{acting=false;}
                creating.add(new Created(handlerSlot,made.copy(),before));needed-=made.getCount();
            }
        }
        creationTick=tick;return !creating.isEmpty();
    }
    private void send(ContainerFillPlan.Plan plan){
        var touched=new BitSet();for(int i=0;i<owned.slots.size();i++)if(!ItemStack.areEqual(owned.getSlot(i).getStack(),plan.after().get(i)))touched.set(i);
        if(touched.isEmpty())throw new IllegalStateException("容器操作没有有效变化");
        batch=new Batch(plan,touched,receipts,tick,plan.clicks().size()>1);acknowledged=false;int revision=owned.getRevision();acting=true;
        try{
            for(int n=0;n<plan.clicks().size();n++){
                var click=plan.clicks().get(n);var before=new ArrayList<ItemStack>(owned.slots.size());for(var slot:owned.slots)before.add(slot.getStack().copy());
                owned.onSlotClick(click.slot(),click.button(),click.action(),client.player);
                var changed=new Int2ObjectOpenHashMap<ItemStack>();boolean confirm=n>=plan.clicks().size()-2;
                if(!confirm)for(int i=0;i<owned.slots.size();i++)if(!ItemStack.areEqual(before.get(i),owned.getSlot(i).getStack()))changed.put(i,owned.getSlot(i).getStack().copy());
                // Omit predictions for the last two real clicks: vanilla returns authoritative slots,
                // then a full inventory when the previous update advances the server revision.
                client.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(owned.syncId,revision,click.slot(),click.button(),click.action(),confirm?ItemStack.EMPTY:owned.getCursorStack().copy(),changed));clickCount++;
            }
            if(!owned.getCursorStack().isEmpty())throw new IllegalStateException("容器操作留下了光标物品");
            for(int i=0;i<owned.slots.size();i++)if(!ItemStack.areEqual(owned.getSlot(i).getStack(),plan.after().get(i)))throw new IllegalStateException("物品操作与预期不一致");
        }finally{acting=false;}
    }
    private void done(ContainerPrintTarget value){
        for(int i=0;i<value.positions().size();i++){long pos=value.positions().get(i).asLong();if(completed.size()>=4096&&!completed.containsKey(pos))completed.remove(completed.keySet().iterator().next());completed.put(pos,value.states().get(i).getBlock());queue.remove(pos);retry.remove(pos);}
    }
    private void release(boolean success){
        if(!active())return;if(!success)defer(target.position().asLong(),tick+40);
        if(owns()&&(!contents||batch!=null||!creating.isEmpty())){cancelled=true;return;}
        if(owns()&&(!serverCursor.isEmpty()||!owned.getCursorStack().isEmpty())){reveal("请先处理光标物品");return;}
        if(owns()){
            client.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(owned.syncId));owned.close(client.player);client.player.currentScreenHandler=client.player.playerScreenHandler;
            if(client.currentScreen==screen)client.setScreen(returnScreen);
        }forget();
    }
    private void reveal(String message){
        reason=message;if(active())defer(target.position().asLong(),tick+100);
        cancelled=true;if(owns()&&client.currentScreen!=null&&client.currentScreen!=screen)return;
        if(owns()&&client.currentScreen==null&&screen!=null)client.setScreen(screen);
        forget();
    }
    private void forget(){target=null;world=null;connection=null;owned=null;screen=null;returnScreen=null;manualRequested=false;batch=null;serverSlots=null;slotReceipts=null;contents=false;acknowledged=false;cancelled=false;fulfilled.clear();creating.clear();}
    void pause(){cancelled=true;}
    void manualRequest(){if(active()&&!acting){manualRequested=true;cancelled=true;}}
    void manual(){if(!acting&&active()){reason="容器已由玩家接管";if(!owns()||client.currentScreen instanceof HandledScreen<?> visible&&visible.getScreenHandler()==owned)forget();else cancelled=true;}}
    void clear(){queue.clear();completed.clear();retry.clear();cache.clear();cacheBytes=0;missing=ItemStack.EMPTY;cancelled=true;}
    void changed(BlockPos pos,BlockState state){var block=completed.get(pos.asLong());if(block!=null&&block!=state.getBlock())completed.remove(pos.asLong());}
}
