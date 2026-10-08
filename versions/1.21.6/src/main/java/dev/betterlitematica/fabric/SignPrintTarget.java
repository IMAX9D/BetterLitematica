package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

/** Detached sign text. Only literal content and visual sign properties enter creative items. */
record SignPrintTarget(BlockState state,List<String> front,List<String> back,NbtCompound itemData) {
    SignPrintTarget {front=List.copyOf(front);back=List.copyOf(back);itemData=itemData.copy();}
    @Override public NbtCompound itemData(){return itemData.copy();}
    static boolean supported(BlockState state){return state!=null&&state.getBlock() instanceof AbstractSignBlock;}
    static SignPrintTarget read(ProjectionController controller,BlockPos pos,BlockState expected){
        var at=new Vec3i(pos.getX(),pos.getY(),pos.getZ());var cell=controller.informationCell(at);
        if(cell==null||cell.unknown())throw new IllegalStateException("等待告示牌投影数据");
        var data=ProjectionInfoData.projectionBlock(cell,at);
        if(!data.error().isEmpty())throw new IllegalStateException(data.error());
        if(data.state()!=expected||!supported(data.state()))throw new IllegalStateException("告示牌投影已更换");
        return from(data.state(),data.tag());
    }
    static SignPrintTarget from(BlockState state,NbtCompound source){
        if(!supported(state))throw new IllegalArgumentException("不是告示牌投影");
        if(source!=null)validate(source,new Budget(),0);
        var input=source==null?new NbtCompound():source;
        var front=side(input,"front_text",true);var back=side(input,"back_text",false);
        var safe=new NbtCompound();safe.put("front_text",front.data());safe.put("back_text",back.data());
        if(input.contains("is_waxed")&&!NbtAccess.contains(input,"is_waxed",NbtAccess.NUMERIC))throw bad();
        safe.putBoolean("is_waxed",input.getBoolean("is_waxed",false));
        return new SignPrintTarget(state,front.lines(),back.lines(),safe);
    }
    ItemStack placementStack(){var stack=new ItemStack(state.getBlock().asItem());ItemDataBridge.blockEntityData(stack,state,itemData);return stack;}
    String[] lines(boolean frontSide){return (frontSide?front:back).toArray(String[]::new);}
    boolean nonDefault(){
        if(itemData.getBoolean("is_waxed",false)||front.stream().anyMatch(s->!s.isEmpty())||back.stream().anyMatch(s->!s.isEmpty()))return true;
        // Even a redundant filtered_messages key changes the newly placed entity's
        // encoded NBT, so vanilla treats the payload as applied and skips the editor.
        for(String key:List.of("front_text","back_text")){var side=itemData.getCompoundOrEmpty(key);if(side.contains("filtered_messages")||!side.getString("color","").equals("black")||side.getBoolean("has_glowing_text",false))return true;}
        return false;
    }
    boolean matches(net.minecraft.block.entity.SignBlockEntity sign){
        if(sign.getCachedState().getBlock()!=state.getBlock()||sign.isWaxed()!=itemData.getBoolean("is_waxed",false))return false;
        for(boolean face:new boolean[]{true,false}){var expected=face?front:back;var actual=sign.getText(face);var side=itemData.getCompoundOrEmpty(face?"front_text":"back_text");
            if(!actual.getColor().getId().equals(side.getString("color",""))||actual.isGlowing()!=side.getBoolean("has_glowing_text",false))return false;
            var filtered=side.contains("filtered_messages")?NbtAccess.list(side,"filtered_messages",NbtElement.STRING_TYPE):null;
            for(int i=0;i<4;i++){
                if(!actual.getMessage(i,false).getString().equals(expected.get(i)))return false;
                // SignText.CODEC replaces empty filtered messages with their raw line.
                String filteredLine=filtered==null?expected.get(i):plain(filtered.getString(i,""));
                if(filteredLine.isEmpty())filteredLine=expected.get(i);
                if(!actual.getMessage(i,true).getString().equals(filteredLine))return false;
            }
        }
        return true;
    }
    private record Side(List<String> lines,NbtCompound data) {}
    private static Side side(NbtCompound root,String key,boolean legacy){
        var safe=new NbtCompound();var text=new ArrayList<String>(4);var messages=new NbtList();
        if(root.contains(key)){
            if(!NbtAccess.contains(root,key,NbtElement.COMPOUND_TYPE))throw bad();var value=root.getCompoundOrEmpty(key);
            var list=messages(value,"messages");for(int i=0;i<4;i++){var line=plain(list.getString(i,""));text.add(line);messages.add(literal(line));}
            if(value.contains("filtered_messages")){var filtered=messages(value,"filtered_messages");var out=new NbtList();for(int i=0;i<4;i++)out.add(literal(plain(filtered.getString(i,""))));safe.put("filtered_messages",out);}
            safe.putString("color",color(value,"color"));safe.putBoolean("has_glowing_text",flag(value,"has_glowing_text"));
        }else{
            for(int i=1;i<=4;i++){String line="";String old="Text"+i;if(legacy&&root.contains(old)){if(!NbtAccess.contains(root,old,NbtElement.STRING_TYPE))throw bad();line=plain(root.getString(old,""));}text.add(line);messages.add(literal(line));}
            safe.putString("color",legacy?color(root,"Color"):"black");safe.putBoolean("has_glowing_text",legacy&&flag(root,"GlowingText"));
        }
        safe.put("messages",messages);return new Side(text,safe);
    }
    private static NbtList messages(NbtCompound value,String key){
        if(!(value.get(key) instanceof NbtList list)||list.size()!=4||NbtAccess.heldType(list)!=NbtElement.STRING_TYPE)throw bad();return list;
    }
    private static boolean flag(NbtCompound value,String key){if(value.contains(key)&&!NbtAccess.contains(value,key,NbtAccess.NUMERIC))throw bad();return value.getBoolean(key,false);}
    private static String color(NbtCompound value,String key){
        if(!value.contains(key))return "black";if(!NbtAccess.contains(value,key,NbtElement.STRING_TYPE))throw bad();String color=value.getString(key,"");
        for(var dye:DyeColor.values())if(dye.getId().equals(color))return color;throw bad();
    }
    private static NbtString literal(String text){return NbtString.of(net.minecraft.text.TextCodecs.CODEC.encodeStart(ItemDataBridge.registries().getOps(com.mojang.serialization.JsonOps.INSTANCE),Text.literal(text)).getOrThrow().toString());}
    private static String plain(String json){
        if(json.length()>16384)throw new IllegalArgumentException("告示牌文字数据过大");
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<json.length();i++){char c=json.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='\"')quoted=false;}else if(c=='\"')quoted=true;else if(c=='{'||c=='['){if(++depth>16)throw new IllegalArgumentException("告示牌文字嵌套过深");}else if(c=='}'||c==']')depth--;}
        try{var parsed=net.minecraft.text.TextCodecs.CODEC.parse(ItemDataBridge.registries().getOps(com.mojang.serialization.JsonOps.INSTANCE),com.google.gson.JsonParser.parseString(json)).getOrThrow();if(parsed==null)throw bad();String value=parsed.getString();if(value.length()>384)throw new IllegalArgumentException("告示牌每行文字不能超过 384 字符");return value;}
        catch(com.google.gson.JsonParseException e){throw new IllegalArgumentException("告示牌文字格式无效",e);}
    }
    private static IllegalArgumentException bad(){return new IllegalArgumentException("告示牌文字数据无效");}
    private static final class Budget {int nodes,bytes;void take(long count,int depth){if(depth>16||++nodes>8192||count<0||count>131072-bytes)throw new IllegalArgumentException("告示牌数据超过预算");bytes+=(int)count;}}
    private static void validate(NbtElement value,Budget budget,int depth){
        budget.take(16,depth);
        if(value instanceof NbtCompound map){for(String key:map.getKeys()){budget.take((long)key.length()*2,depth);validate(map.get(key),budget,depth+1);}}
        else if(value instanceof NbtList list){for(var child:list)validate(child,budget,depth+1);}
        else if(value instanceof NbtString string)budget.take((long)string.asString().orElseThrow().length()*2,depth);
        else if(value instanceof NbtByteArray array)budget.take(array.size(),depth);
        else if(value instanceof NbtIntArray array)budget.take((long)array.size()*4,depth);
        else if(value instanceof NbtLongArray array)budget.take((long)array.size()*8,depth);
    }
}
