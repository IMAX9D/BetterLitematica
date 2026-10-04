package dev.betterlitematica.fabric;

/** Compact, deliberately non-rounding labels; exact counts remain in the material screen. */
final class HudNumbers {
    private HudNumbers() {}
    static String compact(long count){
        if(count<0)throw new IllegalArgumentException("Negative HUD count");
        if(count<10_000)return Long.toString(count);
        long unit;String suffix;
        if(count>=10_000_000_000_000_000L){unit=10_000_000_000_000_000L;suffix="京";}
        else if(count>=1_000_000_000_000L){unit=1_000_000_000_000L;suffix="兆";}
        else if(count>=100_000_000L){unit=100_000_000L;suffix="亿";}
        else{unit=10_000L;suffix="万";}
        long whole=count/unit,decimal=(count%unit)/(unit/10);
        return whole+(whole<100&&decimal>0?"."+decimal:"")+suffix;
    }
}
