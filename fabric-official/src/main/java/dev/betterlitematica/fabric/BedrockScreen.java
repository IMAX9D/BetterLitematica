package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.UiGlyphArt.Kind;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** One compact settings surface for direct targets, printer requests and dimension-scoped regions. */
final class BedrockScreen extends MenuScreen {
    private final long epoch;
    private BedrockSettings draft;
    private int tab,ticks;
    private String error="";
    private final List<Runnable> readers=new ArrayList<>();
    private final Map<String,String> rawInputs=new HashMap<>();
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private Button work,clear,aim;
    private OverlayList regionList;
    private UUID selectedRegion,removing;
    private long removingSince;
    private List<BedrockSettings.Region> shownRegions=List.of();
    BedrockScreen(Screen parent,ProjectionController controller){super("破基岩","",parent,controller,true);epoch=controller.sessionEpoch();draft=controller.bedrock().settings().copy();}
    @Override protected int preferredHeight(){return 344;}
    @Override protected void buildMenu(){
        readers.clear();fields.clear();aim=null;
        String[] names={"常规","规则","方向","区域"};
        for(int i=0;i<names.length;i++){int next=i;tabAt(names[i],cellX(i,names.length),0,cellWidth(names.length),()->{read();tab=next;removing=null;refresh();},tab==i);}
        switch(tab){case 0->general();case 1->rules();case 2->directions();default->regions();}
        work=fixed("启用",0,72,()->{var engine=controller.bedrock();if(!engine.enabled()){read();engine.check();save();}engine.toggle();ClientUi.setScreen(minecraft,null);});
        clear=fixed("清空队列",80,88,()->{controller.bedrock().clear();updateMenu();});
        fixed("保存",176,64,this::save);fixed("放弃更改",248,80,this::discard);updateWork();
    }
    private void general(){
        toggle("空手右键切换",0,38,()->draft.emptyHandToggle,value->draft.emptyHandToggle=value);
        toggle("瞬挖短等待",1,38,()->draft.shortWait,value->draft.shortWait=value);
        number("任务超时 / tick",draft.timeoutTicks,0,78,value->draft.timeoutTicks=value);
        number("重试次数",draft.retries,1,78,value->draft.retries=value);
        hint(fields.get("任务超时 / tick"),"20–1200 tick");hint(fields.get("重试次数"),"0–20");
        toggle("调试日志",0,134,()->draft.debug,value->draft.debug=value);
        aim=buttonAt("加入准星目标",cellX(1,2),134,cellWidth(2),()->{
            read();if(!(minecraft.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)throw new IllegalStateException("没有方块目标");
            var pos=hit.getBlockPos();
            if(!controller.bedrock().accepts(pos,draft))throw new IllegalStateException("目标未加载或不符合破基岩规则");
            save();controller.bedrock().add(pos);updateWork();
        },hasTarget(),false);
    }
    private void rules(){
        input("允许方块",String.join(", ",draft.whitelist),left,38,innerWidth,8192,value->draft.whitelist=blocks(value));
        hint(fields.get("允许方块"),"minecraft:bedrock, minecraft:end_portal_frame");
        input("排除 Y 层",draft.excludedY.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", ")),left,96,innerWidth,4096,value->draft.excludedY=floors(value));
        hint(fields.get("排除 Y 层"),"-64, 0, 120–127");
    }
    private void directions(){
        caption("破坏方向",left,38,innerWidth);
        var directions=BedrockSettings.Face.values();
        for(int i=0;i<directions.length;i++)direction(directions[i],draft.breakDirections,cellX(i,directions.length),60,cellWidth(directions.length));
        caption("初始活塞朝向",left,102,innerWidth);
        direction(BedrockSettings.Face.UP,draft.initialFacings,cellX(0,2),124,cellWidth(2));
        direction(BedrockSettings.Face.DOWN,draft.initialFacings,cellX(1,2),124,cellWidth(2));
    }
    private void direction(BedrockSettings.Face direction,EnumSet<BedrockSettings.Face> choices,int x,int y,int width){
        boolean selected=choices.contains(direction);
        buttonAt(direction.label(),x,y,width,()->{read();if(!choices.remove(direction))choices.add(direction);refresh();},!selected||choices.size()>1,selected);
    }
    private void regions(){
        boolean selected=!controller.selection().boxes().isEmpty();
        buttonAt("加入临时区域",cellX(0,2),36,cellWidth(2),()->addSelection(false),selected,false);
        buttonAt("加入持久区域",cellX(1,2),36,cellWidth(2),()->addSelection(true),selected,false);
        shownRegions=List.copyOf(controller.bedrock().regions());
        if(selectedRegion==null||shownRegions.stream().noneMatch(r->r.id().equals(selectedRegion)))selectedRegion=shownRegions.isEmpty()?null:shownRegions.get(0).id();
        int listWidth=(innerWidth-18)*3/5,right=left+listWidth+18,rightWidth=innerWidth-listWidth-18;
        double previousScroll=regionList==null?0:regionList.scrollOffset();
        regionList=new OverlayList(left,listWidth,Math.max(60,bodyBottom-bodyTop-72),id->{selectedRegion=UUID.fromString(id);removing=null;refresh();});
        String dimension=minecraft.level==null?"":minecraft.level.dimension().identifier().toString();
        regionList.rows(shownRegions.stream().map(r->new OverlayList.Row(r.id().toString(),r.name()+" · "+dimension(r.dimension()),r.dimension().equals(dimension))).toList(),selectedRegion==null?"":selectedRegion.toString());
        regionList.scrollOffset(previousScroll);addBody(regionList,72);
        var current=shownRegions.stream().filter(r->r.id().equals(selectedRegion)).findFirst().orElse(null);
        if(current==null)caption("暂无区域",left+18,86,listWidth-30);
        else{
            addBody(new OverlayLabel(right,rightWidth,(current.persistent()?"持久":"临时")+" · "+dimension(current.dimension())),72);
            var min=current.min();var max=current.max();
            caption("X  "+min.x()+" ～ "+max.x(),right,108,rightWidth);
            caption("Y  "+min.y()+" ～ "+max.y(),right,132,rightWidth);
            caption("Z  "+min.z()+" ～ "+max.z(),right,156,rightWidth);
            boolean confirming=current.id().equals(removing);
            iconAt(Kind.TRASH,confirming?"再次点击移除":"移除区域",right,184,24,()->{
                if(confirming){
                    if(controller.bedrock().regions().stream().noneMatch(r->r.id().equals(current.id()))){removing=null;refresh();return;}
                    save();controller.bedrock().removeRegion(current.id());draft=controller.bedrock().settings().copy();removing=null;
                }
                else{removing=current.id();removingSince=System.nanoTime();}refresh();
            },true,confirming?Look.STANDARD:Look.GHOST,confirming?UiTheme.ERROR:0);
        }
        buttonAt("选区工具",right+32,184,rightWidth-32,()->{read();ClientUi.setScreen(minecraft,new SelectionScreen(this,controller));},true,false);
    }
    private void addSelection(boolean persistent){
        read();preflightSelection(persistent);save();var before=new HashSet<UUID>();for(var region:controller.bedrock().regions())before.add(region.id());
        controller.bedrock().addSelection(persistent);draft=controller.bedrock().settings().copy();
        selectedRegion=controller.bedrock().regions().stream().filter(region->!before.contains(region.id())).map(BedrockSettings.Region::id).findFirst().orElse(selectedRegion);refresh();
    }
    private void preflightSelection(boolean persistent){
        if(minecraft.level==null)throw new IllegalStateException("请先进入世界");
        var boxes=controller.selection().boxes();if(boxes.isEmpty())throw new IllegalStateException("请先创建选区");
        long count=persistent?controller.bedrock().settings().regions.size():controller.bedrock().regions().stream().filter(region->!region.persistent()).count();
        if(count+boxes.size()>BedrockSettings.MAX_REGIONS)throw new IllegalStateException("区域数量已达上限");
        int index=0;String world=controller.worldKey(),dimension=minecraft.level.dimension().identifier().toString();
        // Validate every box before configure can cancel work; this loop has no controller side effects.
        for(var box:boxes)new BedrockSettings.Region(new UUID(0,++index),"选区 "+index,world,dimension,box.first(),box.second(),persistent);
    }
    private static String dimension(String value){return switch(value){case "minecraft:overworld"->"主世界";case "minecraft:the_nether"->"下界";case "minecraft:the_end"->"末地";default->value;};}
    private boolean hasTarget(){return minecraft!=null&&minecraft.hitResult instanceof BlockHitResult hit&&hit.getType()==HitResult.Type.BLOCK;}
    private void toggle(String label,int column,int y,BooleanSupplier get,Consumer<Boolean> set){buttonAt(label+"："+(get.getAsBoolean()?"开":"关"),cellX(column,2),y,cellWidth(2),()->{read();set.accept(!get.getAsBoolean());refresh();},true,get.getAsBoolean());}
    private void number(String label,int value,int column,int y,IntConsumer set){input(label,Integer.toString(value),cellX(column,2),y,cellWidth(2),5,raw->{try{set.accept(Integer.parseInt(raw));}catch(NumberFormatException e){throw new IllegalArgumentException(label+"：请输入整数");}});}
    private void input(String label,String value,int x,int y,int width,int limit,Consumer<String> reader){
        var field=fieldAt(label,rawInputs.getOrDefault(label,value),x,y,width,limit);fields.put(label,field);
        field.setResponder(raw->{rawInputs.put(label,raw);error="";});
        readers.add(()->{try{reader.accept(field.getValue().trim());}catch(RuntimeException failure){setFocused(field);throw failure;}});
    }
    private static List<String> blocks(String value){
        var result=new LinkedHashSet<String>();
        for(String part:value.split("[,，\\s]+"))if(!part.isBlank())result.add(part.contains(":")?part:"minecraft:"+part);
        return new ArrayList<>(result);
    }
    private static final Pattern FLOOR=Pattern.compile("(-?\\d+)(?:[-–~～](-?\\d+))?");
    private static List<Integer> floors(String value){
        var result=new TreeSet<Integer>();
        for(String part:value.split("[,，\\s]+")){
            if(part.isBlank())continue;var match=FLOOR.matcher(part);if(!match.matches())throw new IllegalArgumentException("排除 Y 层：请输入层数或范围");
            int first,last;try{first=Integer.parseInt(match.group(1));last=match.group(2)==null?first:Integer.parseInt(match.group(2));}catch(NumberFormatException e){throw new IllegalArgumentException("排除 Y 层：数值超出范围");}
            int min=Math.min(first,last),max=Math.max(first,last);
            if(min<-2048||max>2047)throw new IllegalArgumentException("排除 Y 层：-2048–2047");
            if((long)max-min+1>BedrockSettings.MAX_EXCLUDED_Y)throw new IllegalArgumentException("排除 Y 层：最多 "+BedrockSettings.MAX_EXCLUDED_Y+" 层");
            for(int y=min;y<=max;y++)result.add(y);
            if(result.size()>BedrockSettings.MAX_EXCLUDED_Y)throw new IllegalArgumentException("排除 Y 层：最多 "+BedrockSettings.MAX_EXCLUDED_Y+" 层");
        }
        return new ArrayList<>(result);
    }
    private void read(){
        try{for(var reader:readers)reader.run();draft.validate();error="";}
        catch(RuntimeException failure){error=Objects.toString(failure.getMessage(),"设置无效");focusInvalid();throw new IllegalArgumentException(error);}
    }
    private void focusInvalid(){for(var entry:fields.entrySet())if(error.startsWith(entry.getKey().split(" /",2)[0])){setFocused(entry.getValue());break;}}
    private void save(){
        if(epoch!=controller.sessionEpoch())throw new IllegalStateException("世界已切换");
        var current=controller.bedrock().settings();draft.regions=new ArrayList<>(current.regions);read();var next=draft.copy();
        if(!next.snapshot().equals(current.snapshot()))controller.bedrock().configure(next);
    }
    private void discard(){if(epoch!=controller.sessionEpoch()){ClientUi.setScreen(minecraft,null);return;}super.onClose();}
    @Override public void onClose(){if(epoch!=controller.sessionEpoch()){ClientUi.setScreen(minecraft,null);return;}runAction(()->{save();super.onClose();});}
    @Override protected void runAction(Runnable action){
        if(epoch!=controller.sessionEpoch()){ClientUi.setScreen(minecraft,null);return;}
        try{error="";action.run();}catch(RuntimeException failure){error=Objects.toString(failure.getMessage(),"操作失败");focusInvalid();}
    }
    @Override protected String displayedStatus(){return statusLine();}
    @Override protected int statusColor(){return error.isEmpty()?UiTheme.MUTED:UiTheme.ERROR;}
    @Override protected String statusLine(){var engine=controller.bedrock();return !error.isEmpty()?error:(engine.queued()>0?"待处理 "+engine.queued()+" · ":"")+Objects.toString(engine.status(),"");}
    private void updateWork(){if(work!=null)work.setMessage(Component.literal(controller.bedrock().enabled()?"停用":"启用"));if(clear!=null)clear.active=controller.bedrock().queued()>0;if(aim!=null)aim.active=hasTarget();}
    @Override protected void updateMenu(){
        updateWork();
        if(removing!=null&&System.nanoTime()-removingSince>3_000_000_000L){removing=null;if(tab==3)refresh();return;}
        if(tab==3&&++ticks%10==0&&!shownRegions.equals(controller.bedrock().regions()))refresh();
    }
}
