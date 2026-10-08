package dev.betterlitematica.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import java.util.function.Predicate;

/** One owned vanilla container session; source discovery never scans a whole schematic. */
final class PrinterContainers {
    private static final int MAX_QUEUED=512,MAX_CACHE=256,MAX_BYTES=8*1024*1024;
    private record Key(long pos,boolean joined){}
    private record Batch(ContainerFillPlan.Plan plan,BitSet touched,long since,int tick,boolean full){}
    private record Created(int slot,ItemStack stack,long since){}
    private final Minecraft client;private final ProjectionController controller;
    private final InventoryPolicy.ToolProtection tools=new InventoryPolicy.ToolProtection();
    private final LinkedHashSet<Long> queue=new LinkedHashSet<>();
    private final LinkedHashMap<Long,Block> completed=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Integer> retry=new LinkedHashMap<>();
    private final LinkedHashMap<Key,ContainerPrintTarget> cache=new LinkedHashMap<>(16,.75f,true);
    private int cacheBytes,tick,started;private boolean acting,cancelled,contents,acknowledged;
    private ClientLevel world;private Object connection;private ContainerPrintTarget target;
    private AbstractContainerMenu owned;private AbstractContainerScreen<?> screen;private net.minecraft.client.gui.screens.Screen returnScreen;private boolean manualRequested;private Batch batch;
    private List<ItemStack> serverSlots;private ItemStack serverCursor=ItemStack.EMPTY;
    private long receipts,fullReceipt;private long[] slotReceipts;private final BitSet fulfilled=new BitSet();
    private final List<Created> creating=new ArrayList<>();private int creationTick;
    private String reason="";private ItemStack missing=ItemStack.EMPTY;
    private long openCount,clickCount,filledCount;

