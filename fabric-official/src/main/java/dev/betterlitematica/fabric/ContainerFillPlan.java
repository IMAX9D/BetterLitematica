package dev.betterlitematica.fabric;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.function.Predicate;

/** Read-only inventory planning. Each accepted transaction starts and finishes with an empty cursor. */
final class ContainerFillPlan {
    record Click(int slot,int button,ContainerInput action){}
    record Plan(List<Click> clicks,List<ItemStack> after,ItemStack missing,boolean complete,String blocked){}
    private record Portion(int slot,int count,int capacity,boolean canTake){}
    private record Transaction(List<Click> clicks,List<Portion> portions,int moved){}
    private ContainerFillPlan(){}

    static Plan plan(AbstractContainerMenu handler,Player player,List<ItemStack> targets,int protectedHotbar,Predicate<ItemStack> protection,int maxClicks){
        Objects.requireNonNull(handler);Objects.requireNonNull(player);Objects.requireNonNull(targets);Objects.requireNonNull(protection);
        if(maxClicks<1||maxClicks>64||targets.size()>1024||handler.slots.size()>1060||targets.size()>handler.slots.size())throw new IllegalArgumentException("Container plan budget");
        var after=new ArrayList<ItemStack>(handler.slots.size());for(var slot:handler.slots)after.add(slot.getItem().copy());
        if(!handler.getCarried().isEmpty())return result(List.of(),after,ItemStack.EMPTY,false,"鼠标上有物品");
        int[] sourceSlots=new int[36];Arrays.fill(sourceSlots,-1);var inventory=player.getInventory();
        for(int slot=0;slot<handler.slots.size();slot++){
            var entry=handler.slots.get(slot);if(entry.container!=inventory)continue;
            if(slot<targets.size())return result(List.of(),after,ItemStack.EMPTY,false,"容器槽位无效");
            int index=entry.getContainerSlot();if(index<0||index>=36)continue;
            if(sourceSlots[index]>=0)return result(List.of(),after,ItemStack.EMPTY,false,"重复的库存槽位");sourceSlots[index]=slot;
        }
        for(var target:targets)Objects.requireNonNull(target);
        var clicks=new ArrayList<Click>();String obstruction="";
        for(int destination=0;destination<targets.size()&&clicks.size()<maxClicks;destination++){
            ItemStack wanted=targets.get(destination);if(satisfied(after.get(destination),wanted))continue;
            String invalid=obstruction(handler.slots.get(destination),after.get(destination),wanted,player);if(!invalid.isEmpty()){if(obstruction.isEmpty())obstruction=invalid;continue;}
            for(int source=0;source<36&&!satisfied(after.get(destination),wanted)&&clicks.size()<maxClicks;source++){
                int sourceSlot=sourceSlots[source];if(!usable(handler,player,after,source,sourceSlot,protectedHotbar,protection))continue;
                ItemStack stack=after.get(sourceSlot);if(!ItemStack.isSameItemSameComponents(stack,wanted))continue;
                int need=wanted.getCount()-after.get(destination).getCount();
                if(source<9&&after.get(destination).isEmpty()&&stack.getCount()==need){
                    clicks.add(new Click(destination,source,ContainerInput.SWAP));after.set(destination,stack.copy());after.set(sourceSlot,ItemStack.EMPTY);continue;
                }
                var group=new ArrayList<Portion>();
                for(int slot=destination;slot<targets.size()&&group.size()<64;slot++){
                    var target=targets.get(slot);var actual=after.get(slot);
                    if(target.isEmpty()||!ItemStack.isSameItemSameComponents(target,stack)||satisfied(actual,target)||!obstruction(handler.slots.get(slot),actual,target,player).isEmpty())continue;
                    group.add(new Portion(slot,target.getCount()-actual.getCount(),capacity(handler.slots.get(slot),target)-actual.getCount(),handler.slots.get(slot).mayPickup(player)));
                }
                Transaction chosen=null;int room=maxClicks-clicks.size();
                for(int button=0;button<2;button++){
                    // Right-clicking a bundle changes its contents rather than splitting the stack.
                    if(button==1&&stack.is(Items.BUNDLE))continue;
                    int taken=button==0?stack.getCount():(stack.getCount()+1)/2;
                    if(button==1&&!handler.slots.get(sourceSlot).allowModification(player))continue;
                    var portions=new ArrayList<Portion>();int moved=0;
                    for(var part:group){int count=Math.min(part.count(),taken-moved);if(count<=0)break;portions.add(new Portion(part.slot(),count,part.capacity(),part.canTake()));moved+=count;
                        for(boolean returnFirst:new boolean[]{false,true}){
                            var candidate=transaction(handler.slots.get(sourceSlot),sourceSlot,stack,taken,button,portions,moved,returnFirst,room);
                            if(candidate!=null&&(chosen==null||candidate.moved()>chosen.moved()||candidate.moved()==chosen.moved()&&candidate.clicks().size()<chosen.clicks().size()))chosen=candidate;
                        }
                    }
                }
                if(chosen==null)continue;
                clicks.addAll(chosen.clicks());for(var portion:chosen.portions()){var next=stack.copy();next.setCount(after.get(portion.slot()).getCount()+portion.count());after.set(portion.slot(),next);}
                int remaining=stack.getCount()-chosen.moved();var rest=stack.copy();rest.setCount(remaining);after.set(sourceSlot,remaining==0?ItemStack.EMPTY:rest);
                // Budget-limited distribution may leave this source useful for the same destination.
                if(!satisfied(after.get(destination),wanted)&&remaining>0)source--;
            }
        }
        boolean complete=true;ItemStack missing=ItemStack.EMPTY;
        for(int slot=0;slot<targets.size();slot++){
            var wanted=targets.get(slot);if(satisfied(after.get(slot),wanted))continue;complete=false;
            String invalid=obstruction(handler.slots.get(slot),after.get(slot),wanted,player);if(!invalid.isEmpty()){if(obstruction.isEmpty())obstruction=invalid;continue;}
            if(!clicks.isEmpty()||!missing.isEmpty())continue;
            int available=0;for(int source=0;source<36;source++){int index=sourceSlots[source];if(usable(handler,player,after,source,index,protectedHotbar,protection)&&ItemStack.isSameItemSameComponents(after.get(index),wanted))available+=after.get(index).getCount();}
            int deficit=wanted.getCount()-after.get(slot).getCount()-available;
            if(deficit>0){missing=wanted.copy();missing.setCount(deficit);}
        }
        if(!complete&&clicks.isEmpty()&&missing.isEmpty()&&obstruction.isEmpty())obstruction="无法完成库存操作";
        return result(clicks,after,missing,complete,clicks.isEmpty()?obstruction:"");
    }
    private static Plan result(List<Click> clicks,List<ItemStack> after,ItemStack missing,boolean complete,String blocked){return new Plan(List.copyOf(clicks),List.copyOf(after),missing,complete,blocked);}
    private static boolean satisfied(ItemStack actual,ItemStack wanted){return wanted.isEmpty()||!actual.isEmpty()&&ItemStack.isSameItemSameComponents(actual,wanted)&&actual.getCount()>=wanted.getCount();}
    private static int capacity(Slot slot,ItemStack item){return Math.min(item.getMaxStackSize(),slot.getMaxStackSize(item));}
    private static String obstruction(Slot slot,ItemStack actual,ItemStack wanted,Player player){
        if(!actual.isEmpty()&&!ItemStack.isSameItemSameComponents(actual,wanted))return "目标槽位已有其他物品";
        if(!slot.mayPlace(wanted)||!actual.isEmpty()&&!slot.mayPickup(player))return "目标槽位不可放入";
        if(wanted.getCount()>capacity(slot,wanted))return "目标数量超过槽位容量";
        return "";
    }
    private static boolean usable(AbstractContainerMenu handler,Player player,List<ItemStack> after,int index,int slot,int mask,Predicate<ItemStack> protection){
        if(slot<0||index<9&&(mask&(1<<index))!=0)return false;var stack=after.get(slot);
        return !stack.isEmpty()&&stack.getCount()<=stack.getMaxStackSize()&&!protection.test(stack)&&handler.slots.get(slot).mayPickup(player);
    }
    private static Transaction transaction(Slot source,int sourceSlot,ItemStack identity,int taken,int button,List<Portion> portions,int moved,boolean returnFirst,int budget){
        int rest=taken-moved;if(rest>0&&!source.mayPlace(identity))return null;
        // The original source slot always has enough room to receive its own remainder.
        if(rest>0&&capacity(source,identity)<identity.getCount()-moved)return null;
        var result=new ArrayList<Click>();result.add(new Click(sourceSlot,button,ContainerInput.PICKUP));int cursor=taken;
        if(returnFirst){for(int i=0;i<rest;i++){if(result.size()>=budget)return null;result.add(new Click(sourceSlot,1,ContainerInput.PICKUP));}cursor-=rest;}
        for(var portion:portions){
            boolean left=portion.count()==Math.min(cursor,portion.capacity());int count=left?1:portion.count();
            // After the first right-click an empty slot is occupied; vanilla then also requires canTakeItems.
            if(!left&&count>1&&!portion.canTake())return null;
            if(result.size()+count>budget)return null;for(int i=0;i<count;i++)result.add(new Click(portion.slot(),left?0:1,ContainerInput.PICKUP));cursor-=portion.count();
        }
        if(cursor>0){if(result.size()>=budget)return null;result.add(new Click(sourceSlot,0,ContainerInput.PICKUP));}
        if(result.size()>budget)return null;return new Transaction(List.copyOf(result),List.copyOf(portions),moved);
    }
}
