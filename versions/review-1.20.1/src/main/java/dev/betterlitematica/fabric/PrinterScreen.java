package dev.betterlitematica.fabric;

import dev.betterlitematica.core.PrinterRange;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.*;

/** One shared printer session covers every enabled placement. */
final class PrinterScreen extends MenuScreen {
    private final long sessionEpoch;
    private PrinterSettings draft;
    private boolean discardConfirmed;
    PrinterScreen page(int value){tab=value;return this;}
    private int tab;private String error="",notice="";
    private final List<Runnable> readers=new ArrayList<>();
    private final Map<String,TextFieldWidget> fields=new LinkedHashMap<>();
    private TextFieldWidget invalidField;
    private ButtonWidget work,stop,directionButton;private final Map<String,String> rawInputs=new HashMap<>();
    PrinterScreen(Screen parent,ProjectionController controller){
        super("打印机","",parent,controller,true);sessionEpoch=controller.sessionEpoch();
        draft=PrinterSettings.read(controller.options().printer.snapshot());
    }
    @Override protected boolean showBack(){return false;}
    @Override protected void buildMenu(){
        readers.clear();fields.clear();
        addBody(new OverlayLabel(left,innerWidth,"启用投影 · "+controller.printerPlacements().size()),0);
        ghostAt("搜索设置",left+innerWidth-240,0,82,()->{read();client.setScreen(new SettingsSearchScreen(this,controller));},true);
        String[] tabs={"施工","通用","策略","过滤","性能","高亮"};
        for(int i=0;i<tabs.length;i++){int next=i;tabAt(tabs[i],cellX(i,tabs.length),26,cellWidth(tabs.length),()->{read();tab=next;refresh();},tab==i);}
        if(tab==0)workPage();else if(tab==1)settingsPage();else if(tab==2)strategyPage();else if(tab==3)filtersPage();else if(tab==4)performancePage();else highlightPage();
        work=fixed("开始",0,72,()->{
            var engine=controller.printer();
            if(engine.running()){engine.pause("已暂停");return;}
            try{read();if(!draft.snapshot().equals(controller.options().printer.snapshot()))throw new IllegalArgumentException("请先保存设置，再开始施工");engine.start();client.setScreen(null);}catch(RuntimeException e){error=e.getMessage();throw e;}
        });
        stop=fixed("停止",80,64,()->{controller.printer().stop();notice="已停止";});
        fixedAction("保存并返回",152,104,this::commit);
        fixed(discardConfirmed?"确认放弃":"放弃更改",264,88,this::discard);dependencies();updateMenu();
    }
    private void workPage(){
        ghostAt("破基岩设置与独立队列",left+innerWidth-150,0,150,()->{read();client.setScreen(new BedrockScreen(this,controller));},true);
        var modes=new WheelModes[]{WheelModes.PRINT,WheelModes.MINE,WheelModes.FILL,WheelModes.DRAIN,WheelModes.BEDROCK};
        for(int i=0;i<modes.length;i++){
            var mode=modes[i];int x=cellX(i,modes.length),w=cellWidth(modes.length);boolean enabled=mode.enabled(draft);
            var control=buttonAt(mode.label()+"："+(mode.mixed(draft)?"部分":enabled?"开":"关"),x,56,w,()->{read();draft=WheelModes.toggled(draft,mode);refresh();},true,enabled);
            hint(control,"切换"+mode.label()+"模式；保存后按施工开关键执行。");
            if(mode.mixed(draft)){var active=new ArrayList<String>();if(draft.breakWrong)active.add("错误方块");if(draft.breakExtra)active.add("多余方块");if(draft.breakState)active.add("错误状态");hint(control,String.join(" · ",active));}

        }
        cycleScope("打印范围",0,3,88,()->draft.printScope,v->draft.printScope=v);
        cycleScope("填充范围",1,3,88,()->draft.fillScope,v->draft.fillScope=v);
        cycleScope("排流体范围",2,3,88,()->draft.fluidScope,v->draft.fluidScope=v);
        inputAt("填充方块",draft.fillState,cellX(0,2),122,cellWidth(2)-76,256,v->draft.fillState=v);
        directionButton=buttonAt("方向",cellX(0,2)+cellWidth(2)-68,136,68,()->{read();var state=PrinterRules.cycleDirection(PrinterEngine.checkedFill(draft));draft.fillState=stateName(state);rawInputs.put("填充方块",draft.fillState);refresh();},false,false);updateDirection();
        number("距离（0 自动）",Double.toString(draft.range),1,2,122,v->draft.range=Double.parseDouble(v));
        number("批次间隔 / tick",draft.interval,0,3,172,v->draft.interval=v);
        number("每 tick 上限",draft.perTick,1,3,172,v->draft.perTick=v);
        number("位置冷却 / tick",draft.cooldown,2,3,172,v->draft.cooldown=v);
        hint(fields.get("批次间隔 / tick"),"0–1：每 tick；2：每 2 tick");
        hint(fields.get("每 tick 上限"),"0：按时间预算执行");
        hint(fields.get("位置冷却 / tick"),"同一位置再次尝试前的等待时间");
        toggle("状态 HUD",0,222,()->draft.hud,v->draft.hud=v);
        toggle("周围待建 HUD",1,222,()->draft.missingHud,v->draft.missingHud=v);

    }
    private void settingsPage(){
        buttonAt("范围形状："+switch(draft.shape){case SPHERE->"球形";case OCTAHEDRON->"八面体";case CUBE->"立方体";},cellX(0,2),56,cellWidth(2),()->{read();draft.shape=PrinterRange.Shape.values()[(draft.shape.ordinal()+1)%3];refresh();},true,false);
        buttonAt("顺序："+draft.order,cellX(1,2),56,cellWidth(2),()->{read();var orders=List.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX");draft.order=orders.get((orders.indexOf(draft.order)+1)%6);refresh();},true,false);
        toggle("X 反向",0,82,()->draft.reverseX,v->draft.reverseX=v);
        toggle("Y 反向",1,82,()->draft.reverseY,v->draft.reverseY=v);
        toggle("Z 反向",0,108,()->draft.reverseZ,v->draft.reverseZ=v);
        toggle("悬空放置",1,108,()->draft.airPlace,v->draft.airPlace=v);
        toggle("重力方块支撑检查",0,134,()->draft.fallingCheck,v->draft.fallingCheck=v);
        toggle("替换可替换方块",1,134,()->draft.replace,v->draft.replace=v);
        toggle("原木去皮",0,160,()->draft.stripLogs,v->draft.stripLogs=v);
        toggle("音符盒调音",1,160,()->draft.noteTuning,v->draft.noteTuning=v);
        toggle("作物催熟",0,186,()->draft.bonemeal,v->draft.bonemeal=v);
        toggle("堆肥",1,186,()->draft.composter,v->draft.composter=v);
        toggle("破坏错误方块",0,212,()->draft.breakWrong,v->draft.breakWrong=v);
        toggle("破坏多余方块",1,212,()->draft.breakExtra,v->draft.breakExtra=v);
        toggle("破坏错误状态",0,238,()->draft.breakState,v->draft.breakState=v);
        toggle("清理流水",1,238,()->draft.flowing,v->draft.flowing=v);
        toggle("跳过含水方块",0,264,()->draft.skipWaterlogged,v->draft.skipWaterlogged=v);
        toggle("潜行放置",1,264,()->draft.forceSneak,v->draft.forceSneak=v);
    }
    private void updateDirection(){if(directionButton==null||!fields.containsKey("填充方块"))return;try{var candidate=PrinterSettings.read(draft.snapshot());candidate.fillState=fields.get("填充方块").getText();String direction=PrinterRules.direction(PrinterEngine.checkedFill(candidate));directionButton.setMessage(Text.literal(direction.isEmpty()?"方向":direction));directionButton.active=!direction.isEmpty();}catch(RuntimeException e){directionButton.setMessage(Text.literal("方向"));directionButton.active=false;}}
    private static String stateName(net.minecraft.block.BlockState state){var properties=new TreeMap<String,String>();for(var entry:state.getEntries().entrySet())properties.put(entry.getKey().getName(),propertyName(entry.getKey(),entry.getValue()));return new dev.betterlitematica.core.BlockStateSpec(net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString(),properties).toString();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String propertyName(net.minecraft.state.property.Property property,Comparable value){return property.name(value);}
    private void strategyPage(){
        toggle("破冰放水",0,56,()->draft.iceWater,v->draft.iceWater=v);toggle("珊瑚替代",1,56,()->draft.coralSubstitute,v->draft.coralSubstitute=v);toggle("侦测器顺序检查",0,82,()->draft.safeObserver,v->draft.safeObserver=v);
        toggle("容器填充",1,82,()->draft.containerFill,v->draft.containerFill=v);
        buttonAt("补给："+draft.supply.label(),cellX(0,2),118,cellWidth(2),()->{read();draft.supply=PrinterSupply.Source.values()[(draft.supply.ordinal()+1)%PrinterSupply.Source.values().length];refresh();},true,false);
    }
    private void highlightPage(){
        toggle("施工高亮",0,56,()->draft.highlights,v->draft.highlights=v);toggle("高亮置顶",1,56,()->draft.highlightOnTop,v->draft.highlightOnTop=v);
        buttonAt("样式："+switch(draft.highlightStyle){case OUTLINE->"轮廓";case FILLED->"填充";case BOTH->"轮廓与填充";},cellX(0,4),96,cellWidth(4),()->{read();draft.highlightStyle=PrinterSettings.HighlightStyle.values()[(draft.highlightStyle.ordinal()+1)%3];refresh();},true,false);
        number("时长 / ms",draft.highlightMillis,1,4,82,v->draft.highlightMillis=v);
        hint(fields.get("时长 / ms"),"放置反馈最多 450 ms，其它动作反馈使用此时长");
        number("高亮范围 / 格",draft.highlightRange,2,4,82,v->draft.highlightRange=v);
        number("高亮上限",draft.highlightLimit,3,4,82,v->draft.highlightLimit=v);
        hint(fields.get("高亮上限"),"0 不限");
        colorInput("放置颜色",draft.placeColor,0,132,v->draft.placeColor=v);colorInput("调节颜色",draft.adjustColor,1,132,v->draft.adjustColor=v);
        colorInput("破坏颜色",draft.breakColor,0,182,v->draft.breakColor=v);colorInput("失败颜色",draft.failedColor,1,182,v->draft.failedColor=v);
        buttonAt("恢复默认",cellX(0,2),232,cellWidth(2),this::resetColors,true,false);
    }
    private void resetColors(){
        var defaults=new PrinterSettings();draft.placeColor=defaults.placeColor;draft.adjustColor=defaults.adjustColor;draft.breakColor=defaults.breakColor;draft.failedColor=defaults.failedColor;
        for(String label:List.of("放置颜色","调节颜色","破坏颜色","失败颜色"))rawInputs.remove(label);
        error="";refresh();
    }
    private void colorInput(String label,int value,int column,int y,IntConsumer read){
        inputAt(label,String.format("%08X",value),cellX(column,2),y,cellWidth(2)-28,8,v->read.accept(color(v)));
        var field=fields.get(label);hint(field,"ARGB");
        addBody(new ColorSwatch(cellX(column,2)+cellWidth(2)-20,field::getText,label,()->{
            int initial=value;try{initial=color(field.getText().trim());}catch(IllegalArgumentException ignored){}
            client.setScreen(new ColorPickerScreen(this,controller,label,initial,chosen->{read.accept(chosen);rawInputs.put(label,String.format("%08X",chosen));error="";}));
        }),y+14);
    }
    private static int color(String text){if(!text.matches("[a-fA-F0-9]{8}"))throw new IllegalArgumentException("颜色需要 8 位 ARGB");return (int)Long.parseLong(text,16);}
    private void performancePage(){
        number("搜索线程",draft.threads,0,3,56,v->draft.threads=v);
        number("破坏间隔 / tick",draft.breakInterval,1,3,56,v->draft.breakInterval=v);
        number("每 tick 破坏上限",draft.breakPerTick,2,3,56,v->draft.breakPerTick=v);
        number("工作预算 / ms",draft.workBudgetMillis,0,3,104,v->draft.workBudgetMillis=v);
        hint(fields.get("工作预算 / ms"),"每 tick 的搜索与施工时间预算");
    }
    private void filtersPage(){
        listInput("跳过方块",draft.skip,56,RegistryListScreen.Kind.BLOCK,v->draft.skip=v);
        listInput("可替换方块",draft.replaceable,106,RegistryListScreen.Kind.BLOCK,v->draft.replaceable=v);
        listInput("清理流体",draft.fluids,156,RegistryListScreen.Kind.FLUID,v->draft.fluids=v);
        listInput("堆肥材料",draft.compostItems,206,RegistryListScreen.Kind.ITEM,v->draft.compostItems=v);
    }
    private void listInput(String label,List<String> values,int y,RegistryListScreen.Kind kind,Consumer<List<String>> set){
        inputAt(label,String.join(",",values),left,y,innerWidth-80,8192,v->set.accept(list(v)));
        var choose=buttonAt("选择",left+innerWidth-72,y+14,72,()->{read();client.setScreen(new RegistryListScreen(this,controller,label,kind,list(fields.get(label).getText()),v->{set.accept(new ArrayList<>(v));rawInputs.put(label,String.join(",",v));discardConfirmed=false;}));},true,false);
        choose.active=switch(label){case "跳过方块"->draft.print;case "可替换方块"->draft.replace;case "清理流体"->draft.fluid;case "堆肥材料"->draft.composter&&draft.print;default->true;};hint(choose,"搜索并选择"+label+"；支持名称、ID 和拼音。");
    }
    private static List<String> list(String text){return new ArrayList<>(Arrays.stream(text.split("[,，\\s]+",-1)).filter(s->!s.isBlank()).toList());}
    private void toggle(String name,int column,int y,BooleanSupplier get,Consumer<Boolean> set){buttonAt(name+"："+(get.getAsBoolean()?"开":"关"),cellX(column,2),y,cellWidth(2),()->{read();discardConfirmed=false;set.accept(!get.getAsBoolean());refresh();},true,get.getAsBoolean());}
    private void cycleScope(String name,int column,int columns,int y,Supplier<PrinterSettings.Scope> get,Consumer<PrinterSettings.Scope> set){
        var scope=get.get();var button=buttonAt(name+"："+switch(scope){case PROJECTION->"投影";case SELECTION->"选区";case BELOW->"选区 · 下方";case ABOVE->"选区 · 上方";},cellX(column,columns),y,cellWidth(columns),()->{read();set.accept(PrinterSettings.Scope.values()[(get.get().ordinal()+1)%4]);refresh();},true,false);
        if(scope==PrinterSettings.Scope.BELOW||scope==PrinterSettings.Scope.ABOVE)hint(button,scope==PrinterSettings.Scope.BELOW?"选区内 · 玩家下方":"选区内 · 玩家上方");
    }
    private void input(String label,String value,int column,int columns,int y,int max,Consumer<String> read){inputAt(label,value,cellX(column,columns),y,cellWidth(columns),max,read);}
    private void inputAt(String label,String value,int x,int y,int width,int max,Consumer<String> read){TextFieldWidget field=fieldAt(label,rawInputs.getOrDefault(label,value),x,y,width,max);fields.put(label,field);field.setChangedListener(v->{rawInputs.put(label,v);discardConfirmed=false;error="";notice="";invalidField=null;if(label.equals("填充方块"))updateDirection();});readers.add(()->{try{read.accept(field.getText().trim());}catch(RuntimeException e){focusInvalidField(field);if(e instanceof NumberFormatException)throw new IllegalArgumentException(label+"：请输入有效数值");throw e;}});}
    private void number(String label,int value,int column,int columns,int y,IntConsumer read){number(label,Integer.toString(value),column,columns,y,v->read.accept(Integer.parseInt(v)));}
    private void number(String label,String value,int column,int columns,int y,Consumer<String> read){input(label,value,column,columns,y,10,read);}
    private void focusInvalidField(TextFieldWidget field){invalidField=field;focusControl(field);}
    private void focusInvalidField(){for(var field:fields.entrySet()){String label=field.getKey().split("[ /（]",2)[0];if(error.startsWith(label)||label.equals("批次间隔")&&error.startsWith("间隔")||label.equals("时长")&&error.startsWith("高亮时长")){focusInvalidField(field.getValue());break;}}}
    private void read(){invalidField=null;try{for(var reader:readers)reader.run();draft.validate();error="";}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"设置无效");focusInvalidField();throw new IllegalArgumentException(error);}}
    private void save(){if(sessionEpoch!=controller.sessionEpoch())throw new IllegalStateException("世界已切换");read();try{controller.printer().configure(PrinterSettings.read(draft.snapshot()));}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"设置无效");focusInvalidField();throw e;}}
    private void commit(){save();super.close();}
    private void discard(){close();}
    @Override public void close(){if(sessionEpoch!=controller.sessionEpoch()){client.setScreen(null);return;}boolean dirty=!rawInputs.isEmpty()||!draft.snapshot().equals(controller.options().printer.snapshot());if(dirty&&!discardConfirmed){discardConfirmed=true;notice="再次返回将放弃未保存的更改";refresh();return;}super.close();}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==257||key==335)&&getFocused() instanceof TextFieldWidget){runAction(this::commit);return true;}return super.keyPressed(key,scan,modifiers);}
    private void dependencies(){
        if(!draft.highlights)for(String name:List.of("高亮置顶","样式","时长 / ms","高亮范围 / 格","高亮上限","放置颜色","调节颜色","破坏颜色","失败颜色"))enableSetting(name,false);
        if(!draft.hud)enableSetting("周围待建 HUD",false);
        if(!draft.replace)enableSetting("可替换方块",false);
        if(!draft.composter)enableSetting("堆肥材料",false);
        if(!draft.fluid){enableSetting("清理流体",false);enableSetting("清理流水",false);enableSetting("排流体范围",false);}
        if(!draft.fill)enableSetting("填充范围",false);
        if(!draft.fill&&!draft.fluid){enableSetting("填充方块",false);enableSetting("方向",false);}
        if(!draft.print)for(String name:List.of("原木去皮","音符盒调音","作物催熟","堆肥","容器填充","珊瑚替代","破冰放水","侦测器顺序检查","跳过含水方块","潜行放置","重力方块支撑检查","悬空放置","跳过方块"))enableSetting(name,false);
    }
    @Override protected void runAction(Runnable action){try{error="";notice="";action.run();}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"操作失败");}}
    @Override protected String displayedStatus(){return statusLine();}
    @Override protected int statusColor(){return error.isEmpty()?UiTheme.MUTED:UiTheme.ERROR;}
    @Override protected void updateMenu(){if(work==null)return;if(invalidField!=null){if(fields.containsValue(invalidField))setFocused(invalidField);invalidField=null;}var engine=controller.printer();work.setMessage(Text.literal(engine.running()?"暂停":engine.state()==PrinterEngine.State.PAUSED?"继续":"开始"));work.active=engine.running()||!controller.printerPlacements().isEmpty();stop.active=engine.state()!=PrinterEngine.State.STOPPED;}
    @Override protected String statusLine(){return !error.isEmpty()?error:!notice.isEmpty()?notice:controller.printer().status();}
}
