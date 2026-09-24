package dev.betterlitematica.core;
/** No world chunk loaded -> UNKNOWN, never air. This is a primitive, not a whole-world verifier. */
public enum Comparison {
    MATCH, MISSING, EXTRA, WRONG_BLOCK, WRONG_STATE, UNKNOWN;
    public static Comparison compare(BlockStateSpec expected,BlockStateSpec actual,boolean worldLoaded){
        if(!worldLoaded)return UNKNOWN;
        if(expected==null||actual==null)throw new IllegalArgumentException("Loaded comparison needs both states");
        if(expected.isAir()&&actual.isAir())return MATCH;
        if(expected.equals(actual))return MATCH;
        if(actual.isAir())return MISSING;
        if(expected.isAir())return EXTRA;
        return expected.name().equals(actual.name())?WRONG_STATE:WRONG_BLOCK;
    }
}
