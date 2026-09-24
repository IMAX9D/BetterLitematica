package dev.betterlitematica.core;

/** Position is relative to the main placement origin, initialized from the source's signed-size anchor. */
public record RegionPlacement(Vec3i position,int quarterTurns,boolean mirrorX,boolean mirrorZ,boolean enabled,boolean locked) {
    public RegionPlacement {if(position==null)throw new NullPointerException();quarterTurns=Math.floorMod(quarterTurns,4);}
    public static RegionPlacement original(Region region){return new RegionPlacement(region.anchor(),0,false,false,true,false);}
    public RegionPlacement moved(Vec3i next){if(locked)throw new IllegalStateException("子区域已锁定");return new RegionPlacement(next,quarterTurns,mirrorX,mirrorZ,enabled,locked);}
    public RegionPlacement enabled(boolean value){return new RegionPlacement(position,quarterTurns,mirrorX,mirrorZ,value,locked);}
}
