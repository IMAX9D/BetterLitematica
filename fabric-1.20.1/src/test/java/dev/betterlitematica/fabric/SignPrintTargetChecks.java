package dev.betterlitematica.fabric;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.nbt.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

/** The actual vanilla sign reader and packet decoder consume detached printer payloads. */
final class SignPrintTargetChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    static int run(){
        checks=0;var source=new NbtCompound();
        source.put("front_text",side("{\"text\":\"正面\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/say unsafe\"},\"extra\":[{\"text\":\"附文\"}]}"));
        source.put("back_text",side(Text.Serializer.toJson(Text.literal("背面"))));
        source.getCompound("front_text").putString("color","red");source.getCompound("front_text").putBoolean("has_glowing_text",true);
        var filtered=new NbtList();for(int i=0;i<4;i++)filtered.add(NbtString.of(Text.Serializer.toJson(Text.literal(i==0?"过滤文字":""))));
        source.getCompound("front_text").put("filtered_messages",filtered);
        source.putBoolean("is_waxed",true);source.putInt("x",123);source.putInt("y",-39);source.putInt("z",456);source.putString("id","minecraft:sign");source.putString("Command","/say unrelated");
        var before=source.copy();
        for(Block block:List.of(Blocks.OAK_SIGN,Blocks.OAK_WALL_SIGN,Blocks.OAK_HANGING_SIGN,Blocks.OAK_WALL_HANGING_SIGN)){
            var state=block.getDefaultState();check(SignPrintTarget.supported(state),"Standing, wall and hanging signs share the target contract");
            var target=SignPrintTarget.from(state,source);check(target.front().equals(List.of("正面附文","","",""))&&target.back().get(0).equals("背面"),"Both sides flatten visible literal content");
            var tag=BlockItem.getBlockEntityNbt(target.placementStack());check(tag!=null&&tag.getKeys().equals(Set.of("front_text","back_text","is_waxed")),"Creative payload omits source coordinates, identity and unrelated fields");
            var be=(SignBlockEntity)((BlockEntityProvider)block).createBlockEntity(BlockPos.ORIGIN,state);be.readNbt(tag);
            check(be.getFrontText().getMessage(0,false).getString().equals("正面附文")&&be.getBackText().getMessage(0,false).getString().equals("背面"),"Vanilla entity restores both safe text sides");
            check(be.getFrontText().getMessage(0,false).getStyle().getClickEvent()==null&&!be.getFrontText().getMessage(0,false).getStyle().isBold(),"Rebuilt literal payload contains no active or arbitrary style");
            check(be.getFrontText().getMessage(0,true).getString().equals("过滤文字"),"Filtered text is independently preserved and sanitized");
            check(be.getFrontText().getColor()==DyeColor.RED&&be.getFrontText().isGlowing()&&be.isWaxed(),"Vanilla color, glow and wax are preserved");
            check(be.copyItemDataRequiresOperator(),"Creative sign NBT requires vanilla operator permission, including hanging signs");
            var lines=target.lines(true);lines[0]="changed";tag.putBoolean("is_waxed",false);var exposed=target.itemData();exposed.putBoolean("is_waxed",false);
            check(target.lines(true)[0].equals("正面附文")&&target.itemData().getBoolean("is_waxed"),"Public snapshots and item tags cannot mutate retained target data");
        }
        check(source.equals(before),"Reading and rendering creative item data preserves the original source NBT");
        var state=Blocks.OAK_SIGN.getDefaultState();var empty=SignPrintTarget.from(state,null);
        check(empty.front().equals(Collections.nCopies(4,""))&&empty.back().equals(Collections.nCopies(4,"")),"Absent sign NBT means four genuinely empty lines per side");
        check(!empty.nonDefault()&&empty.matches(new SignBlockEntity(BlockPos.ORIGIN,state)),"Genuinely default signs await their authorized editor instead of retiring early");
        var vanillaEmpty=new SignBlockEntity(BlockPos.ORIGIN,state).createNbt();var mergedEmpty=vanillaEmpty.copy();mergedEmpty.copyFrom(empty.itemData());
        check(mergedEmpty.equals(vanillaEmpty),"Default printer sign payload is identical to vanilla initial NBT, so the server still opens its editor");
        for(String raw:List.of("","原文"))for(String filteredText:List.of("",raw,"过滤内容")){
            var tag=new NbtCompound();var face=side(Text.Serializer.toJson(Text.literal(raw)));var values=new NbtList();
            for(int i=0;i<4;i++)values.add(NbtString.of(Text.Serializer.toJson(Text.literal(i==0?filteredText:""))));
            face.put("filtered_messages",values);tag.put("front_text",face);
            var target=SignPrintTarget.from(state,tag);var vanilla=new SignBlockEntity(BlockPos.ORIGIN,state);var initial=vanilla.createNbt();var merged=initial.copy();merged.copyFrom(target.itemData());
            check(!merged.equals(initial)&&target.nonDefault(),"Explicit filtered fields change vanilla placement NBT even when all lines are empty or equal");
            vanilla.readNbt(merged);
            check(vanilla.getFrontText().getMessage(0,true).getString().equals(filteredText.isEmpty()?raw:filteredText),"Expected fallback agrees with the actual SignText codec");
            check(target.matches(vanilla),"Acknowledged filtered sign payload can retire its ownership ticket");
            merged.getCompound("front_text").getList("filtered_messages",NbtElement.STRING_TYPE).set(0,NbtString.of(Text.Serializer.toJson(Text.literal("different authoritative filtered line"))));vanilla.readNbt(merged);
            check(!target.matches(vanilla),"Equal raw text does not confirm different filtered content");
        }
        var old=new NbtCompound();old.putString("Text1",Text.Serializer.toJson(Text.literal("旧版文字")));old.putString("Color","blue");old.putBoolean("GlowingText",true);
        var legacy=SignPrintTarget.from(state,old);check(legacy.front().get(0).equals("旧版文字")&&legacy.back().equals(Collections.nCopies(4,"")),"Legacy Text1 through Text4 populate only the front");
        var legacyBe=new SignBlockEntity(BlockPos.ORIGIN,state);legacyBe.readNbt(legacy.itemData());check(legacyBe.getFrontText().getColor()==DyeColor.BLUE&&legacyBe.getFrontText().isGlowing(),"Legacy visual properties migrate to current vanilla NBT");
        old.put("front_text",side("\"现代优先\""));check(SignPrintTarget.from(state,old).front().get(0).equals("现代优先"),"Explicit modern front overrides stale legacy fields");
        var maximum=new NbtCompound();maximum.put("front_text",side(Text.Serializer.toJson(Text.literal("字".repeat(384)))));
        var maxTarget=SignPrintTarget.from(state,maximum);String[] lines=maxTarget.lines(true);var buffer=new PacketByteBuf(Unpooled.buffer());
        try{new UpdateSignC2SPacket(new BlockPos(-10,-39,15),true,lines[0],lines[1],lines[2],lines[3]).write(buffer);var decoded=new UpdateSignC2SPacket(buffer);check(decoded.getText()[0].length()==384&&decoded.getPos().equals(new BlockPos(-10,-39,15))&&decoded.isFront(),"Maximum accepted text round trips through the actual vanilla packet decoder");}finally{buffer.release();}
        maximum.put("front_text",side(Text.Serializer.toJson(Text.literal("字".repeat(385)))));rejects(()->SignPrintTarget.from(state,maximum),"Oversized packet text is rejected, never silently truncated");
        var bad=new NbtCompound();bad.putString("front_text","wrong");rejects(()->SignPrintTarget.from(state,bad),"Wrong modern tag type does not become an empty sign");
        bad.put("front_text",side("{not valid json"));rejects(()->SignPrintTarget.from(state,bad),"Malformed message is rejected");
        bad.put("front_text",side("null"));rejects(()->SignPrintTarget.from(state,bad),"JSON null does not erase a target line");
        bad.put("front_text",side("\"ok\""));bad.getCompound("front_text").getList("messages",NbtElement.STRING_TYPE).remove(3);rejects(()->SignPrintTarget.from(state,bad),"Wrong line count is rejected");
        bad.put("front_text",side("[".repeat(18)+"\"x\""+"]".repeat(18)));rejects(()->SignPrintTarget.from(state,bad),"Nested JSON has an independent parse budget");
        bad.put("front_text",side("\"ok\""));bad.getCompound("front_text").putString("color","not_a_color");rejects(()->SignPrintTarget.from(state,bad),"Invalid color does not silently change appearance");
        var large=new NbtCompound();large.putByteArray("unrelated",new byte[131073]);rejects(()->SignPrintTarget.from(state,large),"Direct from callers cannot bypass total NBT budget");
        var deep=new NbtCompound();var cursor=deep;for(int i=0;i<18;i++){var next=new NbtCompound();cursor.put("nested",next);cursor=next;}rejects(()->SignPrintTarget.from(state,deep),"Direct from callers cannot bypass NBT depth budget");
        check(!SignPrintTarget.supported(Blocks.CHEST.getDefaultState())&&!SignPrintTarget.supported(null),"Only sign blocks are accepted");rejects(()->SignPrintTarget.from(Blocks.STONE.getDefaultState(),source),"Unrelated blocks cannot carry the sign payload");
        return checks;
    }
    private static NbtCompound side(String first){var tag=new NbtCompound();var messages=new NbtList();for(int i=0;i<4;i++)messages.add(NbtString.of(i==0?first:"\"\""));tag.put("messages",messages);return tag;}
}
