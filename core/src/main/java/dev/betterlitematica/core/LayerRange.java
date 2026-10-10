package dev.betterlitematica.core;
public record LayerRange(Axis axis, int min, int max) {
    public enum Axis { X,Y,Z }
    public enum Mode {ALL,SINGLE,RANGE,ABOVE,BELOW}
    public Mode mode(){return min==Integer.MIN_VALUE&&max==Integer.MAX_VALUE?Mode.ALL:min==max?Mode.SINGLE:min==Integer.MIN_VALUE?Mode.BELOW:max==Integer.MAX_VALUE?Mode.ABOVE:Mode.RANGE;}
    public static LayerRange of(Axis axis,Mode mode,int first,int second){return switch(mode){case ALL->new LayerRange(axis,Integer.MIN_VALUE,Integer.MAX_VALUE);case SINGLE->new LayerRange(axis,first,first);case ABOVE->new LayerRange(axis,first,Integer.MAX_VALUE);case BELOW->new LayerRange(axis,Integer.MIN_VALUE,first);case RANGE->new LayerRange(axis,first,second);};}
    public LayerRange shifted(int amount){if(mode()==Mode.ALL)return this;return new LayerRange(axis,min==Integer.MIN_VALUE?min:Math.addExact(min,amount),max==Integer.MAX_VALUE?max:Math.addExact(max,amount));}
    public static final LayerRange ALL = new LayerRange(Axis.Y,Integer.MIN_VALUE,Integer.MAX_VALUE);
    public LayerRange { if(axis==null||min>max)throw new IllegalArgumentException("Invalid layer range"); }
    /**
     * The axis along which two ranges can be compared section by section, or {@code null} when they cut along
     * different axes and every section may change. A range covering everything matches any axis.
     */
    public static Axis sharedAxis(LayerRange before,LayerRange after){
        if(before.mode()==Mode.ALL)return after.axis;if(after.mode()==Mode.ALL)return before.axis;
        return before.axis==after.axis?before.axis:null;
    }
    /**
     * Whether a mesh spanning [{@code first}, {@code last}] along {@link #sharedAxis} must be rebuilt: some cell in
     * it, or a neighbour one step outside whose membership decides face culling, is inside one range but not the
     * other. Moving a boundary by one block therefore touches only the sections next to the old and new planes.
     */
    public static boolean affects(LayerRange before,LayerRange after,int first,int last){
        long from=(long)Math.min(first,last)-1,to=(long)Math.max(first,last)+1;
        long b0=Math.max(from,before.min),b1=Math.min(to,before.max),a0=Math.max(from,after.min),a1=Math.min(to,after.max);
        boolean beforeEmpty=b0>b1,afterEmpty=a0>a1;
        return beforeEmpty!=afterEmpty||!beforeEmpty&&(b0!=a0||b1!=a1);
    }
    public boolean contains(Vec3i position) {
        int v=switch(axis){case X -> position.x();case Y -> position.y();case Z -> position.z();};return v>=min&&v<=max;
    }
}
