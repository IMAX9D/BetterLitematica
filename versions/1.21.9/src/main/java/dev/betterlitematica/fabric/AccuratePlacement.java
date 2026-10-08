package dev.betterlitematica.fabric;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

/** Independent V2/V3 wire codec. Local decoding is scoped to our own recorded use action. */
public final class AccuratePlacement {
    public enum Mode {AUTO,SLAB,V2,V3,NONE;
        String label(){return switch(this){case AUTO->"自动";case SLAB->"半砖";case V2->"V2";case V3->"V3";case NONE->"原生";};}}
    private static final Set<Property<?>> ALLOWED=Set.of(Properties.INVERTED,Properties.OPEN,Properties.PERSISTENT,
        Properties.AXIS,Properties.BLOCK_HALF,Properties.CHEST_TYPE,Properties.COMPARATOR_MODE,Properties.DOOR_HINGE,
        Properties.SLAB_TYPE,Properties.STAIR_SHAPE,Properties.BLOCK_FACE,Properties.BITES,Properties.DELAY,Properties.NOTE,Properties.ROTATION);
    private record Shape(EnumProperty<Direction> facing,List<Property<?>> properties){}
    private static final Map<Block,Shape> shapes=new ConcurrentHashMap<>();
    @SuppressWarnings("rawtypes") record PropertyValues(List<Comparable> values,int bits){}
    private static final Map<Property<?>,PropertyValues> propertyValues=new ConcurrentHashMap<>();
    private record Scope(Mode mode,BlockPos target,BlockHitResult wire){}
    private static volatile net.minecraft.client.network.ClientPlayNetworkHandler carpetConnection;
    private static boolean carpetAccurate;
    private static final Identifier CARPET=Identifier.of("carpet","hello");
    public static void payload(net.minecraft.client.network.ClientPlayNetworkHandler connection,net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket packet){
        var payload=packet.payload();if(!payload.getId().id().equals(CARPET)||payload instanceof LegacyPayloads.Raw)return;
        byte[] bytes=LegacyPayloads.copy(connection,payload);
        if(bytes!=null)MinecraftClient.getInstance().execute(()->readCarpet(connection,bytes));
    }
    static void readCarpet(net.minecraft.client.network.ClientPlayNetworkHandler connection,byte[] bytes){
        var client=MinecraftClient.getInstance();if(connection!=client.getNetworkHandler())return;
        var data=new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try{int type=data.readVarInt();if(type==69){data.readString(64);if(carpetConnection!=connection){carpetConnection=connection;carpetAccurate=false;
                if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet")){var reply=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();reply.writeVarInt(420).writeString("BetterLitematica");LegacyPayloads.send(CARPET,reply);}
            }}else if(type==1&&carpetConnection==connection){var tag=data.readNbt();Boolean enabled=carpetRule(tag);if(enabled!=null)carpetAccurate=enabled;}}
        catch(RuntimeException ignored){/* An unknown protocol never enables encoded placement. */}
        finally{data.release();}
    }
    static Boolean carpetRule(net.minecraft.nbt.NbtCompound data){
        if(data==null||!NbtAccess.contains(data,"Rules",net.minecraft.nbt.NbtElement.COMPOUND_TYPE))return null;var rules=data.getCompoundOrEmpty("Rules");if(rules.getSize()>4096)return null;
        for(String key:rules.getKeys()){var entry=rules.getCompoundOrEmpty(key);String name=NbtAccess.contains(entry,"Rule",net.minecraft.nbt.NbtElement.STRING_TYPE)?entry.getString("Rule",""):key;if(name.equals("accurateBlockPlacement"))return "true".equals(entry.getString("Value",""));}return null;
    }
    private static final ThreadLocal<Scope> active=new ThreadLocal<>();
    private record Key(UUID player,String dimension,long clicked,int side,Hand hand,int x,int y,int z){}
    private record Pending(MinecraftServer server,Scope scope,BlockHitResult physical){}
    /** The same bounded store is exercised headlessly and used by both client and server threads. */
    static final class PendingSlots<K,V> {
        private record Entry<V>(V value,long deadline){}
        private final Map<K,Entry<V>> entries=new HashMap<>();
        private long nextExpiry=Long.MAX_VALUE;
        synchronized boolean available(long now){expire(now);return entries.size()<128;}
        synchronized boolean offer(K key,V value,long deadline,long now){
            if(deadline<=now||!available(now))return false;
            entries.put(key,new Entry<>(value,deadline));nextExpiry=Math.min(nextExpiry,deadline);return true;
        }
        synchronized V get(K key,long now){expire(now);var entry=entries.get(key);return entry==null?null:entry.value();}
        synchronized boolean remove(K key,V value){var entry=entries.get(key);return entry!=null&&entry.value()==value&&entries.remove(key,entry);}
        synchronized void clear(){entries.clear();nextExpiry=Long.MAX_VALUE;}
        private void expire(long now){
            if(now<nextExpiry)return;nextExpiry=Long.MAX_VALUE;
            for(var it=entries.values().iterator();it.hasNext();){var entry=it.next();if(entry.deadline()<=now)it.remove();else nextExpiry=Math.min(nextExpiry,entry.deadline());}
        }
    }
    private static final PendingSlots<Key,Pending> local=new PendingSlots<>();
    private AccuratePlacement(){}
    static Mode resolve(MinecraftClient client,Mode mode){return mode==Mode.AUTO?(client.getServer()!=null?Mode.V3:client.getNetworkHandler()!=null&&client.getNetworkHandler()==carpetConnection&&carpetAccurate?Mode.V2:Mode.SLAB):mode;}
    private static Shape shape(BlockState state){return shapes.computeIfAbsent(state.getBlock(),block->{
        EnumProperty<Direction> direction=null;var properties=new ArrayList<Property<?>>();
        for(var property:block.getStateManager().getProperties()){if(direction==null&&property instanceof EnumProperty<?> facing&&facing.getType()==Direction.class)direction=(EnumProperty<Direction>)facing;if(ALLOWED.contains(property))properties.add(property);}
        properties.sort(Comparator.comparing(Property::getName));return new Shape(direction,List.copyOf(properties));});}
    @SuppressWarnings({"rawtypes","unchecked"}) static PropertyValues propertyValues(Property<?> property){return propertyValues.computeIfAbsent(property,key->{var values=new ArrayList<Comparable>((Collection)key.getValues());values.sort(Comparator.naturalOrder());return new PropertyValues(List.copyOf(values),bits(values.size()));});}
    private static int bits(int count){return 32-Integer.numberOfLeadingZeros(count-1);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static BlockState set(BlockState state,Property property,Comparable value){return state.with(property,value);}
    @SuppressWarnings({"rawtypes","unchecked"}) static BlockHitResult encode(Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        if(mode==Mode.AUTO||mode==Mode.NONE)return hit;Vec3d p=hit.getPos();double y=p.y;int packed=0;boolean data=false;var shape=shape(wanted);
        if(mode==Mode.V2||mode==Mode.SLAB){
            if(wanted.contains(Properties.SLAB_TYPE))y=target.getY()+(wanted.get(Properties.SLAB_TYPE)==SlabType.TOP?.99:0);
            else if(wanted.contains(Properties.BLOCK_HALF))y=target.getY()+(wanted.get(Properties.BLOCK_HALF)==BlockHalf.TOP?.99:0);
        }
        if(mode==Mode.V2){
            if(shape.facing!=null){packed=wanted.get(shape.facing).getIndex();data=true;}
            else if(wanted.contains(Properties.AXIS)){packed=wanted.get(Properties.AXIS).ordinal();data=true;}
            if(wanted.getBlock() instanceof RepeaterBlock)packed+=16*wanted.get(Properties.DELAY);
            else if(wanted.contains(Properties.COMPARATOR_MODE)&&wanted.get(Properties.COMPARATOR_MODE)==ComparatorMode.SUBTRACT
                ||wanted.contains(Properties.BLOCK_HALF)&&wanted.get(Properties.BLOCK_HALF)==BlockHalf.TOP
                ||wanted.contains(Properties.SLAB_TYPE)&&wanted.get(Properties.SLAB_TYPE)==SlabType.TOP)packed+=16;
            data|=packed!=0;packed*=2;
        }else if(mode==Mode.V3){
            int shift=1;if(shape.facing!=null&&shape.facing!=Properties.VERTICAL_DIRECTION){packed=wanted.get(shape.facing).getIndex()<<shift;shift+=3;data=true;}
            for(Property property:shape.properties){var table=propertyValues(property);int width=table.bits();if(shift+width>22)throw new IllegalArgumentException("放置状态超出协议容量");packed|=table.values().indexOf(wanted.get(property))<<shift;shift+=width;data=true;}
        }
        // Keep the encoded fraction strictly inside the target cell, including hits on its east face.
        double x=data?encodedX(target.getX(),hit.getBlockPos().getX(),p.x,packed):p.x;
        return new BlockHitResult(new Vec3d(x,y,p.z),hit.getSide(),hit.getBlockPos(),hit.isInsideBlock());
    }
    static double encodedX(int targetX,int clickedX,double physicalX,int packed){
        double x=targetX+Math.max(.001,Math.min(.999,physicalX-targetX))+2+packed;
        // The packet stores a float relative to the clicked block, not a double world coordinate.
        // At the largest supported payload a fraction near one can round into the next state.
        double received=clickedX+(double)(float)(x-clickedX);
        if(Math.floor(received-targetX)-2!=packed)x=targetX+2.5+packed;
        return x;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) static BlockState decode(Mode mode,BlockState base,BlockPos target,double hitX,Direction playerFacing){
        if(base==null||mode!=Mode.V2&&mode!=Mode.V3)return base;
        double relative=hitX-target.getX();if(!Double.isFinite(relative)||relative<2||relative>=4_194_306)return base;
        int raw=(int)Math.floor(relative)-2;var shape=shape(base);BlockState result=base;
        if(mode==Mode.V2){
            if(shape.facing!=null)result=direction(result,shape.facing,(raw&15)>>>1,playerFacing);
            else if(base.contains(Properties.AXIS))result=result.with(Properties.AXIS,Direction.Axis.values()[((raw>>>1)&3)%3]);
            int value=raw>>>5;
            if(base.getBlock() instanceof RepeaterBlock&&value>=1&&value<=4)result=result.with(Properties.DELAY,value);
            else if(base.getBlock() instanceof ComparatorBlock)result=result.with(Properties.COMPARATOR_MODE,value>0?ComparatorMode.SUBTRACT:ComparatorMode.COMPARE);
            else if(base.contains(Properties.BLOCK_HALF))result=result.with(Properties.BLOCK_HALF,value>0?BlockHalf.TOP:BlockHalf.BOTTOM);
        }else{
            int shift=1;if(shape.facing!=null&&shape.facing!=Properties.VERTICAL_DIRECTION){result=direction(result,shape.facing,(raw>>>1)&7,playerFacing);shift+=3;}
            for(Property property:shape.properties){var table=propertyValues(property);var values=table.values();int width=table.bits(),index=(raw>>>shift)&((1<<width)-1);shift+=width;
                if(index<values.size()){var value=values.get(index);if(property!=Properties.SLAB_TYPE||value!=SlabType.DOUBLE)result=set(result,property,value);}}
        }
        return result;
    }
    private static BlockState direction(BlockState state,EnumProperty<Direction> property,int value,Direction playerFacing){
        if(value>6)return state;Direction facing=value==6?state.get(property).getOpposite():Direction.byIndex(value);
        if(!property.getValues().contains(facing))facing=playerFacing.getOpposite();return property.getValues().contains(facing)?state.with(property,facing):state;
    }
    static BlockState predict(Mode mode,ItemPlacementContext context,BlockState wanted,BlockHitResult hit){
        if(!(context.getStack().getItem() instanceof BlockItem item))return null;
        var wire=encode(mode,context.getBlockPos(),wanted,hit);var encoded=new ItemPlacementContext(context.getPlayer(),context.getHand(),context.getStack(),wire);
        var adjusted=item.getPlacementContext(encoded);if(adjusted==null||!adjusted.getBlockPos().equals(context.getBlockPos()))return null;
        var base=item.getPlacementState(adjusted);
        var state=valid(adjusted,decode(mode,base,context.getBlockPos(),wire.getPos().x,context.getHorizontalPlayerFacing()));
        var finalState=state==null?null:StateResolver1201.itemState(state,context.getStack());
        return validatePlacement(item,adjusted,base,finalState);
    }
    /** Vanilla's exact BlockItem already validated its unchanged getPlacementState result. */
    public static BlockState validatePlacement(BlockItem item,ItemPlacementContext context,BlockState base,BlockState modified){
        if(modified==null)return null;
        if(base==modified&&item.getClass()==BlockItem.class)return modified;
        return item.canPlace(context,modified)?modified:null;
    }
    private static BlockState valid(ItemPlacementContext context,BlockState state){
        if(state!=null&&state.getBlock() instanceof BedBlock&&!context.getWorld().getBlockState(context.getBlockPos().offset(state.get(Properties.HORIZONTAL_FACING))).canReplace(context))return null;
        return state;
    }
    public static BlockState apply(ItemPlacementContext context,BlockState base){
        Scope scope=active.get();if(scope==null)return base;if(!context.getBlockPos().equals(scope.target))return null;
        return valid(context,decode(scope.mode,base,scope.target,scope.wire.getPos().x,context.getHorizontalPlayerFacing()));
    }
    private static <T>T scoped(Scope value,Supplier<T> action){Scope previous=active.get();active.set(value);try{return action.get();}finally{if(previous==null)active.remove();else active.set(previous);}}
    static boolean requiresLocal(boolean integrated,Mode mode,BlockPos target,BlockHitResult wire){return integrated&&(mode==Mode.V2||mode==Mode.V3)&&wire.getPos().x-target.getX()>=2;}
    static boolean localCapacity(boolean integrated,Mode mode,BlockPos target,BlockHitResult wire,PendingSlots<?,?> slots,long now){return !requiresLocal(integrated,mode,target,wire)||slots.available(now);}
    static boolean canUse(MinecraftClient client,Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        if(client.getServer()==null||mode!=Mode.V2&&mode!=Mode.V3)return true;
        return localCapacity(true,mode,target,encode(mode,target,wanted,hit),local,System.nanoTime());
    }
    static ActionResult use(MinecraftClient client,Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        BlockHitResult wire=encode(mode,target,wanted,hit);var scope=new Scope(mode,target.toImmutable(),wire);
        if(requiresLocal(client.getServer()!=null,mode,target,wire)){
            long now=System.nanoTime();var key=key(client.player,Hand.MAIN_HAND,wire);
            if(!local.offer(key,new Pending(client.getServer(),scope,hit),now+2_000_000_000L,now))return ActionResult.FAIL;
        }
        return scoped(scope,()->client.interactionManager.interactBlock(client.player,Hand.MAIN_HAND,wire));
    }
    private static Key key(PlayerEntity player,Hand hand,BlockHitResult hit){var p=hit.getPos();var b=hit.getBlockPos();return new Key(player.getUuid(),player.getEntityWorld().getRegistryKey().getValue().toString(),b.asLong(),hit.getSide().getIndex(),hand,Float.floatToIntBits((float)(p.x-b.getX())),Float.floatToIntBits((float)(p.y-b.getY())),Float.floatToIntBits((float)(p.z-b.getZ())));}
    private static Pending pending(ServerPlayerEntity player,Hand hand,BlockHitResult hit){var p=local.get(key(player,hand,hit),System.nanoTime());return p!=null&&p.server==player.getEntityWorld().getServer()?p:null;}
    public static Vec3d physical(ServerPlayerEntity player,Hand hand,BlockHitResult hit,Vec3d original){var p=pending(player,hand,hit);return p==null?original:p.physical.getPos();}
    public static ActionResult serverUse(ServerPlayerEntity player,Hand hand,BlockHitResult hit,Supplier<ActionResult> action){var p=pending(player,hand,hit);if(p==null)return action.get();local.remove(key(player,hand,hit),p);return scoped(p.scope,action);}
    static void disconnect(){local.clear();active.remove();carpetConnection=null;carpetAccurate=false;}
}
