package dev.betterlitematica.fabric;

import net.minecraft.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.*;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.resource.featuretoggle.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.*;
import net.minecraft.util.ClickType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.function.Predicate;

/** Executes plans through vanilla ScreenHandler.onSlotClick, not a second click simulator. */
public final class ContainerFillPlanChecks {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static final Predicate<ItemStack> NONE=stack->false;
    private record Fixture(TestPlayer player,ScreenHandler handler,int size){}
    public static int run(){checks=0;
        var f=chest(27);f.player.getInventory().setStack(2,new ItemStack(Items.STONE,17));var target=targets(27);target.set(0,new ItemStack(Items.STONE,17));
        var plan=execute(f,target,0,NONE,64);check(plan.complete()&&plan.clicks().size()==1&&plan.clicks().get(0).action()==SlotActionType.SWAP&&plan.clicks().get(0).button()==2,"Exact hotbar amount uses one real SWAP");
        for(int desired:new int[]{1,31,32,33,63,64}){
            f=chest(27);f.player.getInventory().setStack(12,new ItemStack(Items.STONE,64));target=targets(27);target.set(0,new ItemStack(Items.STONE,desired));plan=execute(f,target,0,NONE,64);
            check(plan.complete(),"Partial count fills exact requested amount");if(desired==1||desired==63)check(plan.clicks().size()==3,"One-versus-remainder strategy avoids linear stack clicking");if(desired==32)check(plan.clicks().size()==2,"Half-stack pickup uses two actual clicks");
        }
        f=chest(54);f.player.getInventory().setStack(30,new ItemStack(Items.STONE,64));target=targets(54);for(int i=0;i<54;i++)target.set(i,new ItemStack(Items.STONE,1));plan=execute(f,target,0,NONE,64);
        check(plan.complete()&&plan.clicks().size()==56,"Double chest distributes one source across 54 slots then returns its remainder");
        f=chest(54);f.player.getInventory().setStack(35,new ItemStack(Items.STONE,64));f.player.getInventory().setStack(4,new ItemStack(Items.DIAMOND,16));target=targets(54);target.set(0,new ItemStack(Items.DIAMOND,16));target.set(53,new ItemStack(Items.STONE,63));plan=execute(f,target,0,NONE,64);check(plan.complete(),"Both halves of 54-slot container preserve slot identities");

        f=chest(27);var named=new ItemStack(Items.DIAMOND,12);named.getOrCreateNbt().putString("identity","wanted");named.getOrCreateNbt().putIntArray("payload",new int[]{1,2,3});var wrong=named.copy();wrong.getOrCreateNbt().putString("identity","other");
        f.player.getInventory().setStack(0,wrong);f.player.getInventory().setStack(11,named.copy());target=targets(27);var desired=named.copy();desired.setCount(9);target.set(0,desired);plan=execute(f,target,0,NONE,64);
        check(plan.complete()&&f.player.getInventory().getStack(0).getCount()==12,"Full NBT identity prevents consumption of similarly named or enchanted variants");
        plan.after().get(0).getOrCreateNbt().putString("identity","changed-copy");check(f.handler.getSlot(0).getStack().getNbt().getString("identity").equals("wanted"),"Published after snapshot does not share live NBT");
        f=chest(27);f.player.getInventory().setStack(0,new ItemStack(Items.STONE,64));f.player.getInventory().setStack(10,new ItemStack(Items.STONE,3));target=targets(27);target.set(0,new ItemStack(Items.STONE,4));plan=execute(f,target,1,NONE,64);
        check(!plan.complete()&&f.player.getInventory().getStack(0).getCount()==64&&f.handler.getSlot(0).getStack().getCount()==3,"Protected hotbar remains untouched while usable bag material fills partially");plan=execute(f,target,1,NONE,64);check(plan.clicks().isEmpty()&&plan.missing().getCount()==1&&plan.missing().isOf(Items.STONE),"Partial fill reports exact remaining deficit on the next authoritative snapshot");
        f=chest(27);f.player.getInventory().setStack(0,named.copy());target=targets(27);target.set(0,desired);plan=execute(f,target,0,s->s.hasNbt()&&s.getNbt().contains("identity"),64);check(plan.clicks().isEmpty()&&ItemStack.canCombine(plan.missing(),desired)&&plan.missing().getCount()==9,"NBT tool protection produces exact missing identity without consuming tool");

        f=chest(27);for(int i=0;i<36;i++)f.player.getInventory().setStack(i,new ItemStack(Items.DIRT,64));f.player.getInventory().setStack(12,new ItemStack(Items.STONE,64));f.handler.getSlot(1).setStack(new ItemStack(Items.DIRT,7));f.handler.getSlot(2).setStack(new ItemStack(Items.STONE,10));target=targets(27);target.set(0,new ItemStack(Items.STONE,63));target.set(1,new ItemStack(Items.STONE,1));target.set(2,new ItemStack(Items.STONE,4));plan=execute(f,target,0,NONE,64);
        check(f.handler.getSlot(0).getStack().getCount()==63&&f.handler.getSlot(1).getStack().isOf(Items.DIRT)&&f.handler.getSlot(2).getStack().getCount()==10,"Full player inventory works without clearing wrong or excess target contents");
        plan=execute(f,target,0,NONE,64);check(!plan.complete()&&!plan.blocked().isEmpty()&&plan.clicks().isEmpty(),"Wrong target remains explicitly blocked, never silently considered complete");

        f=chest(27);f.player.getInventory().setStack(10,new ItemStack(Items.STONE,64));target=targets(27);for(int i=0;i<27;i++)target.set(i,new ItemStack(Items.STONE,1));int batches=0;
        do{plan=execute(f,target,0,NONE,5);check(plan.clicks().size()<=5,"Every short batch respects its click limit");check(!plan.clicks().isEmpty(),"Short batches continue making safe progress");}while(!plan.complete()&&++batches<30);check(plan.complete(),"Repeated cursor-empty short batches finish dense one-item allocation");
        f=chest(27);f.player.getInventory().setStack(10,new ItemStack(Items.STONE,64));target=targets(27);target.set(0,new ItemStack(Items.STONE,1));plan=execute(f,target,0,NONE,1);check(plan.clicks().isEmpty()&&!plan.complete()&&!plan.blocked().isEmpty(),"Too-small budget never leaves a picked-up cursor stack");

        f=chest(27);f.player.getInventory().setStack(10,new ItemStack(Items.STONE,64));f.handler.getSlot(0).setStack(new ItemStack(Items.DIRT,3));target=targets(27);plan=execute(f,target,0,NONE,64);check(plan.complete()&&plan.clicks().isEmpty()&&f.handler.getSlot(0).getStack().getCount()==3,"All-empty target skips existing unrelated contents");
        f.handler.setCursorStack(new ItemStack(Items.APPLE));plan=ContainerFillPlan.plan(f.handler,f.player,target,0,NONE,64);check(plan.clicks().isEmpty()&&!plan.blocked().isEmpty()&&f.handler.getCursorStack().isOf(Items.APPLE),"An occupied live cursor blocks without mutation");

        f=chest(27);f.player.getInventory().setStack(10,new ItemStack(Items.STONE,8));var container=f.handler.getSlot(0).inventory;
        Slot limited=new Slot(container,0,0,0){@Override public int getMaxItemCount(ItemStack item){return 8;}};f.handler.slots.set(0,limited);target=targets(27);target.set(0,new ItemStack(Items.STONE,9));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&!plan.blocked().isEmpty()&&plan.missing().isEmpty(),"Slot capacity rejection is not misreported as missing material");
        target.set(0,new ItemStack(Items.STONE,8));plan=execute(f,target,0,NONE,64);check(plan.complete(),"Exact constrained slot capacity still fills");
        f=chest(27);f.player.getInventory().setStack(10,new ItemStack(Items.STONE,8));int source=sourceSlot(f,10);var inv=f.player.getInventory();f.handler.slots.set(source,new Slot(inv,10,0,0){@Override public boolean canTakeItems(PlayerEntity p){return false;}});target=targets(27);target.set(0,new ItemStack(Items.STONE,8));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&plan.missing().getCount()==8,"Source canTake restrictions prevent consumption");
        f=chest(27);container=f.handler.getSlot(0).inventory;f.handler.slots.set(0,new Slot(container,0,0,0){@Override public boolean canTakeItems(PlayerEntity p){return false;}});f.handler.getSlot(0).setStack(new ItemStack(Items.STONE,2));f.player.getInventory().setStack(12,new ItemStack(Items.STONE,2));target=targets(27);target.set(0,new ItemStack(Items.STONE,4));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&!plan.blocked().isEmpty(),"Vanilla nonempty-slot canTake guard is honored even when adding items");
        f.handler.getSlot(0).setStack(ItemStack.EMPTY);f.player.getInventory().setStack(12,new ItemStack(Items.STONE,64));target.set(0,new ItemStack(Items.STONE,63));plan=execute(f,target,0,NONE,64);check(plan.complete()&&plan.clicks().size()==3,"Empty cannot-take slot accepts one exact deposit, never repeated right clicks after occupation");
        f=chest(27);f.player.getInventory().setStack(40,new ItemStack(Items.DIAMOND,16));f.player.getInventory().setStack(36,new ItemStack(Items.DIAMOND,16));target=targets(27);target.set(0,new ItemStack(Items.DIAMOND,2));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&plan.missing().getCount()==2,"Offhand and armor slots are never used as material sources");

        var player=player();var machine=new FurnaceScreenHandler(2,player.getInventory(),new SimpleInventory(3),new ArrayPropertyDelegate(4));f=new Fixture(player,machine,3);player.getInventory().setStack(12,new ItemStack(Items.COAL,64));target=targets(3);target.set(1,new ItemStack(Items.COAL,17));plan=execute(f,target,0,NONE,64);check(plan.complete(),"Real furnace fuel slot accepts coal plan");
        target.set(2,new ItemStack(Items.IRON_INGOT));player.getInventory().setStack(13,new ItemStack(Items.IRON_INGOT));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&!plan.blocked().isEmpty(),"Real furnace output slot rejects insertion");
        machine.getSlot(1).setStack(ItemStack.EMPTY);target=targets(3);target.set(1,new ItemStack(Items.DIAMOND));player.getInventory().setStack(14,new ItemStack(Items.DIAMOND));plan=execute(f,target,0,NONE,64);check(plan.clicks().isEmpty()&&!plan.blocked().isEmpty(),"Real fuel validation rejects nonfuel");
        for(int size:new int[]{5,9}){player=player();ScreenHandler handler=size==5?new HopperScreenHandler(3,player.getInventory(),new SimpleInventory(5)):new Generic3x3ContainerScreenHandler(4,player.getInventory(),new SimpleInventory(9));f=new Fixture(player,handler,size);player.getInventory().setStack(30,new ItemStack(Items.STONE,64));target=targets(size);for(int i=0;i<size;i++)target.set(i,new ItemStack(Items.STONE,i+1));plan=execute(f,target,0,NONE,64);check(plan.complete(),"Real hopper/dispenser handler preserves exact per-slot amounts");}
        f=chest(27);var bundle=new ItemStack(Items.BUNDLE);bundle.getOrCreateNbt().putString("custom","identity");f.player.getInventory().setStack(12,bundle.copy());target=targets(27);target.set(0,bundle.copy());plan=execute(f,target,0,NONE,64);check(plan.complete()&&plan.clicks().stream().allMatch(c->c.button()==0),"Vanilla bundle uses left pickup and never its content-modifying right click");
        for(int sourceCount=1;sourceCount<=64;sourceCount++)for(int wanted=1;wanted<=64;wanted++){
            f=chest(27);f.player.getInventory().setStack(12,new ItemStack(Items.STONE,sourceCount));target=targets(27);target.set(0,new ItemStack(Items.STONE,wanted));plan=execute(f,target,0,NONE,64);check(f.handler.getSlot(0).getStack().getCount()==Math.min(sourceCount,wanted),"All legal source/target counts transfer exact available amount");check(plan.complete()==(sourceCount>=wanted),"Completion matches actual target content");
        }
        return checks;
    }
    private static ArrayList<ItemStack> targets(int size){return new ArrayList<>(Collections.nCopies(size,ItemStack.EMPTY));}
    private static Fixture chest(int slots){var player=player();return new Fixture(player,slots==54?GenericContainerScreenHandler.createGeneric9x6(1,player.getInventory(),new SimpleInventory(54)):GenericContainerScreenHandler.createGeneric9x3(1,player.getInventory(),new SimpleInventory(27)),slots);}
    private static int sourceSlot(Fixture f,int index){for(int i=0;i<f.handler.slots.size();i++){var slot=f.handler.slots.get(i);if(slot.inventory==f.player.getInventory()&&slot.getIndex()==index)return i;}throw new AssertionError();}
    private static ContainerFillPlan.Plan execute(Fixture f,List<ItemStack> target,int protectedSlots,Predicate<ItemStack> protection,int budget){
        var before=f.handler.slots.stream().map(s->s.getStack().copy()).toList();var targetBefore=target.stream().map(ItemStack::copy).toList();var totals=totals(before);
        var plan=ContainerFillPlan.plan(f.handler,f.player,target,protectedSlots,protection,budget);
        for(int i=0;i<before.size();i++)check(ItemStack.areEqual(before.get(i),f.handler.slots.get(i).getStack()),"Planning never mutates a live slot");
        for(int i=0;i<target.size();i++)check(ItemStack.areEqual(targetBefore.get(i),target.get(i)),"Planning never mutates source target NBT/count");
        check(f.handler.getCursorStack().isEmpty(),"Planning never acquires the live cursor");
        for(var click:plan.clicks())f.handler.onSlotClick(click.slot(),click.button(),click.action(),f.player);
        check(f.handler.getCursorStack().isEmpty(),"Real vanilla execution leaves no held cursor item");
        for(int i=0;i<before.size();i++)check(ItemStack.areEqual(plan.after().get(i),f.handler.slots.get(i).getStack()),"Real vanilla click result exactly matches planned slot/NBT/count");
        check(totals.equals(totals(f.handler.slots.stream().map(Slot::getStack).toList())),"Actual clicks conserve all item identities/counts without drops");
        return plan;
    }
    private static Map<String,Integer> totals(List<ItemStack> values){var result=new TreeMap<String,Integer>();for(var stack:values)if(!stack.isEmpty())result.merge(net.minecraft.registry.Registries.ITEM.getId(stack.getItem())+"/"+stack.getNbt(),stack.getCount(),Integer::sum);return result;}
    private static TestPlayer player(){var p=allocate(TestPlayer.class);p.inventory=new PlayerInventory(p);p.features=allocate(FeatureWorld.class);return p;}
    private static <T>T allocate(Class<T> type){try{var c=Class.forName("sun.misc.Unsafe");var f=c.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));}catch(Exception e){throw new AssertionError(e);}}
    /** No world constructed or ticked: only feature flags needed by vanilla handleSlotClick are supplied. */
    private static final class FeatureWorld extends ClientWorld {
        private FeatureWorld(){super(null,null,null,null,0,0,null,null,false,0);}
        @Override public FeatureSet getEnabledFeatures(){return FeatureFlags.VANILLA_FEATURES;}
        @Override public RecipeManager getRecipeManager(){return new RecipeManager();}
    }
    private static final class TestPlayer extends PlayerEntity {
        PlayerInventory inventory;World features;
        private TestPlayer(){super(null,BlockPos.ORIGIN,0,new GameProfile(UUID.randomUUID(),"InventoryCheck"));}
        @Override public PlayerInventory getInventory(){return inventory;}
        @Override public World getWorld(){return features;}
        @Override public boolean isSpectator(){return false;}
        @Override public boolean isCreative(){return false;}
        @Override public void onPickupSlotClick(ItemStack a,ItemStack b,ClickType click){}
        @Override public net.minecraft.entity.ItemEntity dropItem(ItemStack stack,boolean random){throw new AssertionError("Planner dropped inventory");}
    }
    public static void main(String[] args){SharedConstants.createGameVersion();Bootstrap.initialize();System.out.println("ContainerFillPlanChecks: "+run()+" checks");}
}
