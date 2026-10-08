package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Read-only tool context. No world sampling, file reads or selection changes. */
record ToolHudData(ToolMode mode, boolean area, String badge, List<Row> rows) {
    enum Kind { TEXT, NAME, ORIGIN, FIRST, SECOND, SIZE, BLOCK }
    record Row(Kind kind, String label, String text, String state, boolean selected) {}
    record Input(ToolMode mode, AreaSelection selection, SelectionTarget target,
                 Placement placement, List<Region> regions, String regionName,
                 boolean expand, boolean deletePlacement, ReplaceRule pasteRule,
                 boolean pasteNbt, boolean pasteEntities, String primary, String secondary,
                 boolean showPrimary, boolean grabbing, String status) {
        // Model objects are immutable. Identity checks keep an idle HUD independent of source size.
        boolean same(Input other) {
            return other!=null && mode==other.mode && selection==other.selection && target==other.target
                && placement==other.placement && regions==other.regions && regionName.equals(other.regionName)
                && expand==other.expand && deletePlacement==other.deletePlacement && pasteRule==other.pasteRule
                && pasteNbt==other.pasteNbt && pasteEntities==other.pasteEntities
                && primary.equals(other.primary) && secondary.equals(other.secondary)
                && showPrimary==other.showPrimary && grabbing==other.grabbing && status.equals(other.status);
        }
    }

    static boolean visible(boolean player, boolean menu, boolean hidden, boolean rendering, boolean held, ToolMode mode) {
        return player && !menu && !hidden && rendering && (held || mode==ToolMode.REBUILD);
    }

    static ToolHudData capture(Input input) {
        var mode=input.mode();
        boolean area=mode.selection() && !(mode==ToolMode.DELETE && input.deletePlacement());
        var rows=new ArrayList<Row>();
        if (area) {
            var selection=input.selection();
            add(rows,Kind.NAME,selection.simple()?"简单":"普通",selection.selected().isEmpty()?"未选择区域":selection.selected(),false);
            add(rows,Kind.TEXT,"区域",selection.boxes().size()+"  ·  "+(input.expand()?"扩展":"角点"),false);
            if (!selection.boxes().isEmpty()) {
                add(rows,Kind.ORIGIN,"原点",coordinates(selection.origin()),selected(input.target(),SelectionTarget.Part.ORIGIN));
                if (!selection.selected().isEmpty()) {
                    var box=selection.current();var size=box.region().size();
                    add(rows,Kind.SIZE,"尺寸",size.x()+" × "+size.y()+" × "+size.z(),false);
                    add(rows,Kind.FIRST,"A",coordinates(box.first()),selected(input.target(),SelectionTarget.Part.FIRST));
                    add(rows,Kind.SECOND,"B",coordinates(box.second()),selected(input.target(),SelectionTarget.Part.SECOND));
                }
            }
        } else {
            var placement=input.placement();
            add(rows,Kind.NAME,"投影",placement==null?"未选择投影":placement.name(),false);
            if (placement!=null) {
                add(rows,Kind.ORIGIN,"原点",coordinates(placement.transform().origin()),false);
                int changed=0;Region selected=null;
                for (var region:input.regions()) {
                    if (modified(region,placement.region(region))) changed++;
                    if (region.name().equals(input.regionName())) selected=region;
                }
                add(rows,Kind.TEXT,"区域",input.regions().size()+"  ·  已修改 "+changed,false);
                if (selected!=null) {
                    var sub=placement.region(selected);
                    add(rows,Kind.NAME,"子区",selected.name()+(modified(selected,sub)?"  ·  已修改":""),true);
                    add(rows,Kind.ORIGIN,"原点",coordinates(placement.transform().apply(sub.position())),true);
                }
                if (mode==ToolMode.PASTE) {
                    add(rows,Kind.TEXT,"覆盖",switch(input.pasteRule()){case NONE->"仅空气";case NON_AIR->"非空气投影";case ALL->"全部";},false);
                    add(rows,Kind.TEXT,"数据","NBT "+on(input.pasteNbt())+"  ·  实体 "+on(input.pasteEntities()),false);
                }
            }
        }
        if (mode.secondary()) rows.add(new Row(Kind.BLOCK,"来源","",input.secondary(),false));
        if (mode.primary() && input.showPrimary() && (mode!=ToolMode.REBUILD || input.placement()!=null)) rows.add(new Row(Kind.BLOCK,mode.secondary()?"目标":"方块","",input.primary(),false));
        if (!input.status().isBlank()) add(rows,Kind.TEXT,"状态",input.status(),false);
        else if (input.grabbing()) add(rows,Kind.TEXT,"状态","抓取中",true);
        return new ToolHudData(mode,area,mode==ToolMode.DELETE?(area?"选区":"投影"):"",List.copyOf(rows));
    }
    private static void add(List<Row> rows,Kind kind,String label,String text,boolean selected) {
        rows.add(new Row(kind,label,text,"",selected));
    }
    private static boolean selected(SelectionTarget target,SelectionTarget.Part part) {return target!=null && target.part()==part;}
    static String coordinates(Vec3i p) {return p.x()+"  "+p.y()+"  "+p.z();}
    static boolean modified(Region region,RegionPlacement sub) {
        return !sub.position().equals(region.anchor()) || sub.quarterTurns()!=0 || sub.mirrorX() || sub.mirrorZ() || !sub.enabled();
    }
    private static String on(boolean value) {return value?"开":"关";}
}
