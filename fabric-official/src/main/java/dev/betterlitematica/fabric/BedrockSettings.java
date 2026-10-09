package dev.betterlitematica.fabric;
import com.google.gson.*;
import dev.betterlitematica.core.SelectionBox;
import dev.betterlitematica.core.Vec3i;
import java.util.*;
/** Preferences and explicit persistent regions only; live enablement and temporary work are separate. */
final class BedrockSettings {
    static final int MAX_REGIONS=32,MAX_WHITELIST=64,MAX_EXCLUDED_Y=256;
    enum Face {
        DOWN("下"),UP("上"),NORTH("北"),SOUTH("南"),WEST("西"),EAST("东");
        private final String label;Face(String label){this.label=label;}String label(){return label;}
    }
    record Region(UUID id,String name,String world,String dimension,Vec3i first,Vec3i second,boolean persistent) {
        Region {
            Objects.requireNonNull(id,"区域编号");Objects.requireNonNull(first,"区域起点");Objects.requireNonNull(second,"区域终点");
            if(name==null||name.isBlank()||name.length()>120||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("区域名称无效");
            if(world==null||world.isBlank()||world.length()>256||world.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("区域所属世界无效");
            if(!identifier(dimension))throw new IllegalArgumentException("区域维度无效");
            coordinate(first);coordinate(second);new SelectionBox(name,first,second).region();
        }
        Vec3i min(){return new Vec3i(Math.min(first.x(),second.x()),Math.min(first.y(),second.y()),Math.min(first.z(),second.z()));}
        Vec3i max(){return new Vec3i(Math.max(first.x(),second.x()),Math.max(first.y(),second.y()),Math.max(first.z(),second.z()));}
        boolean contains(Vec3i p){return p.x()>=Math.min(first.x(),second.x())&&p.x()<=Math.max(first.x(),second.x())&&p.y()>=Math.min(first.y(),second.y())&&p.y()<=Math.max(first.y(),second.y())&&p.z()>=Math.min(first.z(),second.z())&&p.z()<=Math.max(first.z(),second.z());}
        private static void coordinate(Vec3i value){if(Math.abs((long)value.x())>30_000_000||Math.abs((long)value.z())>30_000_000||value.y()<-2048||value.y()>2047)throw new IllegalArgumentException("区域坐标超出范围");}
    }
    boolean emptyHandToggle=true,shortWait=true,debug,heldTool;
    int timeoutTicks=120,retries=1;
    List<String> whitelist=new ArrayList<>(List.of("minecraft:bedrock"));
    List<Integer> excludedY=new ArrayList<>();
    EnumSet<Face> breakDirections=EnumSet.allOf(Face.class),initialFacings=EnumSet.of(Face.UP,Face.DOWN);
    List<Region> regions=new ArrayList<>();
    private transient JsonObject preserved=new JsonObject();
    void validate(){
        bounded("任务超时",timeoutTicks,20,1200);bounded("重试次数",retries,0,20);
        if(whitelist==null||whitelist.isEmpty()||whitelist.size()>MAX_WHITELIST)throw new IllegalArgumentException("允许方块：1–"+MAX_WHITELIST+" 项");
        for(String id:whitelist)if(!identifier(id))throw new IllegalArgumentException("允许方块：无效方块 ID");
        if(new HashSet<>(whitelist).size()!=whitelist.size())throw new IllegalArgumentException("允许方块：有重复项");
        if(excludedY==null||excludedY.size()>MAX_EXCLUDED_Y)throw new IllegalArgumentException("排除 Y 层：最多 "+MAX_EXCLUDED_Y+" 层");
        for(Integer y:excludedY)if(y==null||y<-2048||y>2047)throw new IllegalArgumentException("排除 Y 层：-2048–2047");
        if(new HashSet<>(excludedY).size()!=excludedY.size())throw new IllegalArgumentException("排除 Y 层：有重复项");
        if(breakDirections==null||breakDirections.isEmpty())throw new IllegalArgumentException("破坏方向：至少选择一个方向");
        if(initialFacings==null||initialFacings.isEmpty()||!EnumSet.of(Face.UP,Face.DOWN).containsAll(initialFacings))throw new IllegalArgumentException("初始活塞朝向：选择上或下");
        if(regions==null||regions.size()>MAX_REGIONS)throw new IllegalArgumentException("持久区域最多 "+MAX_REGIONS+" 个");
        var ids=new HashSet<UUID>();
        for(var region:regions){if(region==null||!region.persistent())throw new IllegalArgumentException("持久区域无效");if(!ids.add(region.id()))throw new IllegalArgumentException("区域编号重复");}
    }
    static BedrockSettings read(JsonElement value){
        if(value==null)return new BedrockSettings();
        if(!value.isJsonObject())throw new IllegalArgumentException("破基岩设置无效");
        var root=value.getAsJsonObject();if(root.has("version")&&root.get("version").getAsInt()!=1)throw new IllegalArgumentException("破基岩设置版本不受支持");
        var settings=new Gson().fromJson(root,BedrockSettings.class);
        if(settings==null)throw new IllegalArgumentException("破基岩设置无效");
        settings.preserved=root.deepCopy();settings.validate();return settings;
    }
    BedrockSettings copy(){return read(snapshot());}
    JsonObject snapshot(){
        validate();var root=preserved.deepCopy();root.addProperty("version",1);
        new Gson().toJsonTree(this).getAsJsonObject().entrySet().forEach(entry->root.add(entry.getKey(),entry.getValue()));return root;
    }
    private static boolean identifier(String value){return value!=null&&value.length()<=128&&value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");}
    private static void bounded(String label,int value,int min,int max){if(value<min||value>max)throw new IllegalArgumentException(label+"："+min+"–"+max);}
}
