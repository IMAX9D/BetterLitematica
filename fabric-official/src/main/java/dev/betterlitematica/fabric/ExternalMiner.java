package dev.betterlitematica.fabric;

import java.lang.reflect.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Optional adapters use installed miners' existing task APIs. A lease owns one exact task object. */
public final class ExternalMiner {
    private static ExternalMiner owner;
    private static boolean callback;
    private final Minecraft client;
    private final java.util.function.BooleanSupplier allowed;
    private Driver driver;
    private Object task,world,connection;
    private BlockPos position;
    private long generation;
    private int started;
    private boolean confirmed,cancelling,acquiring;private String problem="";
    private List<Collection<Object>> acquisitionQueues=List.of();
    ExternalMiner(Minecraft client,java.util.function.BooleanSupplier allowed){this.client=client;this.allowed=allowed;}
    static boolean acting(){return callback;}
    boolean active(){return task!=null||acquiring;}
    String problem(){return problem;}
    void check(){if(driver==null)driver=resolve();}
    void confirmed(BlockPos pos,BlockState state){if(active()&&position.equals(pos)&&!state.is(Blocks.BEDROCK))confirmed=true;}
    boolean owns(long generation,long pos){return active()&&this.generation==generation&&position.asLong()==pos;}
    boolean step(long generation,BlockPos pos,int tick){
        check();
        if(active()){
            if(!owns(generation,pos.asLong())||world!=client.level||connection!=client.getConnection()){reset();throw new IllegalStateException("破基岩任务已变更");}
            if(driver.foreign(task)){reset();throw new IllegalStateException("破基岩已由外部任务接管");}
            if(tick-started>600){reset();throw new IllegalStateException("破基岩任务超时");}
            if(!driver.contains(task)&&confirmed&&driver.settled()){reset();return !active();}
            return false;
        }
        if(client.player==null||client.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SURVIVAL)throw new IllegalStateException("破基岩需要生存模式");
        if(!driver.idle())throw new IllegalStateException("破基岩模组已有任务");
        world=client.level;connection=client.getConnection();position=pos.immutable();this.generation=generation;started=tick;confirmed=false;cancelling=false;problem="";
        acquire(client.level,position);return false;
    }
    void acquire(ClientLevel world,BlockPos pos){
        this.world=world;position=pos;acquisitionQueues=List.copyOf(driver.queues());owner=this;acquiring=true;
        try{driver.begin();try{task=driver.add(world,pos);}finally{if(task==null)claimAcquired();}if(task==null)throw new IllegalStateException("破基岩模组未接受目标，请检查材料与模组设置");acquiring=false;acquisitionQueues=List.of();}
        catch(RuntimeException e){reset();throw e;}
    }
    private void claimAcquired(){for(var queue:acquisitionQueues)for(var candidate:queue)if(driver.matches(candidate,(ClientLevel)world,position)){if(task!=null&&task!=candidate)throw new IllegalStateException("外部施工返回多个目标任务");task=candidate;}}
    void reset(){if(driver!=null&&active())try{cancelling=true;if(acquiring&&task==null){claimAcquired();if(task==null&&acquisitionQueues.stream().anyMatch(q->!q.isEmpty()))throw new IllegalStateException("无法确认新增的外部施工任务");}driver.release(task);}catch(RuntimeException e){if(problem.isEmpty())BetterLitematicaClient.LOGGER.error("Could not release owned mining task",e);problem="外部施工未能停止，已阻止继续执行";return;}task=null;world=null;connection=null;position=null;confirmed=false;cancelling=false;acquiring=false;acquisitionQueues=List.of();problem="";if(owner==this){owner=null;callback=false;}}
    /** Invoked only around the installed manager's tick, so player input still pauses the printer. */
    public static boolean entering(Object manager){callback=false;if(owner==null||owner.driver.manager!=manager)return true;var lease=owner;try{if(lease.acquiring||lease.cancelling||lease.world!=lease.client.level||lease.connection!=lease.client.getConnection()||!lease.allowed.getAsBoolean()||lease.driver.foreign(lease.task)){lease.reset();return !lease.active();}lease.driver.beforeTick();callback=true;return true;}catch(RuntimeException e){lease.reset();return !lease.active();}}
    public static void leaving(Object manager){if(owner!=null&&owner.driver.manager==manager&&callback)try{owner.driver.afterTick();}catch(RuntimeException e){owner.reset();}callback=false;}

