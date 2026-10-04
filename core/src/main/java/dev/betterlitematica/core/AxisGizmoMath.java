package dev.betterlitematica.core;

/** Pure world-space geometry for an axis handle. No camera, world or input ownership. */
public final class AxisGizmoMath {
    private static final double PARALLEL_SIN_SQUARED=1e-8;
    private AxisGizmoMath(){}
    public record Point(double x,double y,double z){
        public Point add(Point p){return new Point(x+p.x,y+p.y,z+p.z);}
        public Point subtract(Point p){return new Point(x-p.x,y-p.y,z-p.z);}
        public Point multiply(double scale){return new Point(x*scale,y*scale,z*scale);}
        public double dot(Point p){return x*p.x+y*p.y+z*p.z;}
        public boolean finite(){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z);}
        public double component(int axis){return switch(axis){case 0->x;case 1->y;case 2->z;default->throw new IllegalArgumentException("Invalid axis");};}
    }
    private static Point normalized(Point direction){
        if(direction==null||!direction.finite())return null;
        double scale=Math.max(Math.abs(direction.x),Math.max(Math.abs(direction.y),Math.abs(direction.z)));
        if(scale==0)return null;
        var scaled=new Point(direction.x/scale,direction.y/scale,direction.z/scale);
        return scaled.multiply(1/Math.sqrt(scaled.dot(scaled)));
    }
    private static Point perpendicular(Point p,int axis){return switch(axis){case 0->new Point(0,p.y,p.z);case 1->new Point(p.x,0,p.z);default->new Point(p.x,p.y,0);};}
    private static boolean valid(Point eye,Point origin,int axis){return axis>=0&&axis<3&&eye!=null&&origin!=null&&eye.finite()&&origin.finite();}

    /** First distance along a normalized forward ray into the capsule from 0.2*length to length.
     * Inside returns zero; invalid geometry and misses return NaN. Radius includes both end caps. */
    public static double hit(Point eye,Point direction,Point origin,int axis,double length,double radius){
        if(!valid(eye,origin,axis)||!Double.isFinite(length)||!Double.isFinite(radius)||length<=0||radius<=0)return Double.NaN;
        Point d=normalized(direction),q=eye.subtract(origin);if(d==null||!q.finite())return Double.NaN;
        double low=.2*length,high=length,qa=q.component(axis),da=d.component(axis),r2=radius*radius;
        if(!Double.isFinite(r2))return Double.NaN;
        Point qp=perpendicular(q,axis),dp=perpendicular(d,axis);
        double endDistance=qa<low?low-qa:qa>high?qa-high:0;
        if(qp.dot(qp)+endDistance*endDistance<=r2)return 0;
        double best=Double.POSITIVE_INFINITY,a=dp.dot(dp);
        if(a>0){
            double center=-qp.dot(dp)/a;
            Point nearest=qp.add(dp.multiply(center));double remaining=r2-nearest.dot(nearest);
            if(remaining>=0&&Double.isFinite(center)){
                double half=Math.sqrt(remaining/a);
                for(double t:new double[]{center-half,center+half}){
                    double along=qa+t*da;
                    if(t>=0&&Double.isFinite(t)&&along>=low&&along<=high)best=Math.min(best,t);
                }
            }
        }
        for(double end:new double[]{low,high}){
            Point center=axisPoint(axis,end),relative=q.subtract(center);
            double t=-relative.dot(d);Point nearest=relative.add(d.multiply(t));double remaining=r2-nearest.dot(nearest);
            if(remaining>=0&&Double.isFinite(t)){
                double half=Math.sqrt(remaining),first=t-half,second=t+half;
                if(first>=0&&Double.isFinite(first))best=Math.min(best,first);
                else if(second>=0&&Double.isFinite(second))best=Math.min(best,second);
            }
        }
        return Double.isFinite(best)?best:Double.NaN;
    }
    /** Axis coordinate of the closest pair between the infinite axis and forward ray.
     * Nearly parallel directions (sin² <= 1e-8) are deliberately unstable and return NaN.
     * If the unconstrained ray point is behind the eye, uses the eye's axis projection. */
    public static double parameter(Point eye,Point direction,Point origin,int axis){
        if(!valid(eye,origin,axis))return Double.NaN;
        Point d=normalized(direction),q=eye.subtract(origin);if(d==null||!q.finite())return Double.NaN;
        Point dp=perpendicular(d,axis);double a=dp.dot(dp);if(a<=PARALLEL_SIN_SQUARED)return Double.NaN;
        double ray=Math.max(0,-perpendicular(q,axis).dot(dp)/a);
        double value=q.component(axis)+ray*d.component(axis);
        return Double.isFinite(value)?value:Double.NaN;
    }
    /** Keep the previous grid value through its inclusive +/-0.6 cell band.
     * Outside it, round to nearest integer, with half-cell ties away from zero. */
    public static int snapped(double delta,int previous){
        if(!Double.isFinite(delta)||delta<Integer.MIN_VALUE||delta>Integer.MAX_VALUE)throw new IllegalArgumentException("Axis offset out of range");
        if(delta>=(double)previous-.6&&delta<=(double)previous+.6)return previous;
        double value=Math.copySign(Math.floor(Math.abs(delta)+.5),delta);
        if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Axis offset out of range");
        return (int)value;
    }
    private static Point axisPoint(int axis,double amount){return switch(axis){case 0->new Point(amount,0,0);case 1->new Point(0,amount,0);case 2->new Point(0,0,amount);default->throw new IllegalArgumentException("Invalid axis");};}
    public static Vec3i offset(int axis,int amount){return switch(axis){case 0->new Vec3i(amount,0,0);case 1->new Vec3i(0,amount,0);case 2->new Vec3i(0,0,amount);default->throw new IllegalArgumentException("Invalid axis");};}
}
