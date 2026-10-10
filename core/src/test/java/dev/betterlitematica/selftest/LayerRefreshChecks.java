package dev.betterlitematica.selftest;

import dev.betterlitematica.core.LayerRange;
import java.util.Random;

/** Incremental layer refresh must rebuild exactly the sections whose cells or culling neighbours changed. */
public final class LayerRefreshChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private LayerRefreshChecks(){}
    private static boolean in(LayerRange range,long value){return value>=range.min()&&value<=range.max();}
    private static LayerRange random(Random r,LayerRange.Axis axis){
        int a=r.nextInt(80)-40,b=a+r.nextInt(12);
        return switch(r.nextInt(5)){case 0->LayerRange.of(axis,LayerRange.Mode.ALL,0,0);case 1->LayerRange.of(axis,LayerRange.Mode.SINGLE,a,a);case 2->LayerRange.of(axis,LayerRange.Mode.ABOVE,a,a);case 3->LayerRange.of(axis,LayerRange.Mode.BELOW,a,a);default->LayerRange.of(axis,LayerRange.Mode.RANGE,a,b);};
    }
    public static int run(){
        checks=0;var r=new Random(20261010);
        for(int round=0;round<200000;round++){
            var axis=LayerRange.Axis.values()[r.nextInt(3)];var before=random(r,axis);var after=random(r,r.nextInt(8)==0?LayerRange.Axis.values()[r.nextInt(3)]:axis);
            var shared=LayerRange.sharedAxis(before,after);
            if(shared==null){check(before.axis()!=after.axis()&&before.mode()!=LayerRange.Mode.ALL&&after.mode()!=LayerRange.Mode.ALL,"Only crossing axes force a full refresh");continue;}
            int first=(r.nextInt(8)-4)*16,last=first+15;
            boolean expected=false;for(long c=(long)first-1;c<=(long)last+1;c++)if(in(before,c)!=in(after,c)){expected=true;break;}
            check(LayerRange.affects(before,after,first,last)==expected,"Refresh decision equals per-cell membership comparison: "+before+" -> "+after+" ["+first+","+last+"]");
        }
        // Following the player one block up rebuilds only the sections next to the old and new boundary.
        var below=LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.BELOW,70,70);var next=below.shifted(1);int touched=0;
        for(int section=-4;section<20;section++)if(LayerRange.affects(below,next,section*16,section*16+15))touched++;
        check(touched<=2,"A one-block move touches at most the sections around the boundary: "+touched);
        check(!LayerRange.affects(LayerRange.ALL,LayerRange.ALL,Integer.MIN_VALUE,Integer.MAX_VALUE),"Equal unbounded ranges need nothing");
        return checks;
    }
}
