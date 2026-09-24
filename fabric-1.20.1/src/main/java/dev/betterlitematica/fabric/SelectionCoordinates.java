package dev.betterlitematica.fabric;
import dev.betterlitematica.core.Vec3i;
final class SelectionCoordinates {
    private SelectionCoordinates(){}
    static String encode(Vec3i value){return value.x()+" "+value.y()+" "+value.z();}
    static Vec3i decode(String text){try{if(text==null||text.length()>128)throw new IllegalArgumentException();String[] p=text.strip().split("[ ,;]+",-1);if(p.length!=3)throw new IllegalArgumentException();return new Vec3i(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]));}catch(RuntimeException e){throw new IllegalArgumentException("剪贴板中没有有效坐标");}}
}
