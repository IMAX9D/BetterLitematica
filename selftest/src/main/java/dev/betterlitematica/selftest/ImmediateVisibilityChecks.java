package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.Arrays;

public final class ImmediateVisibilityChecks {
    private int checks;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected invalid cell/vertex rejection");}
    public static int run(){var suite=new ImmediateVisibilityChecks();suite.masksAndRanges();suite.ownership();suite.bounds();suite.renderedCells();suite.boundedOverlaps();return suite.checks;}
    private void masksAndRanges(){
        var mask=new QuadVisibility.Mask();check(mask.isEmpty()&&mask.revision()==0,"Mask starts fully visible");
        check(!mask.set(7,false)&&mask.revision()==0,"Identical writes do not invalidate range cache");
        var part=new QuadVisibility.Part(new short[]{0,0,1,2,2,3,0});
        check(part.rangeCount(mask)==1&&part.firstQuad(0)==0&&part.quadCount(0)==7,"Empty mask has one whole-part range");
        check(mask.set(0,true)&&mask.hidden(0)&&mask.revision()==1,"Changed bit advances revision");
        check(part.rangeCount(mask)==1&&part.firstQuad(0)==2&&part.quadCount(0)==4,"Leading/trailing hidden quads leave one contiguous range");
        check(mask.set(2,true)&&part.rangeCount(mask)==2,"Middle hidden quads split visible runs");
        check(part.firstQuad(0)==2&&part.quadCount(0)==1&&part.firstQuad(1)==5&&part.quadCount(1)==1,"Draw ranges preserve exact quad offsets");
        for(int i=0;i<30;i++)check(part.rangeCount(mask)==2&&mask.revision()==2,"Repeated range reads keep unchanged mask revision");
        mask.set(0,false);check(part.rangeCount(mask)==2&&part.firstQuad(0)==0&&part.quadCount(0)==3&&part.firstQuad(1)==5&&part.quadCount(1)==2,"Unmasking rebuilds both visible runs");
        mask.set(2,false);check(mask.isEmpty()&&part.rangeCount(mask)==1&&part.quadCount(0)==7,"Mask reversal restores full part");
        check(part.fullyVisible(mask),"Restored part takes the ordinary full draw path");
        mask.set(4095,true);check(part.fullyVisible(mask),"Hidden cells without geometry do not force a split draw");mask.set(4095,false);
        var other=new QuadVisibility.Mask();other.set(1,true);other.set(2,true);other.set(3,true);other.set(0,true);
        check(part.rangeCount(other)==0,"Different mask identity cannot reuse cached full geometry");
        var sameRevision=new QuadVisibility.Mask();for(int v=0;v<3;v++){sameRevision.set(4095,true);sameRevision.set(4095,false);}
        check(sameRevision.revision()==mask.revision()&&part.fullyVisible(sameRevision),"Equal revisions from a different mask do not reuse stale ranges");
        check(!part.fullyVisible(other),"All hidden has no full draw fast path");
        var empty=new QuadVisibility.Part(new short[0]);check(empty.rangeCount(mask)==0&&empty.rangeCount(other)==0,"Empty parts never emit zero-length draws");
        for(int cell=0;cell<4096;cell++)mask.set(cell,true);
        check(!mask.isEmpty()&&mask.hidden(0)&&mask.hidden(63)&&mask.hidden(64)&&mask.hidden(4095),"Bit mask covers word and final-cell boundaries");
        for(int cell=0;cell<4096;cell++)mask.set(cell,false);
        check(mask.isEmpty(),"All 4096 cells can be restored");
        rejects(()->mask.set(-1,true));rejects(()->mask.hidden(4096));
    }
    private void ownership(){
        var builder=new QuadVisibility.Builder();builder.append(7,4);builder.append(8,2);builder.append(9,2);builder.append(10,12);
        var owners=builder.build(20);check(Arrays.equals(owners,new short[]{7,-1,10,10,10}),"Mixed four-vertex ownership produces unhideable sentinel");
        var part=new QuadVisibility.Part(owners);owners[0]=12;
        var mask=new QuadVisibility.Mask();mask.set(7,true);mask.set(8,true);mask.set(9,true);mask.set(10,true);
        check(part.rangeCount(mask)==1&&part.firstQuad(0)==1&&part.quadCount(0)==1,"Mixed quad remains visible and Part defensively copies owners");
        builder.clear();builder.append(4095,1);builder.append(4095,1);builder.append(4095,2);
        check(Arrays.equals(builder.build(4),new short[]{4095}),"Four coincident light vertices use explicit cell, never position");
        var light=new QuadVisibility.Part(builder.build(4));mask.set(4095,true);check(light.rangeCount(mask)==0,"Light billboard hides all four center vertices together");
        var snapshot=builder.build(4);snapshot[0]=0;check(builder.build(4)[0]==4095,"Returned array cannot mutate Builder");
        builder.clear();builder.append(1,3);builder.append(2,6);builder.append(3,3);
        check(Arrays.equals(builder.build(12),new short[]{-1,2,-1}),"Appends spanning quad boundaries preserve sole complete owner");
        builder.clear();builder.append(-1,4);check(builder.build(4)[0]==-1,"Explicitly unowned quads stay visible");
    }
    private void bounds(){
        var builder=new QuadVisibility.Builder();rejects(()->builder.append(4096,4));rejects(()->builder.append(-2,4));rejects(()->builder.append(0,-1));
        builder.append(0,3);rejects(()->builder.build(3));rejects(()->builder.build(4));builder.append(0,1);check(builder.build(4).length==1,"Rejected build leaves partial quad usable");
        builder.clear();builder.append(0,16384);rejects(()->builder.append(1,1));check(builder.build(16384).length==4096,"Vertex hard limit is exact and failed append is atomic");
        builder.clear();check(builder.build(0).length==0,"Clear forgets prior maximum-size build");
        short[] alternating=new short[4096];for(int i=0;i<alternating.length;i++)alternating[i]=(short)(i&1);
        var part=new QuadVisibility.Part(alternating);var mask=new QuadVisibility.Mask();mask.set(0,true);check(part.rangeCount(mask)==2048,"Worst alternating ownership stays within bounded segment storage");
        for(int i=0;i<2048;i++)check(part.firstQuad(i)==i*2+1&&part.quadCount(i)==1,"Every alternating visible quad appears exactly once");
        check(part.memoryEstimate()>=4096L*2+4096L*2*4,"Memory estimate includes owners and worst-case allocated ranges");
        rejects(()->new QuadVisibility.Part(new short[4097]));rejects(()->new QuadVisibility.Part(new short[]{4096}));rejects(()->new QuadVisibility.Part(new short[]{-2}));
    }
    private void renderedCells(){
        var builder=new RenderedCells.Builder();builder.record(4095,123456);builder.record(17,0);builder.record(2,98);builder.record(17,234);
        var cells=builder.build();check(cells.size()==3&&cells.state(2)==98&&cells.state(17)==234&&cells.state(4095)==123456,"Sparse cells sort before binary lookup and duplicate records replace state");
        check(cells.state(0)==-1&&cells.state(16)==-1&&cells.state(18)==-1,"Unrendered cells never imply raw state zero");
        check(cells.estimatedBytes()<1024,"Sparse finished mesh does not retain 16KiB builder array");
        builder.record(17,999);builder.clear();check(cells.state(17)==234&&builder.build().size()==0,"Built sparse state owns immutable independent arrays");
        builder.record(0,0);check(builder.build().state(0)==0,"Raw air ID zero is distinct from missing");
        rejects(()->builder.record(-1,1));rejects(()->builder.record(4096,1));rejects(()->builder.record(0,-1));rejects(()->cells.state(4096));
        builder.clear();for(int cell=4095;cell>=0;cell--)builder.record(cell,cell*3);
        var dense=builder.build();check(dense.size()==4096&&dense.estimatedBytes()>=4096L*6,"Dense output accounts for both packed cell and raw ID arrays");
        for(int cell=0;cell<4096;cell++)check(dense.state(cell)==cell*3,"Every source-cell raw state roundtrips exactly");
    }
    private void boundedOverlaps(){
        var regions=new java.util.ArrayList<Region>();for(int i=0;i<512;i++)regions.add(new Region("r"+i,Vec3i.ZERO,new Vec3i(16,16,16)));
        var placement=new Placement(java.util.UUID.randomUUID(),"overlap","test.litematic",new PlacementTransform(new Vec3i(-32,5,19),1,true,false),true,false);
        var layout=new PlacementLayout(placement,regions);var point=placement.transform().apply(new Vec3i(7,8,9));
        check(layout.at(point,64)==null,"High-overlap callback reports overflow without collecting every region");
        check(layout.at(point,512).equals(layout.at(point)),"Exact overlap limit keeps all ordered transformed source regions");
        check(layout.at(point,511)==null,"One result beyond capacity explicitly reports incomplete lookup");
        check(layout.at(new Vec3i(1000,1000,1000),1).isEmpty(),"Disjoint lookup is complete even with a small budget");
        rejects(()->layout.at(point,0));
        var broad=new java.util.ArrayList<Region>();for(int i=0;i<2048;i++)broad.add(new Region("b"+i,new Vec3i(-100000+i*32,(i&1)==0?-2:1,-100000),new Vec3i(200000,1,200000)));
        var deceptive=new PlacementLayout(new Placement(java.util.UUID.randomUUID(),"bounds","test.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false),broad);
        check(deceptive.at(Vec3i.ZERO).isEmpty(),"Adversarial bounds overlap the query while every leaf misses it");
        check(deceptive.at(Vec3i.ZERO,64)==null,"Node budget stops broad internal bounds even before any source region is matched");

        var scattered=new java.util.ArrayList<Region>();for(int i=0;i<512;i++)scattered.add(new Region("s"+i,new Vec3i(i*32,0,0),new Vec3i(16,16,16)));
        var spread=new PlacementLayout(placement,scattered);var single=placement.transform().apply(new Vec3i(200*32+7,8,9));
        check(spread.at(single,1).equals(spread.at(single)),"Large disjoint layouts keep immediate point updates within the local result budget");
    }
    public static void main(String[] args){System.out.println("ImmediateVisibilityChecks: "+run()+" checks passed");}
}
