package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import com.google.gson.*;
import java.util.*;

record PlacementClipboard(PlacementTransform transform,boolean enabled,float opacity,Map<String,RegionPlacement> regions,boolean renderBlocks,int lockedAxes,ReplaceRule overlapRule,List<String> regionNames) {
    PlacementClipboard {var validated=new Placement(new UUID(0,0),"clipboard","clipboard.litematic",transform,enabled,false,opacity,regions,renderBlocks,lockedAxes,overlapRule);regions=validated.regions();regionNames=regionNames==null?List.of():List.copyOf(regionNames);if(regionNames.size()>1024||new HashSet<>(regionNames).size()!=regionNames.size())throw new IllegalArgumentException();for(String name:regionNames)if(name.isBlank()||name.length()>256)throw new IllegalArgumentException();}
    static String encode(Placement p){return encode(p,List.of());}
    static String encode(Placement p,List<Region> regions){return "BetterLitematica:placement:2;"+new Gson().toJson(new PlacementClipboard(p.transform(),p.enabled(),p.opacity(),p.regions(),p.renderBlocks(),p.lockedAxes(),p.overlapRule(),regions.stream().map(Region::name).toList()));}
    static PlacementClipboard decode(String text){
        try{
            if(text==null||text.length()>4*1024*1024)throw new IllegalArgumentException();text=text.strip();
            String prefix="BetterLitematica:placement:2;";
            if(text.startsWith(prefix)){var root=JsonParser.parseString(text.substring(prefix.length())).getAsJsonObject();validate(root);return new Gson().fromJson(root,PlacementClipboard.class);}
            String[] v=text.split(";",-1);if(v.length!=9||!v[0].equals("BetterLitematica:placement:1"))throw new IllegalArgumentException();
            int rotation=Integer.parseInt(v[4]);if(rotation<0||rotation>3)throw new IllegalArgumentException();
            return new PlacementClipboard(new PlacementTransform(new Vec3i(Integer.parseInt(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3])),rotation,bool(v[5]),bool(v[6])),bool(v[7]),Float.parseFloat(v[8]),Map.of(),true,0,ReplaceRule.ALL,List.of());
        }catch(RuntimeException e){throw new IllegalArgumentException("剪贴板中没有有效的投影状态");}
    }
    private static void validate(JsonObject root){
        transform(root.getAsJsonObject("transform"),"origin");bool(root,"enabled");bool(root,"renderBlocks");integer(root,"lockedAxes",0,7);
        if(!root.get("opacity").isJsonPrimitive()||!root.getAsJsonPrimitive("opacity").isNumber())throw new IllegalArgumentException();
        var rule=root.getAsJsonPrimitive("overlapRule");if(!rule.isString())throw new IllegalArgumentException();ReplaceRule.valueOf(rule.getAsString());
        var regions=root.getAsJsonObject("regions");if(regions.size()>1024)throw new IllegalArgumentException();
        for(var e:regions.entrySet()){var region=e.getValue().getAsJsonObject();transform(region,"position");bool(region,"enabled");bool(region,"locked");}
    }
    private static void transform(JsonObject json,String position){
        integer(json,"quarterTurns",0,3);bool(json,"mirrorX");bool(json,"mirrorZ");var p=json.getAsJsonObject(position);for(String axis:List.of("x","y","z"))integer(p,axis,Integer.MIN_VALUE,Integer.MAX_VALUE);
    }
    private static void bool(JsonObject object,String name){if(!object.has(name)||!object.get(name).isJsonPrimitive()||!object.getAsJsonPrimitive(name).isBoolean())throw new IllegalArgumentException();}
    private static void integer(JsonObject object,String name,int min,int max){var v=object.getAsJsonPrimitive(name);if(!v.isNumber())throw new IllegalArgumentException();long n=new java.math.BigDecimal(v.getAsString()).longValueExact();if(n<min||n>max)throw new IllegalArgumentException();}
    private static boolean bool(String value){if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException();return Boolean.parseBoolean(value);}
    Placement apply(Placement p){
        var merged=new LinkedHashMap<>(p.regions());for(String name:regionNames)merged.remove(name);merged.putAll(regions);
        var moved=p.placed(transform).enabled(enabled).opacity(opacity);if(p.locked()&&!p.regions().equals(merged))throw new IllegalStateException("投影已锁定");for(var e:p.regions().entrySet())if(e.getValue().locked()&&!Objects.equals(merged.get(e.getKey()),e.getValue()))throw new IllegalStateException("子区域已锁定");
        return new Placement(moved.id(),moved.name(),moved.source(),moved.transform(),moved.enabled(),moved.locked(),moved.opacity(),merged,renderBlocks,lockedAxes,overlapRule,moved.displayFilter());
    }
    PlacementClipboard matching(List<Region> available){var names=new HashSet<String>();for(var r:available)names.add(r.name());var filtered=new LinkedHashMap<String,RegionPlacement>();for(var e:regions.entrySet())if(names.contains(e.getKey()))filtered.put(e.getKey(),e.getValue());return new PlacementClipboard(transform,enabled,opacity,filtered,renderBlocks,lockedAxes,overlapRule,regionNames.stream().filter(names::contains).toList());}
}