    PrinterContainers(Minecraft client,ProjectionController controller){this.client=client;this.controller=controller;}
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
        if(client.player==null||client.level==null||client.gameMode==null||!WorldChunks.loaded(client.level,pos))return false;
        double configured=controller.options().printer.range,reach=client.player.blockInteractionRange();if(configured>0)reach=Math.min(reach,configured);
        return PrinterReach.distanceSquared(client.player.getEyePosition(),pos)<=reach*reach;
    }
    /** Called after ready block work, so opening a container never steals the initial printing burst. */
    boolean startNext(int tick){
        this.tick=tick;if(active()||queue.isEmpty()||ClientUi.screen(client)!=null||client.player==null
            ||client.player.containerMenu!=client.player.inventoryMenu||!client.player.inventoryMenu.getCarried().isEmpty())return false;
        long deadline=System.nanoTime()+500_000L;int inspected=0;
        while(!queue.isEmpty()&&inspected++<8&&System.nanoTime()<deadline){
            var it=queue.iterator();long key=it.next();it.remove();var pos=BlockPos.of(key);if(!nearby(pos)||tick<retry.getOrDefault(key,0))continue;
            try{
                var next=source(pos,true);if(next.empty()){done(next);continue;}
                if(!next.matchesWorld(client.level)){defer(key,tick+20);continue;}
                target=next;world=client.level;connection=client.getConnection();started=tick;cancelled=false;contents=false;owned=null;screen=null;returnScreen=null;manualRequested=false;batch=null;fulfilled.clear();missing=ItemStack.EMPTY;
                var eye=client.player.getEyePosition();Vec3 hit=null;Direction face=Direction.UP;
                for(var side:Direction.values()){var point=EasyPlacementRules.nearestHitPoint(pos,side,eye);if(hit==null||point.distanceToSqr(eye)<hit.distanceToSqr(eye)){hit=point;face=side;}}
                if(hit==null||hit.distanceToSqr(eye)>Math.pow(client.player.blockInteractionRange(),2)){forget();continue;}
                boolean sneaking=client.player.isShiftKeyDown();acting=true;
                try{
                    if(sneaking){PlayerInputBridge.shift(client.player,false);PlayerInputBridge.sendShift(client.player,false);}
                    client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,new BlockHitResult(hit,face,pos,false));openCount++;reason="填充容器";
                }finally{if(sneaking){PlayerInputBridge.shift(client.player,true);PlayerInputBridge.sendShift(client.player,true);}acting=false;}
                return true;
            }catch(RuntimeException failure){reason=failure.getMessage();defer(key,tick+40);forget();}
        }return false;
    }
    void opening(MenuType<?> type){
        if(active()&&owned==null&&!manualRequested&&world==client.level&&connection==client.getConnection()&&type==target.screenType())returnScreen=ClientUi.screen(client);
    }
    void opened(int sync,MenuType<?> type){
        if(!active()||world!=client.level||connection!=client.getConnection()||owned!=null)return;
        if(manualRequested){forget();return;}
        if(type!=target.screenType()||!(ClientUi.screen(client) instanceof AbstractContainerScreen<?> opened)||opened.getMenu().containerId!=sync){reason="容器已由玩家接管";forget();return;}
        owned=opened.getMenu();screen=opened;
        if(owned.slots.size()!=target.items().size()+36){reason="不支持的容器槽位";forget();return;}
        serverSlots=new ArrayList<>(Collections.nCopies(owned.slots.size(),ItemStack.EMPTY));slotReceipts=new long[owned.slots.size()];serverCursor=ItemStack.EMPTY;
        // The server handler remains open. Client-side removal only closes its detached UI inventory.
        if(returnScreen!=null)cancelled=true;
        ClientUi.setScreen(client,cancelled?returnScreen:null);
    }
    void inventory(ClientboundContainerSetContentPacket packet){
        if(!owns()||packet.containerId()!=owned.containerId||packet.items().size()!=owned.slots.size())return;
        fullReceipt=++receipts;for(int i=0;i<serverSlots.size();i++){serverSlots.set(i,packet.items().get(i).copy());slotReceipts[i]=receipts;}
        serverCursor=packet.carriedItem().copy();contents=true;if(batch==null)rememberSatisfied(serverSlots);checkAcknowledgement();
    }
    void slot(ClientboundContainerSetSlotPacket packet){
        if(!owns()||!contents)return;
        if(packet.getContainerId()==-1&&packet.getSlot()==-1){serverCursor=packet.getItem().copy();receipts++;checkAcknowledgement();return;}
        if(packet.getContainerId()!=owned.containerId||packet.getSlot()<0||packet.getSlot()>=serverSlots.size())return;
        int index=packet.getSlot();serverSlots.set(index,packet.getItem().copy());slotReceipts[index]=++receipts;checkAcknowledgement();
    }
    void cursor(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket packet){if(!owns()||!contents)return;serverCursor=packet.contents().copy();receipts++;checkAcknowledgement();}
    void playerInventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet){if(!owns()||!contents)return;int index=owned.findSlot(client.player.getInventory(),packet.slot()).orElse(-1);if(index<0||index>=serverSlots.size())return;serverSlots.set(index,packet.contents().copy());slotReceipts[index]=++receipts;checkAcknowledgement();}
    private boolean owns(){return active()&&owned!=null&&world==client.level&&connection==client.getConnection()&&client.player!=null&&client.player.containerMenu==owned;}
    private static boolean satisfied(ItemStack actual,ItemStack wanted){return wanted.isEmpty()||!actual.isEmpty()&&ItemStack.isSameItemSameComponents(actual,wanted)&&actual.getCount()>=wanted.getCount();}
    private void rememberSatisfied(List<ItemStack> observed){for(int i=0;i<target.items().size();i++)if(satisfied(observed.get(i),target.items().get(i)))fulfilled.set(i);}
    private void checkAcknowledgement(){
        if(batch==null||acknowledged||!serverCursor.isEmpty()||batch.full()&&fullReceipt<=batch.since())return;
        for(int i=batch.touched().nextSetBit(0);i>=0;i=batch.touched().nextSetBit(i+1))
            if(slotReceipts[i]<=batch.since()||!ItemStack.matches(serverSlots.get(i),batch.plan().after().get(i)))return;
        acknowledged=true;rememberSatisfied(batch.plan().after());
    }
    /** Services already-sent inventory work even after pause; never starts new clicks while paused. */
    boolean tick(int tick,boolean mayWork){
        this.tick=tick;if(!active())return false;
        if(client.level!=world||client.getConnection()!=connection||client.player==null){forget();return false;}
        if(!mayWork)cancelled=true;
        if(owned==null){if(tick-started>40){defer(target.position().asLong(),tick+40);reason="容器无法打开";forget();}return true;}
        if(!owns()){forget();return false;}
        if(ClientUi.screen(client)!=null){cancelled=true;}
        if(!contents){if(tick-started>60)reveal("容器未同步");return true;}
        if(!creating.isEmpty()){
            boolean received=true;for(var entry:creating)if(slotReceipts[entry.slot()]<=entry.since()||!ItemStack.matches(serverSlots.get(entry.slot()),entry.stack())){received=false;break;}
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
            reason=!plan.blocked().isEmpty()?plan.blocked():missing.isEmpty()?"容器无法填充":"缺少"+missing.getHoverName().getString();release(false);return true;
        }catch(RuntimeException failure){reveal(failure.getMessage()==null?"容器操作中断":failure.getMessage());return true;}
    }
    private boolean createMaterials(List<ItemStack> remaining){
        var inv=client.player.getInventory();var protection=protection();
        var groups=new ArrayList<ItemStack>();
        for(int i=0;i<remaining.size();i++){
            var wanted=remaining.get(i);var slot=owned.getSlot(i);var actual=slot.getItem();
            if(wanted.isEmpty()||satisfied(actual,wanted)||protection.test(wanted)||!slot.mayPlace(wanted)
                ||wanted.getCount()>Math.min(wanted.getMaxStackSize(),slot.getMaxStackSize(wanted))||!actual.isEmpty()&&!ItemStack.isSameItemSameComponents(actual,wanted))continue;
            ItemStack group=null;for(var value:groups)if(ItemStack.isSameItemSameComponents(value,wanted)){group=value;break;}
            if(group==null){group=wanted.copy();group.setCount(0);groups.add(group);}group.grow(wanted.getCount()-actual.getCount());
        }
        long deadline=System.nanoTime()+1_000_000L;long before=receipts;
        for(var group:groups){
            if(!client.getConnection().isFeatureEnabled(group.getItem().requiredFeatures()))continue;
            int needed=group.getCount();
            for(int i=0;i<36;i++)if((i>=9||(controller.options().protectedHotbar&(1<<i))==0)&&!protection.test(inv.getItem(i))&&ItemStack.isSameItemSameComponents(inv.getItem(i),group))needed-=inv.getItem(i).getCount();
            for(int i=0;i<36&&needed>0&&creating.size()<32&&System.nanoTime()<deadline;i++)if(inv.getItem(i).isEmpty()&&(i>=9||(controller.options().protectedHotbar&(1<<i))==0)){
                int handlerSlot=owned.findSlot(inv,i).orElse(-1);if(handlerSlot<remaining.size())continue;
                var made=group.copy();made.setCount(Math.min(made.getMaxStackSize(),needed));
                inv.setItem(i,made);acting=true;try{client.gameMode.handleCreativeModeItemAdd(made,InventoryTransfers.playerSlot(i));}catch(RuntimeException e){inv.setItem(i,ItemStack.EMPTY);throw e;}finally{acting=false;}
                creating.add(new Created(handlerSlot,made.copy(),before));needed-=made.getCount();
            }
        }
        creationTick=tick;return !creating.isEmpty();
    }
    private void send(ContainerFillPlan.Plan plan){
        var touched=new BitSet();for(int i=0;i<owned.slots.size();i++)if(!ItemStack.matches(owned.getSlot(i).getItem(),plan.after().get(i)))touched.set(i);
        if(touched.isEmpty())throw new IllegalStateException("容器操作没有有效变化");
        batch=new Batch(plan,touched,receipts,tick,plan.clicks().size()>1);acknowledged=false;int revision=owned.getStateId();acting=true;
        try{
            for(int n=0;n<plan.clicks().size();n++){
                var click=plan.clicks().get(n);var before=new ArrayList<ItemStack>(owned.slots.size());for(var slot:owned.slots)before.add(slot.getItem().copy());
                owned.clicked(click.slot(),click.button(),click.action(),client.player);
                var changed=new Int2ObjectOpenHashMap<net.minecraft.network.HashedStack>();var hash=client.getConnection().decoratedHashOpsGenenerator();boolean confirm=n>=plan.clicks().size()-2;
                if(!confirm)for(int i=0;i<owned.slots.size();i++)if(!ItemStack.matches(before.get(i),owned.getSlot(i).getItem()))changed.put(i,net.minecraft.network.HashedStack.create(owned.getSlot(i).getItem(),hash));
                // Omit predictions for the last two real clicks: vanilla returns authoritative slots,
                // then a full inventory when the previous update advances the server revision.
                client.getConnection().send(new ServerboundContainerClickPacket(owned.containerId,revision,(short)click.slot(),(byte)click.button(),click.action(),changed,confirm?net.minecraft.network.HashedStack.EMPTY:net.minecraft.network.HashedStack.create(owned.getCarried(),hash)));clickCount++;
            }
            if(!owned.getCarried().isEmpty())throw new IllegalStateException("容器操作留下了光标物品");
            for(int i=0;i<owned.slots.size();i++)if(!ItemStack.matches(owned.getSlot(i).getItem(),plan.after().get(i)))throw new IllegalStateException("物品操作与预期不一致");
        }finally{acting=false;}
    }
    private void done(ContainerPrintTarget value){
        for(int i=0;i<value.positions().size();i++){long pos=value.positions().get(i).asLong();if(completed.size()>=4096&&!completed.containsKey(pos))completed.remove(completed.keySet().iterator().next());completed.put(pos,value.states().get(i).getBlock());queue.remove(pos);retry.remove(pos);}
    }
    private void release(boolean success){
        if(!active())return;if(!success)defer(target.position().asLong(),tick+40);
        if(owns()&&(!contents||batch!=null||!creating.isEmpty())){cancelled=true;return;}
        if(owns()&&(!serverCursor.isEmpty()||!owned.getCarried().isEmpty())){reveal("请先处理光标物品");return;}
        if(owns()){
            client.getConnection().send(new ServerboundContainerClosePacket(owned.containerId));owned.removed(client.player);client.player.containerMenu=client.player.inventoryMenu;
            if(ClientUi.screen(client)==screen)ClientUi.setScreen(client,returnScreen);
        }forget();
    }
    private void reveal(String message){
        reason=message;if(active())defer(target.position().asLong(),tick+100);
        cancelled=true;if(owns()&&ClientUi.screen(client)!=null&&ClientUi.screen(client)!=screen)return;
        if(owns()&&ClientUi.screen(client)==null&&screen!=null)ClientUi.setScreen(client,screen);
        forget();
    }
    private void forget(){target=null;world=null;connection=null;owned=null;screen=null;returnScreen=null;manualRequested=false;batch=null;serverSlots=null;slotReceipts=null;contents=false;acknowledged=false;cancelled=false;fulfilled.clear();creating.clear();}
    void pause(){cancelled=true;}
    void manualRequest(){if(active()&&!acting){manualRequested=true;cancelled=true;}}
    void manual(){if(!acting&&active()){reason="容器已由玩家接管";if(!owns()||ClientUi.screen(client) instanceof AbstractContainerScreen<?> visible&&visible.getMenu()==owned)forget();else cancelled=true;}}
    void clear(){queue.clear();completed.clear();retry.clear();cache.clear();cacheBytes=0;missing=ItemStack.EMPTY;cancelled=true;}
    void changed(BlockPos pos,BlockState state){var block=completed.get(pos.asLong());if(block!=null&&block!=state.getBlock())completed.remove(pos.asLong());}
}
