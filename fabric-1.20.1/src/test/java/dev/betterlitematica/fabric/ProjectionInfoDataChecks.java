package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Real vanilla state/registry and inventory/NBT checks; no active world is needed. */
public final class ProjectionInfoDataChecks {
 private static int checks;
 public static int run(){checks=0;var pos=new BlockPos(7,201,15);
  var chest=Blocks.CHEST.getDefaultState();var tag=container(26,"minecraft:diamond",37);
  var list=ProjectionInfoData.items(chest,tag,pos);
  require(list.size()==27,"all chest slots");for(int i=0;i<26;i++)require(list.get(i).isEmpty(),"empty preserved");
  require(list.get(26).isOf(Items.DIAMOND)&&list.get(26).getCount()==37,"last slot/count");
  tag.getList("Items",10).getCompound(0).getCompound("tag").putString("kept","changed source");
  require(!list.get(26).getNbt().contains("kept"),"item owns independent NBT");
  require(list.get(26).getNbt().getString("custom").equals("preserved"),"item NBT preserved");
  var absent=ProjectionInfoData.side(ProjectionInfoData.sourceData(chest,null,pos),p->null,pos);
  require(absent.items().isEmpty()&&absent.availability().equals("投影未包含容器数据"),"absent source inventory is unknown");
  var empty=ProjectionInfoData.side(ProjectionInfoData.sourceData(chest,new NbtCompound(),pos),p->null,pos);
  require(empty.items().size()==27&&empty.items().stream().allMatch(net.minecraft.item.ItemStack::isEmpty)&&empty.availability().isEmpty(),"stored empty container is known empty");
  require(ProjectionInfoData.sourceData(Blocks.STONE.getDefaultState(),null,pos).error().isEmpty(),"non-container requires no inventory NBT");
  for(var block:new Block[]{Blocks.BARREL,Blocks.SHULKER_BOX,Blocks.TRAPPED_CHEST})require(ProjectionInfoData.items(block.getDefaultState(),null,pos).size()==27,"container capacity");
  require(ProjectionInfoData.items(Blocks.FURNACE.getDefaultState(),null,pos).size()==3,"furnace");
  require(ProjectionInfoData.items(Blocks.HOPPER.getDefaultState(),null,pos).size()==5,"hopper");
  require(ProjectionInfoData.items(Blocks.DISPENSER.getDefaultState(),null,pos).size()==9,"dispenser");
  require(ProjectionInfoData.items(Blocks.CHISELED_BOOKSHELF.getDefaultState(),null,pos).size()==6,"bookshelf");
  for(var facing:Direction.Type.HORIZONTAL)for(var type:new ChestType[]{ChestType.LEFT,ChestType.RIGHT}){
   var one=chest.with(ChestBlock.FACING,facing).with(ChestBlock.CHEST_TYPE,type);
   var other=one.with(ChestBlock.CHEST_TYPE,type.getOpposite());
   var a=new ProjectionInfoData.BlockData(one,container(0,"minecraft:diamond",2),"");
   var b=new ProjectionInfoData.BlockData(other,container(26,"minecraft:emerald",3),"");
   var output=ProjectionInfoData.side(a,p->{require(p.equals(pos.offset(ChestBlock.getFacing(one))),"neighbor coordinate");return b;},pos);
   require(output.items().size()==54&&output.availability().isEmpty(),"connected double chest");
   require(output.items().get(type==ChestType.RIGHT?0:27).isOf(Items.DIAMOND),"vanilla RIGHT first");
   require(output.items().get(type==ChestType.RIGHT?53:26).isOf(Items.EMERALD),"partner last slot");
   require(!ProjectionInfoData.connected(one,other.with(ChestBlock.FACING,facing.getOpposite())),"different facing rejected");
   require(!ProjectionInfoData.connected(one,one),"same side rejected");
   require(!ProjectionInfoData.connected(one,Blocks.TRAPPED_CHEST.getDefaultState().with(ChestBlock.FACING,facing).with(ChestBlock.CHEST_TYPE,type.getOpposite())),"different chest rejected");
   var missing=ProjectionInfoData.side(a,p->new ProjectionInfoData.BlockData(null,null,"未加载"),pos);
   require(!missing.availability().isEmpty()&&missing.items().isEmpty(),"unloaded partner not empty double chest");
  }
  var loot=new NbtCompound();loot.putString("LootTable","minecraft:chests/simple_dungeon");
  var unknownLoot=ProjectionInfoData.side(new ProjectionInfoData.BlockData(chest,loot,""),p->null,pos);
  require(!unknownLoot.availability().isEmpty()&&unknownLoot.items().isEmpty(),"loot is not generated");
  var huge=new NbtCompound();huge.putByteArray("oversize",new byte[131073]);
  boolean failed=false;try{ProjectionInfoData.items(chest,huge,pos);}catch(IllegalArgumentException e){failed=true;}require(failed,"NBT bound");
  failed=false;try{ProjectionInfoData.items(chest,container(0,"unknown:nonexistent",1),pos);}catch(IllegalArgumentException e){failed=true;}require(failed,"unknown item not empty");
  var longText=new NbtCompound();longText.putString("CustomName","x".repeat(10000));
  var lines=ProjectionInfoData.summary(longText);require(lines.get(0).length()<300&&lines.get(0).endsWith("…"),"long value truncation visible");
  var many=new NbtCompound();for(int i=0;i<300;i++)many.putInt(String.format("key%03d",i),i);
  lines=ProjectionInfoData.summary(many);require(lines.size()<=128&&lines.get(lines.size()-1).equals("…"),"line bound visible");
  var books=new NbtCompound();var book=container(0,"minecraft:written_book",1).getList("Items",10).getCompound(0);books.put("Book",book);
  require(ProjectionInfoData.items(Blocks.LECTERN.getDefaultState(),books,pos).get(0).isOf(Items.WRITTEN_BOOK),"lectern dedicated field");
  var record=new NbtCompound();record.put("RecordItem",container(0,"minecraft:music_disc_13",1).getList("Items",10).getCompound(0));
  require(ProjectionInfoData.items(Blocks.JUKEBOX.getDefaultState(),record,pos).get(0).isOf(Items.MUSIC_DISC_13),"jukebox dedicated field");
  return checks;
 }
 private static NbtCompound container(int slot,String id,int count){var root=new NbtCompound();var items=new NbtList();var item=new NbtCompound();item.putByte("Slot",(byte)slot);item.putString("id",id);item.putByte("Count",(byte)count);var data=new NbtCompound();data.putString("custom","preserved");item.put("tag",data);items.add(item);root.put("Items",items);return root;}
 private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
