package dev.betterlitematica.fabric;

import com.retrievepanel.client.PrinterRefillSession;
import com.retrievepanel.printer.*;
import net.minecraft.*;
import java.lang.reflect.Field;
import java.util.*;

/** All compatibility code is packaged in backpack-retrieve, never in BetterLitematica. */
public final class BackpackPrinterBridge {
    private static final Field REASON=field(PrinterSupply.class,"reason");
    private static final Field ACTION_REASON=field(PrinterActions.class,"reason");
    private static final Field CONTROLLER=field(BetterLitematicaClient.class,"activeController");
    private static final Field NEARBY=field(PrinterEngine.class,"nearby");
    private static final Field QUEUE=field(PrinterEngine.class,"queue"),TICKS=field(PrinterEngine.class,"ticks"),ACTIONS=field(PrinterEngine.class,"actions"),SUPPLY=field(PrinterActions.class,"supply");
    private static final Field BUCKETS=field(dev.betterlitematica.core.PrinterQueue.class,"buckets"),TURNS=field(dev.betterlitematica.core.PrinterQueue.class,"turns"),BATCH=field(dev.betterlitematica.core.PrinterQueue.class,"batchMaterial"),REMAINING=field(dev.betterlitematica.core.PrinterQueue.class,"batchRemaining");
    private static final Field GENERATION=field(PrinterEngine.class,"generation");
    private static final Field PENDING=field(InventoryTransfers.class,"pending"),STARTED=field(InventoryTransfers.class,"startedTick"),SLOT=field(InventoryTransfers.class,"slot"),STAMP=field(InventoryTransfers.class,"stamp");
    public static final MaterialQueue<class_1792> materials=new MaterialQueue<>();
    private static final Map<class_1792,Integer> PACK_SIZES=new IdentityHashMap<>();
    public static final StockPolicy<class_1792> stockPolicy=new StockPolicy<>(BackpackPrinterBridge::packSize,StockPolicy.Settings.defaults());
    private static Object stockEngine,stockWorld;
    private static long stockGeneration=Long.MIN_VALUE;
    private static class_1792 lastRequested;
    private static boolean emptyHandLease;
    private static int lastRequestedTick=-100000,lastEvaluation=-100000;
    public record StockView(StockPolicy.Plan<class_1792> plan,List<StockPolicy.Slot<class_1792>> slots,Set<class_1792> active){}
    private static int recoveryCount,lastRecovery=-1000;
    private static Object deniedOwner,deniedWorld;
    private static long deniedUntil;
    private static String deniedReason;
    private static Field field(Class<?> owner,String name){try{var f=owner.getDeclaredField(name);f.setAccessible(true);return f;}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static ProjectionController controller(){try{return (ProjectionController)CONTROLLER.get(null);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public static void used(class_1792 item,int tick){lastRequested=item;lastRequestedTick=tick;emptyHandLease=item==class_1802.field_8162;stockPolicy.attempted(item);}
    public static boolean emptyHand(Object actions){
        if(!PrinterRefillConfig.current.enabled)return false;
        var client=class_310.method_1551();var c=controller();
        if(c==null||!c.printer().running()||client.field_1724==null||client.field_1724.method_7337()||c.inventoryTransfers().inFlight())return false;
        for(int i=0;i<36;i++)if(client.field_1724.method_31548().method_5438(i).method_7960())return false;
        try{Object supply=SUPPLY.get(actions);if(!request(supply,class_1802.field_8162))return false;
            ACTION_REASON.set(actions,PrinterReason.of(PrinterReason.Id.SUPPLY,"腾出空手操作槽位"));return true;
        }catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    public static Object waitOutcome(){return PrinterActions.Outcome.WAIT;}
    public static int packSize(class_1792 item){return PACK_SIZES.computeIfAbsent(item,key->com.retrievepanel.client.PrinterTrash.ordinary(new class_1799(key))?key.method_7882():1);}
    public static Object engine(){var c=controller();return c==null?null:c.printer();}
    public static long generation(){try{var e=engine();return e==null?Long.MIN_VALUE:GENERATION.getLong(e);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    private static void context(){
        Object e=engine(),world=class_310.method_1551().field_1687;long gen=generation();
        if(e!=stockEngine||world!=stockWorld||gen!=stockGeneration){stockPolicy.reset();stockEngine=e;stockWorld=world;stockGeneration=gen;lastRequested=null;lastEvaluation=-100000;emptyHandLease=false;}
    }
    public static boolean suppressIpnAutoRefill(){
        var client=class_310.method_1551();var c=controller();if(c==null)return false;
        return InventoryOwnership.blocksAutoRefill(PrinterRefillConfig.current.enabled,PrinterRefillConfig.current.coordinateIpn,
            client.field_1724!=null,client.field_1755==null,c.printer().running(),PrinterRefillSession.active(),
            c.inventoryTransfers().inFlight(),com.retrievepanel.client.PrinterTrash.unresolved());
    }
    public static long containerReceipt(){return InventoryReceipts.containerStamp();}
    public static boolean settleRecovery(long before){
        var c=controller();if(c==null||InventoryReceipts.containerStamp()<=before)return false;
        try{c.inventoryTransfers().settle(TICKS.getInt(c.printer()));return !c.inventoryTransfers().inFlight();}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    public static boolean disposable(class_1799 stack){var c=controller();return c!=null&&c.inventoryTransfers().usableForPrinter(stack);}
    @SuppressWarnings("unchecked") public static LinkedHashMap<class_1792,Integer> queued(){
        try{var c=controller();var result=new LinkedHashMap<class_1792,Integer>();if(c==null)return result;
            Object queue=QUEUE.get(c.printer());var buckets=(Map<Integer,ArrayDeque<?>>)BUCKETS.get(queue);var turns=(Set<Integer>)TURNS.get(queue);
            for(int id:turns){if(id<0)continue;var item=class_7923.field_41178.method_10200(id);if(item!=class_1802.field_8162&&packSize(item)>1)result.put(item,buckets.get(id).size());}return result;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    /** One bounded material snapshot per safe evaluation, never per placement. */
    public static StockView stockView(class_1792 urgent,List<StockPolicy.Slot<class_1792>> authoritative){
        context();var c=controller();if(c==null)return null;
        try{
            int tick=TICKS.getInt(c.printer());var work=queued();Set<class_1792> missing=new LinkedHashSet<>();
            for(var m:c.printer().missingMaterials())if(packSize(m.item())>1)missing.add(m.item());
            Set<class_1792> active=new LinkedHashSet<>();Object queue=QUEUE.get(c.printer());
            if(REMAINING.getInt(queue)>0){int id=BATCH.getInt(queue);if(id>=0){var key=class_7923.field_41178.method_10200(id);if(work.containsKey(key))active.add(key);}}
            // equipInternal records a real requested item, not a successful consumption rate.
            if(lastRequested!=null&&tick-lastRequestedTick<=4&&work.containsKey(lastRequested))active.add(lastRequested);
            var slots=authoritative==null?stockSlots():authoritative;
            var cfg=PrinterRefillConfig.current;
            stockPolicy.settings(new StockPolicy.Settings(cfg.refillEmptyDivisor,cfg.rotationDivisor,cfg.admissionRounds,cfg.residentProtectionRounds,cfg.demandGraceRounds));
            stockPolicy.observe(tick/4,work,missing,slots,urgent==class_1802.field_8162?null:urgent);
            boolean trash=cfg.trashSurplus&&com.retrievepanel.client.PrinterTrash.unavailable()==null;
            return new StockView(urgent==class_1802.field_8162?stockPolicy.emptyHandPlan(slots,trash):stockPolicy.plan(slots,urgent,active,trash),slots,active);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static List<StockPolicy.Slot<class_1792>> stockSlots(){
        var inv=class_310.method_1551().field_1724.method_31548();int mask=protectedSlots();
        List<StockPolicy.Slot<class_1792>> result=new ArrayList<>(36);
        for(int i=0;i<36;i++){
            var s=inv.method_5438(i);boolean locked=i<9&&(mask&(1<<i))!=0;
            boolean plain=!s.method_7985()&&!s.method_7938()&&disposable(s);
            boolean ordinary=com.retrievepanel.client.PrinterTrash.ordinary(s);
            boolean usable=!locked&&(s.method_7960()||plain&&ordinary);
            result.add(new StockPolicy.Slot<>(i,s.method_7960()?null:s.method_7909(),s.method_7947(),usable,
                usable&&!s.method_7960()&&i!=inv.field_7545&&com.retrievepanel.client.PrinterTrash.allowed(s)));
        }return result;
    }
    /** Called once per printer tick after context/focus checks, before supplyTick. */
    public static void beforeSupplyTick(Object engine){
        if(!PrinterRefillConfig.current.enabled)return;
        var client=class_310.method_1551();var c=controller();
        if(c==null||c.printer()!=engine||!c.printer().running()||client.field_1724==null||client.field_1724.method_7337()||client.field_1755!=null||client.field_1724.method_6115())return;
        try{
            Object supply=SUPPLY.get(ACTIONS.get(engine));if(((PrinterSupply)supply).active())return;
            int tick=TICKS.getInt(engine);var transfers=c.inventoryTransfers();
            if(transfers.inFlight()){
                if(tick-STARTED.getInt(transfers)<PrinterRefillConfig.current.swapRecoveryTicks)return;
                if(tick-lastRecovery>200)recoveryCount=0;
                if(recoveryCount>=2)throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.CONFIRM_TIMEOUT,"换手同步连续失败，请检查服务器库存操作"));
                RefillLog.warn("swap recovery: tick={}, started={}, slot={}, stampBefore={}, currentStamp={}",tick,STARTED.getInt(transfers),SLOT.getInt(transfers),STAMP.getLong(transfers),InventoryReceipts.stamp(SLOT.getInt(transfers)));
                PrinterRefillSession.begin(supply,null,Map.of());lastRecovery=tick;recoveryCount++;reason(supply,"重新同步换手库存",false);return;
            }
            context();
            if(emptyHandLease||tick-lastEvaluation<4||c.options().printer.bedrock||(!c.options().printer.print&&!c.options().printer.fill))return;
            lastEvaluation=tick;var view=stockView(null,null);
            if(view!=null&&view.plan().actionable())start(supply,null);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static void disconnected(){materials.clear();stockPolicy.reset();stockEngine=stockWorld=null;stockGeneration=Long.MIN_VALUE;lastRequested=null;deniedOwner=deniedWorld=null;deniedUntil=0;recoveryCount=0;lastRecovery=-1000;}
    public static Map<class_1792,Integer> demand(){
        try {var c=(ProjectionController)CONTROLLER.get(null);if(c==null)return Map.of();var p=c.printer();
            Map<class_1792,Integer> result=new LinkedHashMap<>();
            var nearby=(NearbyBuildHud)NEARBY.get(p);
            // Snapshot once per batch, never scan projections inside the placement loop.
            result.putAll(nearby.materialNeeds());
            for(var missing:p.missingMaterials())result.merge(missing.item(),missing.required(),Math::max);
            return result;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static int protectedSlots(){try{var c=(ProjectionController)CONTROLLER.get(null);return c==null?0:c.options().protectedHotbar;}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public static void reason(Object supply,String text,boolean failed){
        try{REASON.set(supply,PrinterReason.of(failed?PrinterReason.Id.SUPPLY_UNAVAILABLE:PrinterReason.Id.SUPPLY,text));}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static boolean request(Object supply,class_1792 item){
        return start(supply,item);
    }
    private static boolean start(Object supply,class_1792 item){
        PrinterRefillClient.compatible=true;
        if(PrinterRefillSession.activeFor(supply))return true;
        if(item!=null&&item!=class_1802.field_8162&&!disposable(new class_1799(item))){reason(supply,"此材料受打印机工具保护，不能自动补货",true);return false;}
        var world=class_310.method_1551().field_1687;
        if(deniedOwner==supply&&deniedWorld==world&&System.nanoTime()<deniedUntil){reason(supply,deniedReason,true);return false;}
        try {
            PrinterRefillSession.beginStock(supply,item);
            reason(supply,"后台批量补货",false);return true;
        }catch(IllegalStateException e){deniedOwner=supply;deniedWorld=world;deniedUntil=System.nanoTime()+1_000_000_000L;deniedReason=e.getMessage();reason(supply,e.getMessage(),true);PrinterRefillClient.lastStatus=e.getMessage();RefillLog.warn("refill unavailable: {}",e.getMessage());return false;}
    }
    public static void tick(Object supply){
        String failure=PrinterRefillSession.tick(supply);
        if(failure!=null)throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.SUPPLY_UNAVAILABLE,failure));
    }
}
