package dev.betterlitematica.fabric;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
/** User-defined tool with optional exact SNBT matching; durability is not an identity. */
final class ToolItemSpec {
    private final Item item;private final NbtCompound nbt;private NbtCompound identity;
    private ToolItemSpec(Item item,NbtCompound nbt){this.item=item;this.nbt=nbt;}
    static ToolItemSpec parse(String text){
        if(text==null||text.length()>4096)throw new IllegalArgumentException("无效工具物品");text=text.strip();if(text.isEmpty()||text.equals("empty"))return new ToolItemSpec(null,null);
        int brace=text.indexOf('{');String name=brace<0?text:text.substring(0,brace);name=name.replaceFirst("@[0-9]+$","");var id=Identifier.tryParse(name);
        if(id==null||!Registries.ITEM.containsId(id))throw new IllegalArgumentException("工具物品不存在");Item item=Registries.ITEM.get(id);if(item==Items.AIR)return new ToolItemSpec(null,null);
        NbtCompound tag=null;try{if(brace>=0){tag=StringNbtReader.parse(text.substring(brace));tag.remove("Damage");}}catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalArgumentException("工具 NBT 格式错误",e);}
        return new ToolItemSpec(item,tag);
    }
    static void validate(String text){parse(text);}Item item(){return item;}
    boolean matches(ItemStack stack){if(item==null)return stack.isEmpty();if(!stack.isOf(item))return false;if(nbt==null)return true;if(identity==null)identity=ItemDataBridge.identity(ItemDataBridge.legacy(item,nbt));return identity.equals(ItemDataBridge.identity(stack));}
    boolean held(ItemStack main,ItemStack off){return item==null?main.isEmpty():matches(main)||matches(off);}
}
