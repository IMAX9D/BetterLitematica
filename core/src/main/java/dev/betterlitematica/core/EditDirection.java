package dev.betterlitematica.core;

/** Face-center follows the normal; outer face quarters select an in-plane direction. */
public final class EditDirection {
    private EditDirection(){}
    public static Vec3i choose(Vec3i face,double x,double y,double z,Vec3i heading,boolean place){
        Vec3i u,v;if(face.y()!=0){v=heading;u=new Vec3i(-heading.z(),0,heading.x());}else{u=face.x()!=0?new Vec3i(0,0,1):new Vec3i(1,0,0);v=new Vec3i(0,1,0);}
        double a=(x-.5)*u.x()+(y-.5)*u.y()+(z-.5)*u.z(),b=(x-.5)*v.x()+(y-.5)*v.y()+(z-.5)*v.z();
        if(Math.abs(a)<=.25&&Math.abs(b)<=.25)return place?face:new Vec3i(-face.x(),-face.y(),-face.z());
        var axis=Math.abs(a)>Math.abs(b)?u:v;double sign=Math.abs(a)>Math.abs(b)?a:b;int d=sign<0?-1:1;return new Vec3i(axis.x()*d,axis.y()*d,axis.z()*d);
    }
}