    private static Object call(Method method,Object receiver,Object... args){try{return method.invoke(receiver,args);}catch(ReflectiveOperationException e){throw new IllegalStateException("破基岩模组接口调用失败",e);}}
    private static Method method(Class<?> type,String name,Class<?>... args)throws ReflectiveOperationException{try{var method=type.getMethod(name,args);method.setAccessible(true);return method;}catch(NoSuchMethodException ignored){for(var current=type;current!=null;current=current.getSuperclass())try{var method=current.getDeclaredMethod(name,args);method.setAccessible(true);return method;}catch(NoSuchMethodException missing){}throw new NoSuchMethodException(type.getName()+"."+name);}}
    @SuppressWarnings("unchecked") private static Collection<Object> collection(Object object){if(!(object instanceof Collection<?> value)||value.size()>4096)throw new IllegalStateException("破基岩任务队列不可用");return (Collection<Object>)value;}
    static void removeOwned(Collection<?> values,Object task){values.removeIf(value->value==task);}
    abstract static class Driver {
        final Object manager;boolean wasRunning,changedRunning;
        Driver(Object manager){this.manager=manager;}
        abstract List<Collection<Object>> queues();
        abstract boolean extra();
        abstract void begin();
        abstract Object add(ClientLevel world,BlockPos pos);
        abstract boolean matches(Object candidate,ClientLevel world,BlockPos pos);
        abstract void release(Object owned);
        void beforeTick(){}void afterTick(){}boolean settled(){return true;}
        boolean contains(Object task){for(var queue:queues())for(var entry:queue)if(entry==task)return true;return false;}
        boolean foreign(Object task){if(extra())return true;for(var queue:queues())for(var entry:queue)if(entry!=task)return true;return false;}
        boolean idle(){return !extra()&&queues().stream().allMatch(Collection::isEmpty);}
        void remove(Object task){for(var queue:queues())removeOwned(queue,task);}
    }
    private static Driver resolve(){
        try{if(FabricLoader.getInstance().isModLoaded("blockminer"))return new BlockMiner();if(FabricLoader.getInstance().isModLoaded("bedrockminer"))return new BedrockMiner();}
        catch(ReflectiveOperationException|LinkageError e){throw new IllegalStateException("已安装的破基岩模组接口不兼容",e);}
        throw new IllegalStateException("需要安装 BedrockMiner 或 BlockMiner");
    }
    private static final class BedrockMiner extends Driver {
        final Method pending,active,cached,regions,add,running,setRunning,lookTask,lookReset;
        final Field taskPos,taskWorld,ranges;final Object config,look;
        BedrockMiner()throws ReflectiveOperationException{this(Class.forName("com.github.bunnyi116.bedrockminer.task.TaskManager"));}
        BedrockMiner(Class<?> type)throws ReflectiveOperationException{
            super(call(method(type,"getInstance"),null));pending=method(type,"getPendingBlockTasks");active=method(type,"getActiveBlockTasks");cached=method(type,"getCacheBlockTasks");regions=method(type,"getPendingRegionTasks");add=method(type,"addBlockTask",ClientLevel.class,BlockPos.class,Block.class);running=method(type,"isRunning");setRunning=method(type,"setRunning",boolean.class,boolean.class);
            var taskType=Class.forName("com.github.bunnyi116.bedrockminer.task.Task");taskPos=taskType.getField("pos");taskWorld=taskType.getField("world");
            var configType=Class.forName("com.github.bunnyi116.bedrockminer.config.Config");config=call(method(configType,"getInstance"),null);ranges=configType.getField("ranges");
            var lookType=Class.forName("com.github.bunnyi116.bedrockminer.task.TaskLookManager");look=lookType.getField("INSTANCE").get(null);lookTask=method(lookType,"getTask");lookReset=method(lookType,"reset");
        }
        List<Collection<Object>> queues(){return List.of(collection(call(pending,manager)),collection(call(active,manager)),collection(call(cached,manager)));}
        boolean extra(){try{return !collection(call(regions,manager)).isEmpty()||!collection(ranges.get(config)).isEmpty();}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
        void begin(){changedRunning=false;wasRunning=(boolean)call(running,manager);if(!wasRunning){changedRunning=true;call(setRunning,manager,true,false);}}
        Object add(ClientLevel world,BlockPos pos){call(add,manager,world,pos,Blocks.BEDROCK);for(Object candidate:collection(call(pending,manager)))try{if(pos.equals(taskPos.get(candidate))&&taskWorld.get(candidate)==world)return candidate;}catch(IllegalAccessException e){throw new IllegalStateException(e);}return null;}
        boolean matches(Object candidate,ClientLevel world,BlockPos pos){try{return pos.equals(taskPos.get(candidate))&&taskWorld.get(candidate)==world;}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
        void release(Object owned){boolean foreign=foreign(owned);remove(owned);if(owned!=null&&call(lookTask,look)==owned&&Minecraft.getInstance().player!=null)call(lookReset,look);if(!foreign&&changedRunning)call(setRunning,manager,false,false);}
    }
    private static final class BlockMiner extends Driver {
        final Method queue,positions,cleanup,add,running,toggle,paused,enabled,rotation,clearRotation,breaking,setBreaking;final Field taskPos;final Object mod;
        final Set<Long> ownedCleanup=new HashSet<>(),beforeCleanup=new HashSet<>();boolean changedControls;
        BlockMiner()throws ReflectiveOperationException{this(Class.forName("me.z7087.blockminer.task.TaskManager"),Class.forName("me.z7087.blockminer.BlockMinerMod"));}
        BlockMiner(Class<?> type,Class<?> mod)throws ReflectiveOperationException{
            super(call(method(mod,"getTaskManager"),call(method(mod,"getInstance"),null)));this.mod=call(method(mod,"getInstance"),null);queue=method(type,"taskQueue");positions=method(type,"posSet");cleanup=method(type,"positionsToClear");add=method(type,"handleAttackBlock",BlockPos.class);running=method(type,"isEnabled");toggle=method(type,"toggle",boolean.class,boolean.class);paused=method(type,"isPaused");enabled=method(type,"setEnabled0",boolean.class);taskPos=Class.forName("me.z7087.blockminer.task.Task").getField("targetPos");rotation=method(mod,"getRotationUtils");breaking=method(mod,"getBlockBreakUtils");clearRotation=method(call(rotation,this.mod).getClass(),"forceClearRotations");setBreaking=method(call(breaking,this.mod).getClass(),"setBreaking",boolean.class);
        }
        List<Collection<Object>> queues(){return List.of(collection(call(queue,manager)));}
        private it.unimi.dsi.fastutil.longs.LongIterator cleanupIterator(){try{Object storage=call(cleanup,manager);return (it.unimi.dsi.fastutil.longs.LongIterator)call(method(storage.getClass(),"iterator"),storage);}catch(ReflectiveOperationException|ClassCastException e){throw new IllegalStateException("BlockMiner 清理队列接口不兼容",e);}}
        private Set<Long> cleanupValues(){var values=new HashSet<Long>();var iterator=cleanupIterator();while(iterator.hasNext()){if(values.size()>=4096)throw new IllegalStateException("破基岩清理队列超限");values.add(iterator.nextLong());}return values;}
        boolean extra(){return !ownedCleanup.containsAll(cleanupValues());}
        boolean idle(){return super.idle()&&collection(call(positions,manager)).isEmpty()&&cleanupValues().isEmpty();}
        void beforeTick(){beforeCleanup.clear();beforeCleanup.addAll(cleanupValues());}
        void afterTick(){var now=cleanupValues();ownedCleanup.retainAll(now);now.removeAll(beforeCleanup);ownedCleanup.addAll(now);changedControls=true;}
        boolean settled(){return cleanupValues().isEmpty();}
        void begin(){changedRunning=false;if((boolean)call(paused,manager))throw new IllegalStateException("BlockMiner 已暂停");ownedCleanup.clear();beforeCleanup.clear();changedControls=false;wasRunning=(boolean)call(running,manager);if(!wasRunning){changedRunning=true;call(toggle,manager,false,false);}}
        Object add(ClientLevel world,BlockPos pos){if(!(boolean)call(add,manager,pos))return null;for(Object candidate:collection(call(queue,manager)))try{if(pos.equals(taskPos.get(candidate)))return candidate;}catch(IllegalAccessException e){throw new IllegalStateException(e);}return null;}
        boolean matches(Object candidate,ClientLevel world,BlockPos pos){try{return pos.equals(taskPos.get(candidate));}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
        void release(Object owned){boolean foreign=foreign(owned);remove(owned);if(owned!=null)try{collection(call(positions,manager)).remove(taskPos.get(owned));}catch(IllegalAccessException e){throw new IllegalStateException(e);}var iterator=cleanupIterator();while(iterator.hasNext())if(ownedCleanup.contains(iterator.nextLong()))iterator.remove();ownedCleanup.clear();beforeCleanup.clear();if(!foreign){if(changedControls){call(clearRotation,call(rotation,mod));call(setBreaking,call(breaking,mod),false);}if(changedRunning)call(enabled,manager,false);}changedControls=false;}
    }
}
