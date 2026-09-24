package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.text.Text;

/** Real ItemStack/NBT comparison and source-isolation checks. No active Minecraft world. */
public final class ProjectionInventoryViewChecks {
    private static int checks;
    public static int run(){
        checks=0;
        for(var block:new Block[]{Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.BARREL,Blocks.SHULKER_BOX,Blocks.BLUE_SHULKER_BOX}){
            var first=new ItemStack(Items.DIAMOND,64);var second=new ItemStack(Items.DIAMOND,53);
            var source=new ProjectionInfoData.Side(block.getDefaultState(),"",List.of("key = value"),List.of(first,ItemStack.EMPTY,second,new ItemStack(Items.EMERALD,2)),9);
            var merged=ProjectionInventoryView.compact(source);
            require(merged.items().size()==2,"empty slots removed, identical items merged");
            require(merged.items().get(0).isOf(Items.DIAMOND)&&merged.items().get(0).getCount()==117,"sum exceeds vanilla stack maximum");
            require(merged.items().get(1).isOf(Items.EMERALD)&&merged.items().get(1).getCount()==2,"first encounter order");
            require(first.getCount()==64&&second.getCount()==53&&source.items().size()==4,"source counts/slots untouched");
            merged.items().get(0).setCount(1);require(first.getCount()==64&&second.getCount()==53,"output owns stack");
            require(merged.nbt().equals(source.nbt())&&merged.state()==source.state()&&merged.availability().equals(source.availability()),"metadata preserved");
        }
        var tagged=new ItemStack(Items.DIAMOND,2);var nested=new NbtCompound();nested.putString("origin","one");tagged.getOrCreateNbt().put("payload",nested);
        var identical=tagged.copy();identical.setCount(3);var changed=tagged.copy();changed.getNbt().getCompound("payload").putString("origin","two");
        var named=tagged.copy().setCustomName(Text.literal("named"));
        var enchanted=new ItemStack(Items.DIAMOND_SWORD);enchanted.addEnchantment(Enchantments.SHARPNESS,1);
        var another=enchanted.copy();another.addEnchantment(Enchantments.UNBREAKING,1);
        var source=side(Blocks.CHEST,List.of(tagged,identical,changed,named,enchanted,another),9);
        var result=ProjectionInventoryView.compact(source);
        require(result.items().size()==5&&result.items().get(0).getCount()==5,"full NBT identity");
        result.items().get(0).getNbt().getCompound("payload").putString("origin","display change");
        require(tagged.getNbt().getCompound("payload").getString("origin").equals("one")&&identical.getNbt().getCompound("payload").getString("origin").equals("one"),"deep NBT isolation");
        var a=new ItemStack(Items.DIAMOND);var b=new ItemStack(Items.DIAMOND);
        a.getOrCreateNbt().putInt("first",1);a.getOrCreateNbt().putInt("second",2);
        b.getOrCreateNbt().putInt("second",2);b.getOrCreateNbt().putInt("first",1);
        require(ProjectionInventoryView.compact(side(Blocks.BARREL,List.of(a,b),9)).items().size()==1,"compound insertion order does not change NBT equality");
        for(var block:new Block[]{Blocks.DISPENSER,Blocks.DROPPER,Blocks.HOPPER,Blocks.FURNACE,Blocks.SMOKER,Blocks.BLAST_FURNACE}){
            var original=new ItemStack(Items.COAL,6);var empty=new ItemStack(Items.AIR,0);
            source=side(block,List.of(original,empty,new ItemStack(Items.COAL,7)),9);
            result=ProjectionInventoryView.compact(source);
            int columns=block instanceof HopperBlock?5:3;
            require(result.items().size()==3&&result.items().get(1).isEmpty(),"machine slots/empties retained");
            require(result.columns()==columns,"machine layout columns");
            require(result.items().get(0).getCount()==6&&result.items().get(2).getCount()==7,"machine equal items not merged");
            require(result.items().get(0)!=original&&result.items().get(1)!=empty,"machine stacks independent");
            result.items().get(0).setCount(20);require(original.getCount()==6,"machine source untouched");
        }
        var allEmpty=ProjectionInventoryView.compact(side(Blocks.CHEST,Collections.nCopies(54,ItemStack.EMPTY),9));
        require(allEmpty.items().isEmpty(),"empty double chest compact");
        var unknown=new ProjectionInfoData.Side(null,"未同步",List.of(),List.of(),9);
        require(ProjectionInventoryView.compact(unknown).availability().equals("未同步"),"unknown retained");
        var massive=side(Blocks.CHEST,List.of(new ItemStack(Items.DIAMOND,Integer.MAX_VALUE),new ItemStack(Items.DIAMOND,1)),9);
        var overflow=ProjectionInventoryView.compact(massive);
        require(!overflow.availability().isEmpty()&&overflow.items().size()==2&&overflow.items().get(0).getCount()==Integer.MAX_VALUE&&overflow.items().get(1).getCount()==1,"integer overflow preserves exact source counts");
        boolean rejected=false;try{ProjectionInventoryView.compact(side(Blocks.CHEST,Collections.nCopies(257,ItemStack.EMPTY),9));}catch(IllegalArgumentException e){rejected=true;}
        require(rejected,"bounded slot input");
        return checks;
    }
    private static ProjectionInfoData.Side side(Block block,List<ItemStack> items,int columns){return new ProjectionInfoData.Side(block.getDefaultState(),"",List.of(),items,columns);}
    private static void require(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
