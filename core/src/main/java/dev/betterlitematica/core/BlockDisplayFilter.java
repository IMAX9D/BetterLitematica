package dev.betterlitematica.core;

import java.util.*;
import java.util.regex.Pattern;

/** Visual block-type selection only; never removes source data or construction requirements. */
public record BlockDisplayFilter(Mode mode,Set<String> blockIds) {
    public enum Mode { OFF, BLACKLIST, WHITELIST }
    public static final int MAX_IDS=65536,MAX_ID_LENGTH=1024,MAX_ID_CHARACTERS=1<<20;
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final BlockDisplayFilter OFF=new BlockDisplayFilter(Mode.OFF,Set.of());

    public BlockDisplayFilter {
        Objects.requireNonNull(mode,"mode");Objects.requireNonNull(blockIds,"blockIds");
        if(blockIds.size()>MAX_IDS)throw new IllegalArgumentException("Too many display-filter blocks");
        var copy=new TreeSet<String>();int characters=0;
        for(String input:blockIds){
            String id=canonicalId(input);
            if(copy.add(id)){characters+=id.length();if(characters>MAX_ID_CHARACTERS)throw new IllegalArgumentException("Display-filter block IDs exceed budget");}
        }
        // Stable serialization order, with constant-time membership on the rendering path.
        blockIds=Collections.unmodifiableSet(new LinkedHashSet<>(copy));
    }
    public static String canonicalId(String id){
        Objects.requireNonNull(id,"block ID");
        if(id.length()>MAX_ID_LENGTH)throw new IllegalArgumentException("Display-filter block ID too long");
        if(id.indexOf(':')<0)id="minecraft:"+id;
        if(id.length()>MAX_ID_LENGTH||!ID.matcher(id).matches())throw new IllegalArgumentException("Invalid display-filter block ID");
        return id;
    }
    /** Input is a canonical registry ID. No parsing or allocation is performed per rendered block. */
    public boolean allows(String canonicalBlockId){return mode==Mode.OFF||(blockIds.contains(canonicalBlockId)==(mode==Mode.WHITELIST));}
    public boolean allows(BlockStateSpec state){return allows(state.name());}
    public BlockDisplayFilter mode(Mode next){return next==mode?this:new BlockDisplayFilter(next,blockIds);}
}
