package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.RenderResources;

/** Distance-ordered residency: pressure removes the farthest mesh, never a nearer one for a farther request. */
public final class DistanceResidencyChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private DistanceResidencyChecks(){}
    private static RenderResources.Group resident(RenderResources budget,double distance,int bytes){
        var group=budget.begin(RenderResources.VISIBLE,distance);var leases=group.reserve(new int[]{bytes},RenderResources.VISIBLE);
        check(leases!=null,"Room was available for distance "+distance);leases.get(0).attach(()->{});group.finish(RenderResources.NEARBY,()->{});return group;
    }
    public static int run(){
        checks=0;
        var budget=new RenderResources(100,100,100,10,2,1);budget.distanceOrdered(true);
        var near=resident(budget,10,30);var middle=resident(budget,20,30);var far=resident(budget,30,30);
        // A farther request than everything resident evicts nothing: turning toward distant sections cannot churn.
        var distant=budget.begin(RenderResources.VISIBLE,40);check(distant.reserve(new int[]{30},RenderResources.VISIBLE)==null,"Full budget rejects a farther mesh");distant.close();
        check(!near.closed()&&!middle.closed()&&!far.closed(),"Nothing nearer was evicted for a farther request");
        check(!budget.admits(35)&&budget.admits(25),"Admission matches the farthest resident mesh");
        // A nearer request evicts the farthest first.
        var closer=budget.begin(RenderResources.VISIBLE,15);check(closer.reserve(new int[]{30},RenderResources.VISIBLE)==null,"Space is reclaimed before reserving");
        check(far.closed()&&!middle.closed()&&!near.closed(),"The farthest mesh is evicted first");
        budget.drain(16,Long.MAX_VALUE);check(closer.reserve(new int[]{30},RenderResources.VISIBLE)!=null,"The nearer mesh fits after release");closer.finish(RenderResources.NEARBY,()->{});
        // Moving the viewer re-sorts residents: the formerly near mesh is now the farthest.
        near.distance(500);var another=budget.begin(RenderResources.VISIBLE,12);check(another.reserve(new int[]{30},RenderResources.VISIBLE)==null,"Pressure again");
        check(near.closed()&&!middle.closed(),"Updated distances decide eviction");another.close();budget.drain(16,Long.MAX_VALUE);
        // Shrinking the budget is reclaimed by the next request; growing it admits more.
        budget.resize(60,100);var squeeze=budget.begin(RenderResources.VISIBLE,1);check(squeeze.reserve(new int[]{20},RenderResources.VISIBLE)==null,"Smaller budget reclaims space");squeeze.close();
        budget.drain(16,Long.MAX_VALUE);budget.resize(1000,1000);check(budget.capacity()==1000&&budget.admits(1e9),"Larger budget admits everything again");
        // Legacy budgets keep their lane policy.
        var legacy=new RenderResources(100,100,100,10,2,1);check(!legacy.distanceOrdered()&&legacy.admits(1e9),"Distance order is opt-in");
        return checks;
    }
}
