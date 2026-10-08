package dev.betterlitematica.fabric;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
/** User-defined tool with optional exact SNBT matching; durability is not an identity. */
final class ToolItemSpec {
    private final Item item;private final CompoundTag nbt;private CompoundTag identity;
    private ToolItemSpec(Item item,CompoundTag nbt){this.item=item;this.nbt=nbt;}
    static ToolItemSpec parse(String text){
        if(text==null||text.length()>4096)throw new IllegalArgumentException("无效工具物品");text=text.strip();if(text.isEmpty()||text.equals("empty"))return new ToolItemSpec(null,null);
        int brace=text.indexOf('{');String name=brace<0?text:text.substring(0,brace);name=name.replaceFirst("@[0-9]+$","");var id=Identifier.tryParse(name);
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("工具物品不存在");Item item=BuiltInRegistries.ITEM.getValue(id);if(item==Items.AIR)return new ToolItemSpec(null,null);
        CompoundTag tag=null;try{if(brace>=0){tag=TagParser.parseCompoundFully(text.substring(brace));tag.remove("Damage");}}catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalArgumentException("工具 NBT 格式错误",e);}
        return new ToolItemSpec(item,tag);
    }
    static void validate(String text){parse(text);}Item item(){return item;}
    boolean matches(ItemStack stack){if(item==null)return stack.isEmpty();if(!stack.is(item))return false;if(nbt==null)return true;if(identity==null)identity=ItemDataBridge.identity(ItemDataBridge.legacy(item,nbt));return identity.equals(ItemDataBridge.identity(stack));}
    boolean held(ItemStack main,ItemStack off){return item==null?main.isEmpty():matches(main)||matches(off);}
}
