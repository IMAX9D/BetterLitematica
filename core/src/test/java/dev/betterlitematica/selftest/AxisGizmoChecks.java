package dev.betterlitematica.selftest;

import dev.betterlitematica.core.AxisGizmoMath;
import dev.betterlitematica.core.AxisGizmoMath.Point;
import dev.betterlitematica.core.Vec3i;

/** Analytic examples, axis symmetry and drag stability; no game or duplicated picker. */
public final class AxisGizmoChecks {
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    private static void near(double actual,double expected,String text){check(Double.isFinite(actual)&&Math.abs(actual-expected)<1e-7,text+": "+actual);}
    private static void rejected(Runnable call){try{call.run();throw new AssertionError("Invalid offset accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static Point permute(Point p,int axis){return switch(axis){case 0->p;case 1->new Point(p.y(),p.x(),p.z());default->new Point(p.z(),p.y(),p.x());};}
    public static int run(){
        checks=0;Point zero=new Point(0,0,0);
        for(int axis=0;axis<3;axis++){
            Point eye=permute(new Point(1,0,-5),axis),direction=permute(new Point(0,0,1),axis);
            near(AxisGizmoMath.hit(eye,direction,zero,axis,2,.1),4.9,"Cylinder front surface");
            near(AxisGizmoMath.parameter(eye,direction,zero,axis),1,"Perpendicular drag coordinate");
            near(AxisGizmoMath.hit(eye,direction.multiply(1e300),zero,axis,2,.1),4.9,"Ray scale does not change world distance");
            near(AxisGizmoMath.hit(eye,direction.multiply(1e-300),zero,axis,2,.1),4.9,"Tiny nonzero ray normalizes");
            Point translated=new Point(-30000000,-39,-170);
            near(AxisGizmoMath.hit(eye.add(translated),direction,translated,axis,2,.1),4.9,"Negative world translation invariant");
            near(AxisGizmoMath.parameter(eye.add(translated),direction,translated,axis),1,"Negative origin drag invariant");
            check(Double.isNaN(AxisGizmoMath.hit(eye,direction.multiply(-1),zero,axis,2,.1)),"Behind ray is not a hit");
            near(AxisGizmoMath.hit(permute(new Point(0,0,0),axis),permute(new Point(1,0,0),axis),zero,axis,2,.1),.3,"Low capsule cap");
            near(AxisGizmoMath.hit(permute(new Point(3,0,0),axis),permute(new Point(-1,0,0),axis),zero,axis,2,.1),.9,"High capsule cap");
            near(AxisGizmoMath.hit(permute(new Point(1,0,0),axis),direction,zero,axis,2,.1),0,"Inside handle");
            check(Double.isNaN(AxisGizmoMath.hit(permute(new Point(.1,0,-5),axis),direction,zero,axis,2,.1)),"Dead space near origin is not shaft");
            near(AxisGizmoMath.hit(permute(new Point(1,.1,-5),axis),direction,zero,axis,2,.1),5,"Tangent contact");
            near(AxisGizmoMath.parameter(permute(new Point(-2,3,0),axis),permute(new Point(1,-1,0),axis),zero,axis),1,"Oblique drag closest point");
            near(AxisGizmoMath.parameter(permute(new Point(-2,3,0),axis),permute(new Point(1,1,0),axis),zero,axis),-2,"Behind-eye nearest point clamps to forward ray");
            check(Double.isNaN(AxisGizmoMath.parameter(eye,permute(new Point(1,0,0),axis),zero,axis)),"Parallel axis has no stable drag");
            check(Double.isNaN(AxisGizmoMath.parameter(eye,permute(new Point(1,1e-6,0),axis),zero,axis)),"Almost parallel also rejected");
            check(Double.isFinite(AxisGizmoMath.parameter(eye,permute(new Point(1,.001,0),axis),zero,axis)),"Sufficient angle remains usable");
            Vec3i offset=AxisGizmoMath.offset(axis,-12);check(offset.x()+offset.y()+offset.z()==-12&&switch(axis){case 0->offset.x()==-12;case 1->offset.y()==-12;default->offset.z()==-12;},"Offset changes only selected axis");
        }
        int value=0;
        for(double d:new double[]{-.01,.01,-.49,.49,-.59,.59}){value=AxisGizmoMath.snapped(d,value);check(value==0,"Zero dead band has no negative bias");}
        check(AxisGizmoMath.snapped(.61,0)==1&&AxisGizmoMath.snapped(-.61,0)==-1,"Exit dead band symmetrically");
        for(double d:new double[]{.45,.51,.55,.41})check(AxisGizmoMath.snapped(d,1)==1,"Returning through midpoint does not flicker");
        check(AxisGizmoMath.snapped(.39,1)==0&&AxisGizmoMath.snapped(-.39,-1)==0,"Return threshold releases");
        for(int i=-20;i<=20;i++){
            near(AxisGizmoMath.snapped(i,i),i,"Exact grids remain stable");
            check(AxisGizmoMath.snapped(i+.6,i)==i&&AxisGizmoMath.snapped(i-.6,i)==i,"Inclusive hysteresis threshold survives floating subtraction");
            check(AxisGizmoMath.snapped(i+.7,i)==-AxisGizmoMath.snapped(-i-.7,-i),"Snap reflection symmetry");
        }
        check(AxisGizmoMath.snapped(12.5,0)==13&&AxisGizmoMath.snapped(-12.5,0)==-13,"Large jumps and ties symmetric");
        check(AxisGizmoMath.snapped(Integer.MAX_VALUE,0)==Integer.MAX_VALUE&&AxisGizmoMath.snapped(Integer.MIN_VALUE,0)==Integer.MIN_VALUE,"Integer endpoints do not wrap");
        rejected(()->AxisGizmoMath.snapped(Double.NaN,0));rejected(()->AxisGizmoMath.snapped(Double.POSITIVE_INFINITY,0));
        rejected(()->AxisGizmoMath.snapped((double)Integer.MAX_VALUE+1,0));rejected(()->AxisGizmoMath.snapped((double)Integer.MIN_VALUE-1,0));rejected(()->AxisGizmoMath.offset(3,1));
        for(Point invalid:new Point[]{zero,new Point(Double.NaN,0,0),new Point(Double.POSITIVE_INFINITY,0,0)}){
            check(Double.isNaN(AxisGizmoMath.hit(new Point(1,0,-5),invalid,zero,0,2,.1)),"Invalid ray misses");
            check(Double.isNaN(AxisGizmoMath.parameter(new Point(1,0,-5),invalid,zero,0)),"Invalid drag ray rejected");
        }
        check(Double.isNaN(AxisGizmoMath.hit(zero,new Point(1,0,0),zero,-1,2,.1)),"Invalid geometry axis");
        check(Double.isNaN(AxisGizmoMath.hit(zero,new Point(1,0,0),zero,0,-2,.1)),"Negative length rejected");
        check(Double.isNaN(AxisGizmoMath.hit(zero,new Point(1,0,0),zero,0,2,0)),"Zero hit radius rejected");
        return checks;
    }
    public static void main(String[] args){System.out.println("AxisGizmoChecks: "+run()+" checks");}
}
