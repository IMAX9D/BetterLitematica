import com.retrievepanel.printer.TrashPacket;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.class_2540;

/** Round-trips our packet through the actual installed TrashSlot codec. No server connection. */
public class ProtocolChecks {
    public static void main(String[] args)throws Exception{
        Class<?> message=Class.forName("net.blay09.mods.trashslot.network.MessageDeleteFromSlot");
        var decode=message.getMethod("decode",class_2540.class);var encode=message.getMethod("encode",message,class_2540.class);
        for(int id:new int[]{0,9,36,127,128,255,511}){
            var ours=TrashPacket.payload(id);var encoded=PacketByteBufs.create();
            try{Object decoded=decode.invoke(null,ours);if(ours.readableBytes()!=0)throw new AssertionError("extra data");encode.invoke(null,decoded,encoded);
                if(encoded.method_10816()!=id||encoded.readBoolean()||encoded.readableBytes()!=0)throw new AssertionError("not exact single-slot false packet");
            }finally{ours.release();encoded.release();}
        }
        for(int invalid:new int[]{-1,512}){try{TrashPacket.payload(invalid);throw new AssertionError("invalid disposal slot accepted");}catch(IllegalArgumentException expected){}}
        System.out.println("PASS 9 actual TrashSlot codec checks: no clear-trash or delete-all packet");
    }
}
