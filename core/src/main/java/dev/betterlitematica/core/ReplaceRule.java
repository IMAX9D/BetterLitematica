package dev.betterlitematica.core;

public enum ReplaceRule {
    NONE, NON_AIR, ALL;
    public boolean permits(boolean expectedAir,boolean actualAir){return switch(this){case NONE->actualAir;case NON_AIR->!expectedAir;case ALL->true;};}
}
