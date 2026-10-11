package dev.betterlitematica.fabric;
import org.joml.Matrix4f;
/** Per-placement clip state, copied before GPU submission. */
record ProjectionClip(Matrix4f inverse,float x,float y,float z,float axis,float from,float to,float feather){
 static ProjectionClip current=new ProjectionClip(new Matrix4f(),0,0,0,-1,0,0,0);
 static void configure(Matrix4f view,double x,double y,double z,int axis,double from,double to,double feather){current=new ProjectionClip(new Matrix4f(view).invert(),(float)x,(float)y,(float)z,axis,(float)Math.max(-1e9,from),(float)Math.min(1e9,to),(float)feather);}
 void write(java.nio.ByteBuffer buffer,int at){inverse.get(at,buffer);buffer.putFloat(at+64,x).putFloat(at+68,y).putFloat(at+72,z).putFloat(at+76,0);buffer.putFloat(at+80,axis).putFloat(at+84,from).putFloat(at+88,to).putFloat(at+92,feather);buffer.putFloat(at+96,.75f).putFloat(at+100,2.5f).putFloat(at+104,0).putFloat(at+108,0);}
}
