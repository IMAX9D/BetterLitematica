package dev.betterlitematica.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bridge established wire protocols through the registered codec without replacing another mod's payload type. */
final class LegacyPayloads {
    private static final int LIMIT=1_048_576;
    private static final Identifier CARPET=Identifier.fromNamespaceAndPath("carpet","hello");
    record Raw(CustomPacketPayload.Type<Raw> type,byte[] data) implements CustomPacketPayload {
        Raw {data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        @Override public CustomPacketPayload.Type<Raw> type(){return type;}
    }
    static void initialize(){
        // Carpet registers its own type when installed. Never claim its channel ahead of that initialization.
        if(!FabricLoader.getInstance().isModLoaded("carpet")&&PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(CARPET)==null){
            var id=new CustomPacketPayload.Type<Raw>(CARPET);
            ensure(PayloadTypeRegistryImpl.CLIENTBOUND_PLAY,CARPET);
            ClientPlayNetworking.registerGlobalReceiver(id,(payload,context)->AccuratePlacement.readCarpet(context.client().getConnection(),payload.data()));
        }
    }
    private static StreamCodec<RegistryFriendlyByteBuf,Raw> codec(CustomPacketPayload.Type<Raw> id){return new StreamCodec<>(){
        @Override public Raw decode(RegistryFriendlyByteBuf buffer){int size=buffer.readableBytes();if(size>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");byte[] bytes=new byte[size];buffer.readBytes(bytes);return new Raw(id,bytes);}
        @Override public void encode(RegistryFriendlyByteBuf buffer,Raw payload){if(payload.data.length>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");buffer.writeBytes(payload.data);}
    };}
    @SuppressWarnings({"unchecked","rawtypes"})
    private static StreamCodec<RegistryFriendlyByteBuf,CustomPacketPayload> ensure(PayloadTypeRegistryImpl<RegistryFriendlyByteBuf> registry,Identifier channel){
        var type=registry.get(channel);
        if(type==null){var id=new CustomPacketPayload.Type<Raw>(channel);registry.register(id,codec(id));type=registry.get(channel);}
        return (StreamCodec)type.codec();
    }
    static void send(Identifier channel,FriendlyByteBuf data){
        try{
            if(data.readableBytes()>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");
            var connection=Minecraft.getInstance().getConnection();if(connection==null)throw new IllegalStateException("未连接服务器");
            var buffer=new RegistryFriendlyByteBuf(data,connection.registryAccess());
            var payload=ensure(PayloadTypeRegistryImpl.SERVERBOUND_PLAY,channel).decode(buffer);
            if(buffer.isReadable())throw new IllegalArgumentException("服务器补给协议与当前客户端不匹配");
            ClientPlayNetworking.send(payload);
        }finally{data.release();}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static byte[] copy(ClientPacketListener connection,CustomPacketPayload payload){
        var type=PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(payload.type().id());if(type==null)return null;
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(256,LIMIT),connection.registryAccess());
        try{((StreamCodec)type.codec()).encode(buffer,payload);byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),bytes);return bytes;}
        catch(RuntimeException invalid){return null;}
        finally{buffer.release();}
    }
}
