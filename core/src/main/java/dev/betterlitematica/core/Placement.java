package dev.betterlitematica.core;

import java.util.*;

/** An independent instance of a source blueprint; its identity survives renames and reloads. */
public record Placement(UUID id, String name, String source, PlacementTransform transform,
                        boolean enabled, boolean locked, float opacity,Map<String,RegionPlacement> regions,boolean renderBlocks,int lockedAxes,ReplaceRule overlapRule) {
    public Placement(UUID id,String name,String source,PlacementTransform transform,boolean enabled,boolean locked,float opacity){this(id,name,source,transform,enabled,locked,opacity,Map.of(),true,0,ReplaceRule.ALL);}
    public Placement(UUID id,String name,String source,PlacementTransform transform,boolean enabled,boolean locked){this(id,name,source,transform,enabled,locked,0.45f);}
    public Placement {
        if(!Float.isFinite(opacity)||opacity<0.05f||opacity>1f)throw new IllegalArgumentException("Opacity must be 0.05-1");
        Objects.requireNonNull(id); Objects.requireNonNull(transform);
        Objects.requireNonNull(overlapRule);if(lockedAxes<0||lockedAxes>7||regions.size()>1024)throw new IllegalArgumentException("Placement settings limit");regions=Collections.unmodifiableMap(new LinkedHashMap<>(regions));for(var entry:regions.entrySet())if(entry.getKey().isBlank()||entry.getKey().length()>256||entry.getValue()==null)throw new IllegalArgumentException("Invalid region override");
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Placement name must be 1-120 characters");
        if (source == null || source.isBlank() || source.length() > 1024) throw new IllegalArgumentException("Invalid blueprint path");
        source = source.replace('\\', '/');
        if (source.startsWith("/") || source.contains(":") || source.indexOf('\0') >= 0) throw new IllegalArgumentException("Blueprint path must be relative");
        for (String part : source.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid blueprint path component");
        }
    }
    public boolean sameGeometry(Placement other){return other!=null&&source.equals(other.source)&&transform.equals(other.transform)&&regions.equals(other.regions)&&enabled==other.enabled&&overlapRule==other.overlapRule;}
    public Placement placed(PlacementTransform next) {
        if (locked) throw new IllegalStateException("Placement is locked; unlock it before moving or rotating");
        if((lockedAxes&1)!=0&&next.origin().x()!=transform.origin().x()||(lockedAxes&2)!=0&&next.origin().y()!=transform.origin().y()||(lockedAxes&4)!=0&&next.origin().z()!=transform.origin().z())throw new IllegalStateException("坐标轴已锁定");
        return new Placement(id, name, source, next, enabled, locked, opacity,regions,renderBlocks,lockedAxes,overlapRule);
    }
    public Placement named(String next) { return new Placement(id, next, source, transform, enabled, locked, opacity,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement source(String next) { return new Placement(id,name,next,transform,enabled,locked,opacity,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement enabled(boolean next) { return new Placement(id, name, source, transform, next, locked, opacity,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement locked(boolean next) { return new Placement(id, name, source, transform, enabled, next, opacity,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement opacity(float next) { return new Placement(id,name,source,transform,enabled,locked,next,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement renderBlocks(boolean next){return new Placement(id,name,source,transform,enabled,locked,opacity,regions,next,lockedAxes,overlapRule);}
    public Placement axes(int next){return new Placement(id,name,source,transform,enabled,locked,opacity,regions,renderBlocks,next,overlapRule);}
    public Placement overlap(ReplaceRule next){return new Placement(id,name,source,transform,enabled,locked,opacity,regions,renderBlocks,lockedAxes,next);}
    public Placement duplicate() { return new Placement(UUID.randomUUID(), name, source, transform, enabled, false, opacity,regions,renderBlocks,lockedAxes,overlapRule); }
    public Placement identity(UUID next){return new Placement(next,name,source,transform,enabled,locked,opacity,regions,renderBlocks,lockedAxes,overlapRule);}
    public RegionPlacement region(Region region){return regions.getOrDefault(region.name(),RegionPlacement.original(region));}
    public Placement region(Region region,RegionPlacement value){var old=region(region);if((locked||old.locked())&&(!old.position().equals(value.position())||old.quarterTurns()!=value.quarterTurns()||old.mirrorX()!=value.mirrorX()||old.mirrorZ()!=value.mirrorZ()))throw new IllegalStateException("子区域变换已锁定");var next=new LinkedHashMap<>(regions);if(value.equals(RegionPlacement.original(region)))next.remove(region.name());else next.put(region.name(),value);return new Placement(id,name,source,transform,enabled,locked,opacity,next,renderBlocks,lockedAxes,overlapRule);}
    public PlacementTransform transformFor(Region region){
        var sub=region(region);var mainLinear=new PlacementTransform(Vec3i.ZERO,transform.quarterTurns(),transform.mirrorX(),transform.mirrorZ());var subLinear=new PlacementTransform(Vec3i.ZERO,sub.quarterTurns(),sub.mirrorX(),sub.mirrorZ());
        var linear=subLinear.compose(mainLinear);var origin=transform.apply(sub.position()).subtract(linear.apply(region.anchor()));return new PlacementTransform(origin,linear.quarterTurns(),linear.mirrorX(),linear.mirrorZ());
    }
}
