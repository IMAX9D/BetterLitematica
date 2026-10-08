package dev.betterlitematica.fabric;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Independent V2/V3 wire codec. Local decoding is scoped to our own recorded use action. */
public final class AccuratePlacement {
    public enum Mode {AUTO,SLAB,V2,V3,NONE;
        String label(){return switch(this){case AUTO->"自动";case SLAB->"半砖";case V2->"V2";case V3->"V3";case NONE->"原生";};}}
    private static final Set<Property<?>> ALLOWED=Set.of(BlockStateProperties.INVERTED,BlockStateProperties.OPEN,BlockStateProperties.PERSISTENT,
        BlockStateProperties.AXIS,BlockStateProperties.HALF,BlockStateProperties.CHEST_TYPE,BlockStateProperties.MODE_COMPARATOR,BlockStateProperties.DOOR_HINGE,
        BlockStateProperties.SLAB_TYPE,BlockStateProperties.STAIRS_SHAPE,BlockStateProperties.ATTACH_FACE,BlockStateProperties.BITES,BlockStateProperties.DELAY,BlockStateProperties.NOTE,BlockStateProperties.ROTATION_16);
    private record Shape(Property<Direction> facing,List<Property<?>> properties){}
    private static final Map<Block,Shape> shapes=new ConcurrentHashMap<>();
    @SuppressWarnings("rawtypes") record PropertyValues(List<Comparable> values,int bits){}
    private static final Map<Property<?>,PropertyValues> propertyValues=new ConcurrentHashMap<>();
    private record Scope(Mode mode,BlockPos target,BlockHitResult wire){}
    private static volatile net.minecraft.client.multiplayer.ClientPacketListener carpetConnection;
    private static boolean carpetAccurate;
    private static final Identifier CARPET=Identifier.fromNamespaceAndPath("carpet","hello");
    public static void payload(net.minecraft.client.multiplayer.ClientPacketListener connection,net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet){
        var payload=packet.payload();if(!payload.type().id().equals(CARPET)||payload instanceof LegacyPayloads.Raw)return;
        byte[] bytes=LegacyPayloads.copy(connection,payload);
        if(bytes!=null)Minecraft.getInstance().execute(()->readCarpet(connection,bytes));
    }
    static void readCarpet(net.minecraft.client.multiplayer.ClientPacketListener connection,byte[] bytes){
        var client=Minecraft.getInstance();if(connection!=client.getConnection())return;
        var data=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try{int type=data.readVarInt();if(type==69){data.readUtf(64);if(carpetConnection!=connection){carpetConnection=connection;carpetAccurate=false;
                if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet")){var reply=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());reply.writeVarInt(420).writeUtf("BetterLitematica");LegacyPayloads.send(CARPET,reply);}
            }}else if(type==1&&carpetConnection==connection){var tag=data.readNbt();Boolean enabled=carpetRule(tag);if(enabled!=null)carpetAccurate=enabled;}}
        catch(RuntimeException ignored){/* An unknown protocol never enables encoded placement. */}
        finally{data.release();}
    }
    static Boolean carpetRule(net.minecraft.nbt.CompoundTag data){
        if(data==null||!NbtAccess.contains(data,"Rules",net.minecraft.nbt.Tag.TAG_COMPOUND))return null;var rules=data.getCompoundOrEmpty("Rules");if(rules.size()>4096)return null;
        for(String key:rules.keySet()){var entry=rules.getCompoundOrEmpty(key);String name=NbtAccess.contains(entry,"Rule",net.minecraft.nbt.Tag.TAG_STRING)?entry.getStringOr("Rule",""):key;if(name.equals("accurateBlockPlacement"))return "true".equals(entry.getStringOr("Value",""));}return null;
    }
    private static final ThreadLocal<Scope> active=new ThreadLocal<>();
    private record Key(UUID player,String dimension,long clicked,int side,InteractionHand hand,int x,int y,int z){}
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
    static Mode resolve(Minecraft client,Mode mode){return mode==Mode.AUTO?(client.getSingleplayerServer()!=null?Mode.V3:client.getConnection()!=null&&client.getConnection()==carpetConnection&&carpetAccurate?Mode.V2:Mode.SLAB):mode;}
    private static Shape shape(BlockState state){return shapes.computeIfAbsent(state.getBlock(),block->{
        Property<Direction> direction=null;var properties=new ArrayList<Property<?>>();
        for(var property:block.getStateDefinition().getProperties()){if(direction==null&&property.getValueClass()==Direction.class)direction=(Property<Direction>)(Property<?>)property;if(ALLOWED.contains(property))properties.add(property);}
        properties.sort(Comparator.comparing(Property::getName));return new Shape(direction,List.copyOf(properties));});}
    @SuppressWarnings({"rawtypes","unchecked"}) static PropertyValues propertyValues(Property<?> property){return propertyValues.computeIfAbsent(property,key->{var values=new ArrayList<Comparable>((Collection)key.getPossibleValues());values.sort(Comparator.naturalOrder());return new PropertyValues(List.copyOf(values),bits(values.size()));});}
    private static int bits(int count){return 32-Integer.numberOfLeadingZeros(count-1);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static BlockState set(BlockState state,Property property,Comparable value){return state.setValue(property,value);}
    @SuppressWarnings({"rawtypes","unchecked"}) static BlockHitResult encode(Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        if(mode==Mode.AUTO||mode==Mode.NONE)return hit;Vec3 p=hit.getLocation();double y=p.y;int packed=0;boolean data=false;var shape=shape(wanted);
        if(mode==Mode.V2||mode==Mode.SLAB){
            if(wanted.hasProperty(BlockStateProperties.SLAB_TYPE))y=target.getY()+(wanted.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.TOP?.99:0);
            else if(wanted.hasProperty(BlockStateProperties.HALF))y=target.getY()+(wanted.getValue(BlockStateProperties.HALF)==Half.TOP?.99:0);
        }
        if(mode==Mode.V2){
            if(shape.facing!=null){packed=wanted.getValue(shape.facing).get3DDataValue();data=true;}
            else if(wanted.hasProperty(BlockStateProperties.AXIS)){packed=wanted.getValue(BlockStateProperties.AXIS).ordinal();data=true;}
            if(wanted.getBlock() instanceof RepeaterBlock)packed+=16*wanted.getValue(BlockStateProperties.DELAY);
            else if(wanted.hasProperty(BlockStateProperties.MODE_COMPARATOR)&&wanted.getValue(BlockStateProperties.MODE_COMPARATOR)==ComparatorMode.SUBTRACT
                ||wanted.hasProperty(BlockStateProperties.HALF)&&wanted.getValue(BlockStateProperties.HALF)==Half.TOP
                ||wanted.hasProperty(BlockStateProperties.SLAB_TYPE)&&wanted.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.TOP)packed+=16;
            data|=packed!=0;packed*=2;
        }else if(mode==Mode.V3){
            int shift=1;if(shape.facing!=null&&shape.facing!=BlockStateProperties.VERTICAL_DIRECTION){packed=wanted.getValue(shape.facing).get3DDataValue()<<shift;shift+=3;data=true;}
            for(Property property:shape.properties){var table=propertyValues(property);int width=table.bits();if(shift+width>22)throw new IllegalArgumentException("放置状态超出协议容量");packed|=table.values().indexOf(wanted.getValue(property))<<shift;shift+=width;data=true;}
        }
        // Keep the encoded fraction strictly inside the target cell, including hits on its east face.
        double x=data?encodedX(target.getX(),hit.getBlockPos().getX(),p.x,packed):p.x;
        return new BlockHitResult(new Vec3(x,y,p.z),hit.getDirection(),hit.getBlockPos(),hit.isInside());
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
            else if(base.hasProperty(BlockStateProperties.AXIS))result=result.setValue(BlockStateProperties.AXIS,Direction.Axis.values()[((raw>>>1)&3)%3]);
            int value=raw>>>5;
            if(base.getBlock() instanceof RepeaterBlock&&value>=1&&value<=4)result=result.setValue(BlockStateProperties.DELAY,value);
            else if(base.getBlock() instanceof ComparatorBlock)result=result.setValue(BlockStateProperties.MODE_COMPARATOR,value>0?ComparatorMode.SUBTRACT:ComparatorMode.COMPARE);
            else if(base.hasProperty(BlockStateProperties.HALF))result=result.setValue(BlockStateProperties.HALF,value>0?Half.TOP:Half.BOTTOM);
        }else{
            int shift=1;if(shape.facing!=null&&shape.facing!=BlockStateProperties.VERTICAL_DIRECTION){result=direction(result,shape.facing,(raw>>>1)&7,playerFacing);shift+=3;}
            for(Property property:shape.properties){var table=propertyValues(property);var values=table.values();int width=table.bits(),index=(raw>>>shift)&((1<<width)-1);shift+=width;
                if(index<values.size()){var value=values.get(index);if(property!=BlockStateProperties.SLAB_TYPE||value!=SlabType.DOUBLE)result=set(result,property,value);}}
        }
        return result;
    }
    private static BlockState direction(BlockState state,Property<Direction> property,int value,Direction playerFacing){
        if(value>6)return state;Direction facing=value==6?state.getValue(property).getOpposite():Direction.from3DDataValue(value);
        if(!property.getPossibleValues().contains(facing))facing=playerFacing.getOpposite();return property.getPossibleValues().contains(facing)?state.setValue(property,facing):state;
    }
    static BlockState predict(Mode mode,BlockPlaceContext context,BlockState wanted,BlockHitResult hit){
        if(!(context.getItemInHand().getItem() instanceof BlockItem item))return null;
        var wire=encode(mode,context.getClickedPos(),wanted,hit);var encoded=new BlockPlaceContext(context.getPlayer(),context.getHand(),context.getItemInHand(),wire);
        var adjusted=item.updatePlacementContext(encoded);if(adjusted==null||!adjusted.getClickedPos().equals(context.getClickedPos()))return null;
        var base=item.getPlacementState(adjusted);
        var state=valid(adjusted,decode(mode,base,context.getClickedPos(),wire.getLocation().x,context.getHorizontalDirection()));
        var finalState=state==null?null:StateResolver1201.itemState(state,context.getItemInHand());
        return validatePlacement(item,adjusted,base,finalState);
    }
    /** Vanilla's exact BlockItem already validated its unchanged getPlacementState result. */
    public static BlockState validatePlacement(BlockItem item,BlockPlaceContext context,BlockState base,BlockState modified){
        if(modified==null)return null;
        if(base==modified&&item.getClass()==BlockItem.class)return modified;
        return item.canPlace(context,modified)?modified:null;
    }
    private static BlockState valid(BlockPlaceContext context,BlockState state){
        if(state!=null&&state.getBlock() instanceof BedBlock&&!context.getLevel().getBlockState(context.getClickedPos().relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING))).canBeReplaced(context))return null;
        return state;
    }
    public static BlockState apply(BlockPlaceContext context,BlockState base){
        Scope scope=active.get();if(scope==null)return base;if(!context.getClickedPos().equals(scope.target))return null;
        return valid(context,decode(scope.mode,base,scope.target,scope.wire.getLocation().x,context.getHorizontalDirection()));
    }
    private static <T>T scoped(Scope value,Supplier<T> action){Scope previous=active.get();active.set(value);try{return action.get();}finally{if(previous==null)active.remove();else active.set(previous);}}
    static boolean requiresLocal(boolean integrated,Mode mode,BlockPos target,BlockHitResult wire){return integrated&&(mode==Mode.V2||mode==Mode.V3)&&wire.getLocation().x-target.getX()>=2;}
    static boolean localCapacity(boolean integrated,Mode mode,BlockPos target,BlockHitResult wire,PendingSlots<?,?> slots,long now){return !requiresLocal(integrated,mode,target,wire)||slots.available(now);}
    static boolean canUse(Minecraft client,Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        if(client.getSingleplayerServer()==null||mode!=Mode.V2&&mode!=Mode.V3)return true;
        return localCapacity(true,mode,target,encode(mode,target,wanted,hit),local,System.nanoTime());
    }
    static InteractionResult use(Minecraft client,Mode mode,BlockPos target,BlockState wanted,BlockHitResult hit){
        BlockHitResult wire=encode(mode,target,wanted,hit);var scope=new Scope(mode,target.immutable(),wire);
        if(requiresLocal(client.getSingleplayerServer()!=null,mode,target,wire)){
            long now=System.nanoTime();var key=key(client.player,InteractionHand.MAIN_HAND,wire);
            if(!local.offer(key,new Pending(client.getSingleplayerServer(),scope,hit),now+2_000_000_000L,now))return InteractionResult.FAIL;
        }
        return scoped(scope,()->client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,wire));
    }
    private static Key key(Player player,InteractionHand hand,BlockHitResult hit){var p=hit.getLocation();var b=hit.getBlockPos();return new Key(player.getUUID(),player.level().dimension().identifier().toString(),b.asLong(),hit.getDirection().get3DDataValue(),hand,Float.floatToIntBits((float)(p.x-b.getX())),Float.floatToIntBits((float)(p.y-b.getY())),Float.floatToIntBits((float)(p.z-b.getZ())));}
    private static Pending pending(ServerPlayer player,InteractionHand hand,BlockHitResult hit){var p=local.get(key(player,hand,hit),System.nanoTime());return p!=null&&p.server==player.level().getServer()?p:null;}
    public static Vec3 physical(ServerPlayer player,InteractionHand hand,BlockHitResult hit,Vec3 original){var p=pending(player,hand,hit);return p==null?original:p.physical.getLocation();}
    public static InteractionResult serverUse(ServerPlayer player,InteractionHand hand,BlockHitResult hit,Supplier<InteractionResult> action){var p=pending(player,hand,hit);if(p==null)return action.get();local.remove(key(player,hand,hit),p);return scoped(p.scope,action);}
    static void disconnect(){local.clear();active.remove();carpetConnection=null;carpetAccurate=false;}
}
