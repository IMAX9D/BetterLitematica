package dev.betterlitematica.fabric;
import java.util.*;
import net.minecraft.util.registry.Registry;
/** Compact state and NBT text shared by the in-world overlay. */
final class ProjectionInfoPanel {
    private ProjectionInfoPanel(){}
    static List<String> lines(ProjectionInfoData.Side side,boolean states){var result=new ArrayList<String>();if(side==null)return result;if(side.state()!=null){result.add(Registry.BLOCK.getId(side.state().getBlock()).toString());if(states)side.state().getEntries().entrySet().stream().sorted(Comparator.comparing(e->e.getKey().getName())).forEach(e->result.add(property(e.getKey().getName())+" · "+value(e.getKey(),e.getValue())));}if(!side.availability().isBlank())result.add(side.availability());for(String line:side.nbt())if(!line.matches("(id|x|y|z|Items) = .*"))result.add(line);return result;}
    private static String property(String key){return switch(key){case "facing"->"朝向";case "axis"->"轴向";case "half"->"半部";case "waterlogged"->"含水";case "powered"->"充能";case "lit"->"点亮";case "open"->"开启";case "level"->"等级";default->key;};}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String value(net.minecraft.state.property.Property key,Comparable value){String raw=key.name(value);return switch(raw){case "north"->"北";case "south"->"南";case "east"->"东";case "west"->"西";case "up"->"上";case "down"->"下";case "true"->"是";case "false"->"否";default->raw;};}
}
