package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Exercises the production read-only HUD model; no client/world is created. */
public final class ToolHudChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ToolHudData.Input input(ToolMode mode,AreaSelection selection,Placement placement,List<Region> regions){
        return new ToolHudData.Input(mode,selection,null,placement,regions,"",false,false,ReplaceRule.ALL,true,false,
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]","minecraft:stone",true,false,"");
    }
    private static ToolHudData.Row row(ToolHudData data,ToolHudData.Kind kind,String label){return data.rows().stream().filter(r->r.kind()==kind&&r.label().equals(label)).findFirst().orElseThrow();}
    private static ToolHudData.Input with(ToolHudData.Input source,String field,Object value){
        try{var fields=ToolHudData.Input.class.getRecordComponents();var types=new Class<?>[fields.length];var args=new Object[fields.length];boolean found=false;
            for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();args[i]=fields[i].getAccessor().invoke(source);if(fields[i].getName().equals(field)){args[i]=value;found=true;}}
            if(!found)throw new AssertionError("Unknown input component "+field);return ToolHudData.Input.class.getDeclaredConstructor(types).newInstance(args);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    public static int run(){checks=0;
        var box=new SelectionBox("反向区域",new Vec3i(-2,-35,4),new Vec3i(-7,-39,-3));
        var area=new AreaSelection(List.of(box),box.name(),new Vec3i(-12,-44,-8),false);
        var seed=input(ToolMode.SELECTION,area,null,List.of());var captured=ToolHudData.capture(seed);
        check(captured.area(),"Selection modes use selection data");
        check(row(captured,ToolHudData.Kind.SIZE,"尺寸").text().equals("6 × 5 × 8"),"Dimensions include both reversed negative-coordinate corners");
        check(row(captured,ToolHudData.Kind.FIRST,"A").text().equals("-2  -35  4")&&row(captured,ToolHudData.Kind.SECOND,"B").text().equals("-7  -39  -3"),"Displayed corners preserve original order rather than normalized min/max");
        check(row(captured,ToolHudData.Kind.ORIGIN,"原点").text().equals("-12  -44  -8"),"Explicit selection origin is not replaced by the box minimum");
        var selected=ToolHudData.capture(with(seed,"target",new SelectionTarget(SelectionTarget.Part.SECOND,box.name(),2)));
        check(row(selected,ToolHudData.Kind.SECOND,"B").selected()&&!row(selected,ToolHudData.Kind.FIRST,"A").selected(),"Only the selected corner is emphasized");
        selected=ToolHudData.capture(with(seed,"target",new SelectionTarget(SelectionTarget.Part.ORIGIN,"",1)));
        check(row(selected,ToolHudData.Kind.ORIGIN,"原点").selected(),"Origin selection is distinct from a corner selection");
        var simple=ToolHudData.capture(with(with(seed,"selection",area.toggleMode()),"expand",true));
        check(row(simple,ToolHudData.Kind.NAME,"简单").text().equals(box.name())&&row(simple,ToolHudData.Kind.TEXT,"区域").text().contains("扩展"),"Simple selection and corner expansion are independent displayed settings");
        var noSelection=ToolHudData.capture(with(seed,"selection",area.select("")));
        check(noSelection.rows().stream().noneMatch(r->r.kind()==ToolHudData.Kind.SIZE)&&row(noSelection,ToolHudData.Kind.NAME,"普通").text().contains("未选择"),"Unselected nonempty area never calls current() or invents a selected box");
        var empty=ToolHudData.capture(with(seed,"selection",AreaSelection.EMPTY));
        check(empty.rows().stream().noneMatch(r->r.kind()==ToolHudData.Kind.ORIGIN||r.kind()==ToolHudData.Kind.FIRST),"Empty area has no invented origin/corner rows and does not throw");
        boolean immutable=false;try{captured.rows().clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"HUD capture publishes immutable rows");
        check(area.current()==box&&area.origin().equals(new Vec3i(-12,-44,-8)),"Capture does not change the original area model");

        var region=new Region("signed",new Vec3i(-4,-3,-6),new Vec3i(5,4,7),Vec3i.ZERO);
        var main=new PlacementTransform(new Vec3i(100,-39,-200),1,true,false);
        var placement=new Placement(UUID.randomUUID(),"测试投影","fixture.litematic",main,true,false);
        var locked=RegionPlacement.original(region);locked=new RegionPlacement(locked.position(),0,false,false,true,true);
        check(!ToolHudData.modified(region,locked),"Locking alone is not a source-region modification");
        check(ToolHudData.modified(region,locked.enabled(false)),"Disabling a region is a source-region modification");
        check(!ToolHudData.modified(region,RegionPlacement.original(region)),"Signed-size source anchor, not normalized minimum, defines the unchanged origin");
        var sub=new RegionPlacement(new Vec3i(3,2,-5),2,false,true,true,false);placement=placement.region(region,sub);
        var pInput=with(input(ToolMode.PLACEMENT,area,placement,List.of(region)),"regionName",region.name());
        var pData=ToolHudData.capture(pInput);
        var origins=pData.rows().stream().filter(r->r.kind()==ToolHudData.Kind.ORIGIN).toList();
        check(origins.size()==2&&origins.get(0).text().equals("100  -39  -200")&&origins.get(1).text().equals("105  -37  -203"),"Subregion origin applies main mirror then rotation once, independently of subregion rotation and signed anchor");
        check(row(pData,ToolHudData.Kind.TEXT,"区域").text().equals("1  ·  已修改 1"),"Modified-region count uses actual source regions");
        check(row(pData,ToolHudData.Kind.NAME,"子区").selected(),"Selected source subregion is identified separately from the whole placement");
        var missing=ToolHudData.capture(with(pInput,"placement",null));check(row(missing,ToolHudData.Kind.NAME,"投影").text().contains("未选择"),"No selected placement is shown explicitly without stale placement origin");
        var deleted=ToolHudData.capture(with(with(pInput,"mode",ToolMode.DELETE),"deletePlacement",true));
        check(!deleted.area()&&deleted.badge().equals("投影")&&deleted.rows().stream().noneMatch(r->r.kind()==ToolHudData.Kind.FIRST),"DELETE placement target must not fall through to selection fields");
        deleted=ToolHudData.capture(with(pInput,"mode",ToolMode.DELETE));check(deleted.area()&&deleted.badge().equals("选区"),"DELETE area target remains a separate branch");
        for(var rule:ReplaceRule.values()){
            var paste=ToolHudData.capture(with(with(with(with(pInput,"mode",ToolMode.PASTE),"pasteRule",rule),"pasteNbt",false),"pasteEntities",true));
            String expected=switch(rule){case NONE->"仅空气";case NON_AIR->"非空气投影";case ALL->"全部";};
            check(row(paste,ToolHudData.Kind.TEXT,"覆盖").text().equals(expected),"Paste rule is the actual configured policy: "+rule);
            check(row(paste,ToolHudData.Kind.TEXT,"数据").text().equals("NBT 关  ·  实体 开"),"NBT and entities are independent truthful values");
        }
        var replace=ToolHudData.capture(with(seed,"mode",ToolMode.REPLACE));
        check(row(replace,ToolHudData.Kind.BLOCK,"来源").state().equals(seed.secondary()),"Replacement source is the sampled secondary state");
        check(row(replace,ToolHudData.Kind.BLOCK,"目标").state().equals(seed.primary()),"Replacement target preserves the exact primary state and orientation properties");
        var rebuild=ToolHudData.capture(with(with(pInput,"mode",ToolMode.REBUILD),"showPrimary",false));
        check(rebuild.rows().stream().noneMatch(r->r.kind()==ToolHudData.Kind.BLOCK),"Held-building-material rebuild does not claim the stored primary will be used");
        rebuild=ToolHudData.capture(with(pInput,"mode",ToolMode.REBUILD));check(row(rebuild,ToolHudData.Kind.BLOCK,"方块").state().equals(pInput.primary()),"Tool/empty-hand rebuild displays the stored exact primary");
        rebuild=ToolHudData.capture(with(with(pInput,"mode",ToolMode.REBUILD),"placement",null));check(rebuild.rows().stream().noneMatch(r->r.kind()==ToolHudData.Kind.BLOCK),"Rebuild without a selected placement cannot display a misleading editable primary");
        var grabbing=ToolHudData.capture(with(seed,"grabbing",true));check(row(grabbing,ToolHudData.Kind.TEXT,"状态").text().equals("抓取中"),"Grab state is retained as a necessary operation state");
        var failure=ToolHudData.capture(with(with(seed,"grabbing",true),"status","目标区块未加载"));check(row(failure,ToolHudData.Kind.TEXT,"状态").text().equals("目标区块未加载"),"Task status takes priority over generic grabbing text");

        var identity=with(with(pInput,"target",new SelectionTarget(SelectionTarget.Part.BOX,box.name(),2)),"status","就绪");
        check(identity.same(identity)&&!identity.same(null),"Input cache accepts identical snapshots and rejects an absent prior snapshot");
        var changed=new LinkedHashMap<String,Object>();changed.put("mode",ToolMode.PASTE);changed.put("selection",new AreaSelection(area.boxes(),area.selected(),area.origin(),area.simple()));changed.put("target",new SelectionTarget(SelectionTarget.Part.BOX,box.name(),2));changed.put("placement",placement.named(placement.name()));changed.put("regions",new ArrayList<>(identity.regions()));changed.put("regionName","other");changed.put("expand",true);changed.put("deletePlacement",true);changed.put("pasteRule",ReplaceRule.NONE);changed.put("pasteNbt",false);changed.put("pasteEntities",true);changed.put("primary","minecraft:dirt");changed.put("secondary","minecraft:glass");changed.put("showPrimary",false);changed.put("grabbing",true);changed.put("status","失败");
        for(var entry:changed.entrySet())check(!identity.same(with(identity,entry.getKey(),entry.getValue())),"Changed HUD input invalidates cached state: "+entry.getKey());
        check(identity.same(with(identity,"status",new String(identity.status()))),"Equivalent text snapshots do not churn the cache");
        check(row(ToolHudData.capture(with(identity,"status","失败")),ToolHudData.Kind.TEXT,"状态").text().equals("失败"),"A changed status is visible immediately without a timed refresh gate");

        for(var mode:ToolMode.values()){
            check(ToolHudData.visible(true,false,false,true,true,mode),"Held enabled tool exposes mode "+mode);
            check(ToolHudData.visible(true,false,false,true,false,mode)==(mode==ToolMode.REBUILD),"Only rebuild retains original no-tool visibility: "+mode);
            check(!ToolHudData.visible(false,false,false,true,true,mode),"No player means no tool HUD");
            check(!ToolHudData.visible(true,true,false,true,true,mode),"Open menus hide the tool HUD");
            check(!ToolHudData.visible(true,false,true,true,true,mode),"F1 hides the tool HUD");
            check(!ToolHudData.visible(true,false,false,false,true,mode),"Global rendering off hides the tool HUD");
        }
        return checks;
    }
}
