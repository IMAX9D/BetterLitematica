package com.retrievepanel.client;

import com.retrievepanel.printer.*;
import dev.betterlitematica.fabric.BackpackPrinterBridge;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.*;
import java.lang.reflect.Field;
import java.util.*;

/** Packet handlers record evidence only. All sends and closes occur at a printer tick boundary. */
public final class PrinterRefillSession {
    private enum Phase { OPEN, CONTENT, PLAN, TRASH, CONFIRM }
    private static final class_2960 BACKPACK=new class_2960("travelersbackpack","backpack_item");
    private static PrinterRefillSession current,late,abandoned;
    private static final Field PENDING=pending("pendingStack"),LOOP_PENDING=pending("pendingLoopItem");
    private final Object owner,player,connection,world;
    private final class_310 client=class_310.method_1551();
    private final RetrieveBackpackMenuAccess access;
    private final RetrieveBackpackMenuAccess.Source source;
    private final class_1792 wanted;
    private final boolean recovery;
    private final Object engine;
    private final long generation;
    private StockPolicy.Plan<class_1792> stockPlan;
    private final int limit,timeout,protectedSlots;
    private final long beforeContainerReceipt;
    private class_1703 menu;
    private class_1799[] serverSlots;
    private boolean fullReceived;
    private Phase phase=Phase.OPEN;
    private final List<Write> writes=new ArrayList<>();
    private final List<Write> deletes=new ArrayList<>();
    private final List<Write> allWrites=new ArrayList<>();
    private int nextWrite;
    private final int[] menuToWrite=new int[512],inventoryToWrite=new int[36],menuToDelete=new int[512],inventoryToDelete=new int[36];
    private ReceiptBatch receipts,trashReceipts;
    private int age;
    private String failure;
    private long lateUntil;
    private record Write(int menuSlot,int inventorySlot,class_1799 stack){}
    private static Field pending(String name){try{var f=RetrievePanelClient.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static boolean otherTask(){try{return RetrieveLoop.isActive()||RetrieveTaskRunner.isBusy()||PENDING.get(null)!=null||LOOP_PENDING.get(null)!=null;}catch(ReflectiveOperationException e){return true;}}
    private PrinterRefillSession(Object owner,class_1792 wanted,boolean recovery)throws ReflectiveOperationException{
        this.owner=owner;this.wanted=wanted;this.recovery=recovery;
        engine=BackpackPrinterBridge.engine();generation=BackpackPrinterBridge.generation();
        player=client.field_1724;connection=client.method_1562();world=client.field_1687;
        access=RetrieveBackpackMenuAccess.get();source=access.capture(client.field_1724);
        if(source==null||!access.stillWearing(client.field_1724,source))throw new IllegalStateException("请先穿戴旅行者背包");
        var config=PrinterRefillConfig.current;limit=config.maxStacksPerBatch;timeout=config.timeoutTicks;
        protectedSlots=BackpackPrinterBridge.protectedSlots();beforeContainerReceipt=BackpackPrinterBridge.containerReceipt();
        for(var map:List.of(menuToWrite,inventoryToWrite,menuToDelete,inventoryToDelete))Arrays.fill(map,-1);
    }
    public static boolean activeFor(Object owner){return current!=null&&current.owner==owner;}
    public static boolean active(){return current!=null;}
    public static void begin(Object owner,class_1792 item,Map<class_1792,Integer> needs){
        begin(owner,item,item==null);
    }
    public static void beginStock(Object owner,class_1792 item){begin(owner,item,false);}
    private static void begin(Object owner,class_1792 item,boolean recovery){
        if(current!=null)throw new IllegalStateException("已有背包补货任务");
        if(PrinterTrash.unresolved())throw new IllegalStateException("上次垃圾桶操作结果待确认，暂不再次补货");
        var c=class_310.method_1551();
        if(c.field_1724==null||c.field_1687==null||c.method_1562()==null)throw new IllegalStateException("未进入世界");
        if(c.field_1755!=null||c.field_1724.field_7512!=c.field_1724.field_7498||!c.field_1724.field_7498.method_34255().method_7960())throw new IllegalStateException("等待物品栏操作结束");
        if(otherTask())throw new IllegalStateException("等待背包取物任务结束");
        if(!RetrieveActions.channelAvailable())throw new IllegalStateException("服务器未提供背包取物通道");
        try{
            current=new PrinterRefillSession(owner,item,recovery);late=null;RetrieveActions.sendOpenScreen();
            PrinterRefillClient.lastStatus=recovery?"重新同步换手库存":"等待后台背包会话";
            RefillLog.info("begin recovery={}, wanted={}",recovery,item);
        }catch(ReflectiveOperationException|LinkageError e){current=null;throw new IllegalStateException("穿戴背包接口不兼容");}
        catch(RuntimeException e){current=null;throw e;}
    }
    private boolean sameContext(){return client.field_1724==player&&client.field_1687==world&&client.method_1562()==connection;}
    private String problem(){
        if(!sameContext())return "玩家、世界或连接已变化";
        if(engine!=BackpackPrinterBridge.engine()||generation!=BackpackPrinterBridge.generation())return "打印任务已变化";
        if(protectedSlots!=BackpackPrinterBridge.protectedSlots())return "打印保护栏已变化";
        if(!PrinterRefillConfig.current.enabled)return "后台补货已关闭";
        if(!client.field_1724.method_5805()||client.field_1724.method_7325())return "玩家状态已变化";
        if(!client.method_1569())return "等待返回游戏";
        if(client.field_1755!=null)return "补货已由玩家接管";
        if(otherTask())return "其他背包任务已接管";
        if(menu!=null&&client.field_1724.field_7512!=menu)return "后台背包会话已关闭或切换";
        if(menu==null&&client.field_1724.field_7512!=client.field_1724.field_7498)return "容器已切换";
        if(!client.field_1724.field_7512.method_34255().method_7960())return "请先放下光标上的物品";
        try{if(!access.stillWearing(client.field_1724,source))return "穿戴背包已切换";}
        catch(ReflectiveOperationException|LinkageError e){return "穿戴背包接口不可用";}
        return null;
    }
    public static boolean capture(class_2960 typeId,int sync,class_2540 buf){
        if(!BACKPACK.equals(typeId))return false;
        PrinterRefillSession s=current;
        if(s==null&&late!=null){if(System.nanoTime()<late.lateUntil&&late.sameContext())s=late;else late=null;}
        if(s==null||s.menu!=null||!s.sameContext())return false;
        if(s.client.field_1755!=null&&s.failure==null||s.client.field_1724.field_7512!=s.client.field_1724.field_7498)return false;
        try{
            var type=class_7923.field_41187.method_10223(typeId);if(!(type instanceof ExtendedScreenHandlerType<?> extended))return false;
            s.menu=extended.create(sync,s.client.field_1724.method_31548(),buf);s.client.field_1724.field_7512=s.menu;
            if(!s.access.matches(s.menu,s.client.field_1724,s.source)){s.fail("后台容器不是当前穿戴的背包");return true;}
            if(s==late||s.failure!=null){s.closeOwned();late=null;return true;}
            if(s.menu.field_7761.size()>512){s.fail("背包槽位超出兼容范围");return true;}
            s.serverSlots=new class_1799[s.menu.field_7761.size()];Arrays.fill(s.serverSlots,class_1799.field_8037);
            s.phase=Phase.CONTENT;s.age=0;RefillLog.info("hidden menu opened sync={}, slots={}",sync,s.serverSlots.length);
            return true;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException e){s.fail("无法建立后台背包会话");return true;}
    }
    public static String tick(Object owner){
        var s=current;if(s==null||s.owner!=owner)return null;
        if(s.failure==null){String problem=s.problem();if(problem!=null)s.fail(problem);}
        if(s.failure==null&&++s.age>s.timeout)s.fail("后台"+(s.phase==Phase.TRASH?"垃圾桶回收":"补货")+"未获服务器确认，已暂停");
        if(s.failure==null)try{s.advance();}catch(RuntimeException e){s.fail("补货操作失败："+e.getMessage());}
        if(s.failure!=null){String failure=s.failure;cancel(owner,true);return failure;}
        return null;
    }
    private void advance(){
        switch(phase){
            case OPEN -> {}
            case CONTENT -> {
                if(!fullReceived)return;
                // Wait until EVERY inventory observer has consumed the full packet before closing.
                if(BackpackPrinterBridge.containerReceipt()<=beforeContainerReceipt)return;
                if(recovery){if(!BackpackPrinterBridge.settleRecovery(beforeContainerReceipt)){fail("完整库存同步后换手仍未解除");return;}complete();return;}
                phase=Phase.PLAN;age=0;plan();
            }
            case PLAN -> plan();
            case TRASH -> {
                if(!trashReceipts.complete()||!PrinterTrash.confirmed())return;
                for(var d:deletes)if(!client.field_1724.method_31548().method_5438(d.inventorySlot()).method_7960()){fail("垃圾桶回包后槽位发生变化");return;}
                RefillLog.info("trash confirmed sync={}, slots={}",menu.field_7763,deletes.size());PrinterTrash.consumed();
                if(stockPlan.writes().isEmpty()){BackpackPrinterBridge.stockPolicy.committed(stockPlan);complete();return;}
                sendBatch();
            }
            case CONFIRM -> {
                if(!receipts.complete())return;
                for(var w:writes)if(!class_1799.method_7973(w.stack(),client.field_1724.method_31548().method_5438(w.inventorySlot()))){fail("补货槽位已被其他操作更改");return;}
                allWrites.addAll(writes);
                if(nextWrite<stockPlan.writes().size()){sendBatch();return;}
                for(var w:allWrites)if(!class_1799.method_7973(w.stack(),client.field_1724.method_31548().method_5438(w.inventorySlot()))){fail("已确认的补货槽位发生变化");return;}
                BackpackPrinterBridge.stockPolicy.committed(stockPlan);complete();
            }
        }
    }
    private static PrinterRefillSession recipient(int sync){
        if(current!=null&&current.menu!=null&&(sync==current.menu.field_7763||sync==-2)&&current.sameContext())return current;
        if(abandoned!=null&&abandoned.menu!=null&&(sync==abandoned.menu.field_7763||sync==-2)&&abandoned.sameContext())return abandoned;
        return null;
    }
    public static void inventory(class_2649 packet){
        var s=recipient(packet.method_11440());if(s==null||s.serverSlots==null)return;
        if(packet.method_11441().size()!=s.serverSlots.length){s.fail("背包库存数据长度异常");return;}
        for(int i=0;i<s.serverSlots.length;i++){var stack=packet.method_11441().get(i);s.serverSlots[i]=stack.method_7972();s.ack(i,stack);}
        // Validate all 36 real player inventory slots before granting the initial full snapshot.
        long mask=0;for(var slot:s.menu.field_7761)if(slot.field_7871==s.client.field_1724.method_31548()&&slot.method_34266()>=0&&slot.method_34266()<36)mask|=1L<<slot.method_34266();
        s.fullReceived=mask==((1L<<36)-1);
    }
    public static void slot(class_2653 packet){
        var s=recipient(packet.method_11452());if(s==null||s.serverSlots==null)return;
        int id=packet.method_11450();
        if(packet.method_11452()==-2){if(id<0||id>=36)return;int found=-1;for(int i=0;i<s.menu.field_7761.size();i++){var slot=s.menu.method_7611(i);if(slot.field_7871==s.client.field_1724.method_31548()&&slot.method_34266()==id){found=i;break;}}id=found;}
        if(id<0||id>=s.serverSlots.length)return;s.serverSlots[id]=packet.method_11449().method_7972();s.ack(id,packet.method_11449());
    }
    private void ack(int id,class_1799 stack){
        int deletion=menuToDelete[id];if(trashReceipts!=null&&deletion>=0)trashReceipts.receive(deletion,stack.method_7960());
        int write=menuToWrite[id];if(receipts!=null&&write>=0)receipts.receive(write,class_1799.method_7973(writes.get(write).stack(),stack));
    }
    private List<Integer> emptySlots(){
        var inv=client.field_1724.method_31548();List<Integer> empty=new ArrayList<>();int selected=inv.field_7545;
        for(int pass=0;pass<3;pass++)for(int i=0;i<menu.field_7761.size();i++){
            var slot=menu.method_7611(i);int index=slot.method_34266();
            if(slot.field_7871!=inv||index<0||index>=36||!slot.method_7677().method_7960()||!serverSlots[i].method_7960()||index<9&&(protectedSlots&(1<<index))!=0)continue;
            if(pass==0&&index==selected||pass==1&&index<9&&index!=selected||pass==2&&index>=9)empty.add(i);
        }return empty;
    }
    private void plan(){
        var inv=client.field_1724.method_31548();List<StockPolicy.Slot<class_1792>> stock=new ArrayList<>(36);Map<Integer,Integer> toMenu=new HashMap<>();
        for(int i=0;i<menu.field_7761.size();i++){
            var slot=menu.method_7611(i);int index=slot.method_34266();if(slot.field_7871!=inv||index<0||index>=36)continue;
            var stack=slot.method_7677();boolean locked=index<9&&(protectedSlots&(1<<index))!=0;
            boolean exact=class_1799.method_7973(stack,serverSlots[i]);
            boolean plain=!stack.method_7985()&&!stack.method_7938()&&BackpackPrinterBridge.disposable(stack);
            boolean usable=!locked&&exact&&(stack.method_7960()||plain&&(PrinterTrash.ordinary(stack)||stack.method_7909()==wanted));
            boolean safe=usable&&index!=inv.field_7545&&PrinterTrash.ordinary(stack)&&PrinterTrash.allowed(stack);
            stock.add(new StockPolicy.Slot<>(index,stack.method_7960()?null:stack.method_7909(),stack.method_7947(),usable,safe));toMenu.put(index,i);
        }
        var view=BackpackPrinterBridge.stockView(wanted,stock);stockPlan=view==null?null:view.plan();
        if(stockPlan==null||!stockPlan.actionable()){
            boolean missing=wanted==class_1802.field_8162?stock.stream().noneMatch(s->s.usable()&&s.count()==0):wanted!=null&&stock.stream().noneMatch(s->s.usable()&&s.count()>0&&s.item()==wanted);
            if(!missing){complete();return;}
            String unavailable=PrinterRefillConfig.current.trashSurplus?PrinterTrash.unavailable():null;
            if(age<timeout&&"等待服务器垃圾桶同步".equals(unavailable))return;
            fail(unavailable!=null?unavailable:"没有可安全腾出的打印槽位，请留出一个空格");return;
        }
        RefillLog.info("stock plan cause={}, stacks={}, reclaim={}, admissions={}, waiting={}",stockPlan.cause(),stockPlan.writes().size(),stockPlan.deletes().size(),stockPlan.admissions(),BackpackPrinterBridge.stockPolicy.waitingCount());
        for(int index:stockPlan.deletes()){
            int id=toMenu.get(index);deletes.add(new Write(id,index,menu.method_7611(id).method_7677().method_7972()));
        }
        if(!deletes.isEmpty()){
            trashReceipts=new ReceiptBatch(deletes.size());for(int i=0;i<deletes.size();i++){var d=deletes.get(i);menuToDelete[d.menuSlot()]=i;inventoryToDelete[d.inventorySlot()]=i;}
            phase=Phase.TRASH;age=0;PrinterTrash.send(deletes.stream().map(Write::menuSlot).toList(),deletes.stream().map(Write::stack).toList());PrinterRefillClient.lastStatus="腾出 "+deletes.size()+" 个打印槽位";return;
        }
        sendBatch();
    }
    private void sendBatch(){
        var empty=emptySlots();int count=Math.min(limit,stockPlan.writes().size()-nextWrite);
        if(count<1||empty.size()<count){fail("已规划的空槽发生变化");return;}
        writes.clear();Arrays.fill(menuToWrite,-1);Arrays.fill(inventoryToWrite,-1);
        for(int i=0;i<count;i++){
            var entry=stockPlan.writes().get(nextWrite+i);int id=empty.get(i);var slot=menu.method_7611(id);
            var stack=new class_1799(entry.item(),Math.min(entry.count(),slot.method_7676(new class_1799(entry.item()))));
            if(!slot.method_7677().method_7960()||!serverSlots[id].method_7960()||!slot.method_7680(stack)||stack.method_7960()){fail("补货槽位不可用");return;}
            writes.add(new Write(id,slot.method_34266(),stack));
        }
        nextWrite+=count;
        receipts=new ReceiptBatch(writes.size());for(int i=0;i<writes.size();i++){var w=writes.get(i);menuToWrite[w.menuSlot()]=i;inventoryToWrite[w.inventorySlot()]=i;}
        phase=Phase.CONFIRM;age=0;for(var w:writes)RetrieveActions.sendSetStack(w.menuSlot(),w.stack());
        RefillLog.info("refill send sync={}, stacks={}, playerSlots={}",menu.field_7763,writes.size(),writes.stream().map(Write::inventorySlot).toList());
        PrinterRefillClient.lastStatus="等待 "+writes.size()+" 组材料到账";
    }
    private void complete(){
        closeOwned();PrinterRefillClient.lastStatus=recovery?"换手库存已重新同步":"已补货 "+allWrites.size()+" 组，回收 "+deletes.size()+" 组";
        RefillLog.info("complete recovery={}, sync={}, stacks={}, reclaimed={}, containerReceipt={}",recovery,menu.field_7763,allWrites.size(),deletes.size(),BackpackPrinterBridge.containerReceipt());current=null;
    }
    private void fail(String message){if(failure==null){failure=message;PrinterRefillClient.lastStatus=message;RefillLog.warn("failure phase={}, sync={}, age={}, pendingWrites={}, pendingTrash={}, reason={}",phase,menu==null?-1:menu.field_7763,age,receipts==null?0:receipts.pending(),trashReceipts==null?0:trashReceipts.pending(),message);}}
    public static void userScreen(class_437 next){if(next!=null&&current!=null){current.fail("补货已由玩家界面操作中断");current.closeOwned();}}
    private void closeOwned(){
        boolean visible=client.field_1755 instanceof class_465<?> screen&&screen.method_17577()==menu;
        if(menu!=null&&sameContext()&&!visible&&client.field_1724.field_7512==menu&&menu.method_34255().method_7960()){
            client.method_1562().method_2883(new class_2815(menu.field_7763));client.field_1724.field_7512=client.field_1724.field_7498;
        }
    }
    public static void cancel(Object owner,boolean close){
        var s=current;if(s==null||s.owner!=owner)return;if(close)s.closeOwned();
        if(s.trashReceipts!=null&&PrinterTrash.unresolved())abandoned=s;
        if(s.menu==null&&s.sameContext()){s.failure="补货已取消";s.lateUntil=System.nanoTime()+5_000_000_000L;late=s;}
        current=null;
    }
    public static void backgroundTick(){
        if(abandoned!=null&&abandoned.sameContext()&&abandoned.trashReceipts.complete()&&PrinterTrash.confirmed()){PrinterTrash.consumed();abandoned=null;RefillLog.info("late disposal receipts settled; no retry was sent");}
    }
    public static void disconnected(){current=late=abandoned=null;PrinterTrash.reset();BackpackPrinterBridge.disconnected();}
}
