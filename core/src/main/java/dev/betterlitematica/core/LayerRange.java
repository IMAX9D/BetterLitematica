package dev.betterlitematica.core;
public record LayerRange(Axis axis, int min, int max) {
    public enum Axis { X,Y,Z }
    public enum Mode {ALL,SINGLE,RANGE,ABOVE,BELOW}
    public Mode mode(){return min==Integer.MIN_VALUE&&max==Integer.MAX_VALUE?Mode.ALL:min==max?Mode.SINGLE:min==Integer.MIN_VALUE?Mode.BELOW:max==Integer.MAX_VALUE?Mode.ABOVE:Mode.RANGE;}
    public static LayerRange of(Axis axis,Mode mode,int first,int second){return switch(mode){case ALL->new LayerRange(axis,Integer.MIN_VALUE,Integer.MAX_VALUE);case SINGLE->new LayerRange(axis,first,first);case ABOVE->new LayerRange(axis,first,Integer.MAX_VALUE);case BELOW->new LayerRange(axis,Integer.MIN_VALUE,first);case RANGE->new LayerRange(axis,first,second);};}
    public LayerRange shifted(int amount){if(mode()==Mode.ALL)return this;return new LayerRange(axis,min==Integer.MIN_VALUE?min:Math.addExact(min,amount),max==Integer.MAX_VALUE?max:Math.addExact(max,amount));}
    public static final LayerRange ALL = new LayerRange(Axis.Y,Integer.MIN_VALUE,Integer.MAX_VALUE);
    public LayerRange { if(axis==null||min>max)throw new IllegalArgumentException("Invalid layer range"); }
    public boolean contains(Vec3i position) {
        int v=switch(axis){case X -> position.x();case Y -> position.y();case Z -> position.z();};return v>=min&&v<=max;
    }
}
