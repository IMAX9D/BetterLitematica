package dev.betterlitematica.fabric;

import dev.betterlitematica.core.LayerRange;

enum WheelRenderMode {
    ALL("全部"), SINGLE("单层"), ABOVE("此层之上"), BELOW("此层之下"), RANGE("范围"),
    PLAYER_ABOVE("玩家之上"), PLAYER_BELOW("玩家之下");
    private static final int LIMIT=30_000_000;
    private final String label;
    WheelRenderMode(String label){this.label=label;}
    String label(){return label;}
    boolean editable(){return this==SINGLE||this==ABOVE||this==BELOW||this==RANGE;}
    boolean follows(){return this==PLAYER_ABOVE||this==PLAYER_BELOW;}
    static int step(boolean control,boolean shift){return control&&shift?20:shift?10:control?5:1;}
    static int coordinate(String text){
        try{int value=Integer.parseInt(text.strip());if(value < -LIMIT||value > LIMIT)throw new NumberFormatException();return value;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("层数须为 -30000000 至 30000000 的整数");}
    }
    record Coordinates(int first,int second){}
    static Coordinates shifted(WheelRenderMode mode,int first,int second,int amount){
        if(mode==null||!mode.editable())throw new IllegalArgumentException("当前模式不能调整层数");
        mode.range(first,second,0);
        int a=coordinate(Long.toString((long)first+amount));
        int b=mode==RANGE?coordinate(Long.toString((long)second+amount)):a;
        return new Coordinates(a,b);
    }
    LayerRange range(int first,int second,int playerY){
        if(this==ALL)return LayerRange.ALL;
        int value=coordinate(Integer.toString(follows()?playerY:first));
        return switch(this){
            case SINGLE->LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.SINGLE,value,value);
            case ABOVE,PLAYER_ABOVE->LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.ABOVE,value,value);
            case BELOW,PLAYER_BELOW->LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.BELOW,value,value);
            case RANGE->{int last=coordinate(Integer.toString(second));if(value>last)throw new IllegalArgumentException("起始层不能大于结束层");yield LayerRange.of(LayerRange.Axis.Y,LayerRange.Mode.RANGE,value,last);}
            default->throw new IllegalStateException();
        };
    }
    static WheelRenderMode selected(LayerRange layer,boolean follow,WheelRenderMode preferred){
        return switch(layer.mode()){
            case ALL->ALL;
            case SINGLE->preferred==RANGE?RANGE:SINGLE;
            case RANGE->RANGE;
            case ABOVE->follow&&layer.axis()==LayerRange.Axis.Y?PLAYER_ABOVE:ABOVE;
            case BELOW->follow&&layer.axis()==LayerRange.Axis.Y?PLAYER_BELOW:BELOW;
        };
    }
}
