package dev.betterlitematica.fabric;
final class CommandSettings {
    int perTick=8,interval=1,fillVolume=32768;
    boolean merge=true;
    CommandSettings copy(){var value=new CommandSettings();value.perTick=perTick;value.interval=interval;value.fillVolume=fillVolume;value.merge=merge;return value;}
    void validate(){if(perTick<1||perTick>256||interval<1||interval>100||fillVolume<1||fillVolume>32768)throw new IllegalArgumentException("命令参数超出范围");}
}
