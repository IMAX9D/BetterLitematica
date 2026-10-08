package dev.betterlitematica.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Bridge established wire protocols through the registered codec without replacing another mod's payload type. */
final class LegacyPayloads {
    private static final int LIMIT=1_048_576;
    private static final Identifier CARPET=new Identifier("carpet","hello");
    record Raw(CustomPayload.Id<Raw> type,byte[] data) implements CustomPayload {
        Raw {data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        @Override public CustomPayload.Id<Raw> getId(){return type;}
    }
    static void initialize(){
        // Carpet registers its own type when installed. Never claim its channel ahead of that initialization.
        if(!FabricLoader.getInstance().isModLoaded("carpet")&&PayloadTypeRegistryImpl.PLAY_S2C.get(CARPET)==null){
            var id=new CustomPayload.Id<Raw>(CARPET);
            ensure(PayloadTypeRegistryImpl.PLAY_S2C,CARPET);
            ClientPlayNetworking.registerGlobalReceiver(id,(payload,context)->AccuratePlacement.readCarpet(context.client().getNetworkHandler(),payload.data()));
        }
    }
    private static PacketCodec<RegistryByteBuf,Raw> codec(CustomPayload.Id<Raw> id){return new PacketCodec<>(){
        @Override public Raw decode(RegistryByteBuf buffer){int size=buffer.readableBytes();if(size>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");byte[] bytes=new byte[size];buffer.readBytes(bytes);return new Raw(id,bytes);}
        @Override public void encode(RegistryByteBuf buffer,Raw payload){if(payload.data.length>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");buffer.writeBytes(payload.data);}
    };}
    @SuppressWarnings({"unchecked","rawtypes"})
    private static PacketCodec<RegistryByteBuf,CustomPayload> ensure(PayloadTypeRegistryImpl<RegistryByteBuf> registry,Identifier channel){
        var type=registry.get(channel);
        if(type==null){var id=new CustomPayload.Id<Raw>(channel);registry.register(id,codec(id));type=registry.get(channel);}
        return (PacketCodec)type.codec();
    }
    static void send(Identifier channel,PacketByteBuf data){
        try{
            if(data.readableBytes()>LIMIT)throw new IllegalArgumentException("兼容数据包超过预算");
            var connection=MinecraftClient.getInstance().getNetworkHandler();if(connection==null)throw new IllegalStateException("未连接服务器");
            var buffer=new RegistryByteBuf(data,connection.getRegistryManager());
            var payload=ensure(PayloadTypeRegistryImpl.PLAY_C2S,channel).decode(buffer);
            if(buffer.isReadable())throw new IllegalArgumentException("服务器补给协议与当前客户端不匹配");
            ClientPlayNetworking.send(payload);
        }finally{data.release();}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static byte[] copy(ClientPlayNetworkHandler connection,CustomPayload payload){
        var type=PayloadTypeRegistryImpl.PLAY_S2C.get(payload.getId().id());if(type==null)return null;
        var buffer=new RegistryByteBuf(Unpooled.buffer(256,LIMIT),connection.getRegistryManager());
        try{((PacketCodec)type.codec()).encode(buffer,payload);byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(buffer.readerIndex(),bytes);return bytes;}
        catch(RuntimeException invalid){return null;}
        finally{buffer.release();}
    }
}
