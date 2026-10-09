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
    private boolean discardConfirmed;private ButtonWidget exit;private final Set<String> invalidInputs=new HashSet<>();
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
    @Override protected boolean isSettingsPage(){return true;}
    @Override protected boolean showBack(){return false;}
    @Override protected void buildMenu(){
        readers.clear();fields.clear();
        addBody(new OverlayLabel(left,innerWidth,"启用投影 · "+controller.printerPlacements().size()),0);
        ghostAt("搜索设置",left+innerWidth-240,0,82,()->{read();client.setScreen(new SettingsSearchScreen(this,controller));},true);
        String[] tabs={"施工","通用","策略","过滤","性能","高亮"};
        for(int i=0;i<tabs.length;i++){int next=i;tabAt(tabs[i],cellX(i,tabs.length),26,cellWidth(tabs.length),()->{read();tab=next;refresh();},tab==i);}
        if(tab==0)workPage();else if(tab==1)settingsPage();else if(tab==2)strategyPage();else if(tab==3)filtersPage();else if(tab==4)performancePage();else highlightPage();
        work=fixed("开始",0,104,()->{
            var engine=controller.printer();
            if(engine.running()){engine.pause("已暂停");return;}
            try{save();engine.start();client.setScreen(null);}catch(RuntimeException e){error=e.getMessage();throw e;}
        });
        stop=fixed("停止",112,64,()->{controller.printer().stop();notice="已停止";});
        fixedAction("保存并返回",184,104,this::commit);
        exit=fixed(exitLabel(),innerWidth-88,88,this::discard);dependencies();updateMenu();
    }
    private void workPage(){
        ghostAt("破基岩设置与独立队列",left+innerWidth-150,0,150,()->{read();client.setScreen(new BedrockScreen(this,controller));},true);
        var modes=new WheelModes[]{WheelModes.PRINT,WheelModes.MINE,WheelModes.FILL,WheelModes.DRAIN,WheelModes.BEDROCK};
        for(int i=0;i<modes.length;i++){
            var mode=modes[i];int x=cellX(i,modes.length),w=cellWidth(modes.length);boolean enabled=mode.enabled(draft);
            var control=setting(switch(mode){case PRINT->SettingId.PRINTER_PRINT;case MINE->SettingId.PRINTER_MINE;case FILL->SettingId.PRINTER_FILL;case DRAIN->SettingId.PRINTER_DRAIN;case BEDROCK->SettingId.PRINTER_BEDROCK;},buttonAt(mode.label()+"："+(mode.mixed(draft)?"部分":enabled?"开":"关"),x,56,w,()->{read();draft=WheelModes.toggled(draft,mode);refresh();},true,enabled));
            hint(control,mode==WheelModes.BEDROCK?SettingHelp.text(SettingId.PRINTER_BEDROCK):"切换"+mode.label()+"模式；可直接保存并开始施工。");
            if(mode.mixed(draft)){var active=new ArrayList<String>();if(draft.breakWrong)active.add("错误方块");if(draft.breakExtra)active.add("多余方块");if(draft.breakState)active.add("错误状态");hint(control,String.join(" · ",active));}

        }
        cycleScope(SettingId.PRINTER_PRINT_SCOPE,"打印范围",0,4,88,()->draft.printScope,v->draft.printScope=v);
        cycleScope(SettingId.PRINTER_FILL_SCOPE,"填充范围",1,4,88,()->draft.fillScope,v->draft.fillScope=v);
        cycleScope(SettingId.PRINTER_FLUID_SCOPE,"排流体范围",2,4,88,()->draft.fluidScope,v->draft.fluidScope=v);
        cycleScope(SettingId.PRINTER_BEDROCK_SCOPE,"破基岩范围",3,4,88,()->draft.bedrockScope,v->draft.bedrockScope=v);
        inputAt(SettingId.PRINTER_FILL_STATE,"填充方块",draft.fillState,cellX(0,2),122,cellWidth(2)-76,256,v->draft.fillState=v);
        directionButton=setting(SettingId.PRINTER_FILL_DIRECTION,buttonAt("方向",cellX(0,2)+cellWidth(2)-68,136,68,()->{read();var state=PrinterRules.cycleDirection(PrinterEngine.checkedFill(draft));draft.fillState=stateName(state);rawInputs.put("填充方块",draft.fillState);refresh();},false,false));updateDirection();
        number(SettingId.PRINTER_RANGE,"距离（0 自动）",Double.toString(draft.range),1,2,122,v->draft.range=Double.parseDouble(v));
        number(SettingId.PRINTER_INTERVAL,"批次间隔 / tick",draft.interval,0,3,172,v->draft.interval=v);
        number(SettingId.PRINTER_PER_TICK,"每 tick 上限",draft.perTick,1,3,172,v->draft.perTick=v);
        number(SettingId.PRINTER_COOLDOWN,"位置冷却 / tick",draft.cooldown,2,3,172,v->draft.cooldown=v);
        hint(fields.get("批次间隔 / tick"),"0–1：每 tick；2：每 2 tick");
        hint(fields.get("每 tick 上限"),"0：按时间预算执行");
        hint(fields.get("位置冷却 / tick"),"同一位置再次尝试前的等待时间");
        toggle(SettingId.PRINTER_HUD,"状态 HUD",0,222,()->draft.hud,v->draft.hud=v);
        toggle(SettingId.PRINTER_NEARBY_HUD,"周围待建 HUD",1,222,()->draft.missingHud,v->draft.missingHud=v);

    }
    private void settingsPage(){
        setting(SettingId.PRINTER_SHAPE,buttonAt("范围形状："+switch(draft.shape){case SPHERE->"球形";case OCTAHEDRON->"八面体";case CUBE->"立方体";},cellX(0,2),56,cellWidth(2),()->{read();draft.shape=PrinterRange.Shape.values()[(draft.shape.ordinal()+1)%3];refresh();},true,false));
        setting(SettingId.PRINTER_ORDER,buttonAt("顺序："+draft.order,cellX(1,2),56,cellWidth(2),()->{read();var orders=List.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX");draft.order=orders.get((orders.indexOf(draft.order)+1)%6);refresh();},true,false));
        toggle(SettingId.PRINTER_REVERSE_X,"X 反向",0,82,()->draft.reverseX,v->draft.reverseX=v);
        toggle(SettingId.PRINTER_REVERSE_Y,"Y 反向",1,82,()->draft.reverseY,v->draft.reverseY=v);
        toggle(SettingId.PRINTER_REVERSE_Z,"Z 反向",0,108,()->draft.reverseZ,v->draft.reverseZ=v);
        toggle(SettingId.PRINTER_AIR_PLACE,"悬空放置",1,108,()->draft.airPlace,v->draft.airPlace=v);
        toggle(SettingId.PRINTER_FALLING_CHECK,"重力方块支撑检查",0,134,()->draft.fallingCheck,v->draft.fallingCheck=v);
        toggle(SettingId.PRINTER_REPLACE,"替换可替换方块",1,134,()->draft.replace,v->draft.replace=v);
        toggle(SettingId.PRINTER_STRIP_LOGS,"原木去皮",0,160,()->draft.stripLogs,v->draft.stripLogs=v);
        toggle(SettingId.PRINTER_NOTE_TUNING,"音符盒调音",1,160,()->draft.noteTuning,v->draft.noteTuning=v);
        toggle(SettingId.PRINTER_BONEMEAL,"作物催熟",0,186,()->draft.bonemeal,v->draft.bonemeal=v);
        toggle(SettingId.PRINTER_COMPOSTER,"堆肥",1,186,()->draft.composter,v->draft.composter=v);
        toggle(SettingId.PRINTER_BREAK_WRONG,"破坏错误方块",0,212,()->draft.breakWrong,v->draft.breakWrong=v);
        toggle(SettingId.PRINTER_BREAK_EXTRA,"破坏多余方块",1,212,()->draft.breakExtra,v->draft.breakExtra=v);
        toggle(SettingId.PRINTER_BREAK_STATE,"破坏错误状态",0,238,()->draft.breakState,v->draft.breakState=v);
        toggle(SettingId.PRINTER_FLOWING,"清理流水",1,238,()->draft.flowing,v->draft.flowing=v);
        toggle(SettingId.PRINTER_SKIP_WATERLOGGED,"跳过含水方块",0,264,()->draft.skipWaterlogged,v->draft.skipWaterlogged=v);
        toggle(SettingId.PRINTER_FORCE_SNEAK,"潜行放置",1,264,()->draft.forceSneak,v->draft.forceSneak=v);
    }
    private void updateDirection(){if(directionButton==null||!fields.containsKey("填充方块"))return;try{var candidate=PrinterSettings.read(draft.snapshot());candidate.fillState=fields.get("填充方块").getText();String direction=PrinterRules.direction(PrinterEngine.checkedFill(candidate));directionButton.setMessage(Text.literal(direction.isEmpty()?"方向":direction));directionButton.active=!direction.isEmpty();}catch(RuntimeException e){directionButton.setMessage(Text.literal("方向"));directionButton.active=false;}}
    private static String stateName(net.minecraft.block.BlockState state){var properties=new TreeMap<String,String>();for(var entry:state.getEntries().entrySet())properties.put(entry.getKey().getName(),propertyName(entry.getKey(),entry.getValue()));return new dev.betterlitematica.core.BlockStateSpec(net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString(),properties).toString();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String propertyName(net.minecraft.state.property.Property property,Comparable value){return property.name(value);}
    private void strategyPage(){
        toggle(SettingId.PRINTER_ICE_WATER,"破冰放水",0,56,()->draft.iceWater,v->draft.iceWater=v);toggle(SettingId.PRINTER_CORAL,"珊瑚替代",1,56,()->draft.coralSubstitute,v->draft.coralSubstitute=v);toggle(SettingId.PRINTER_SAFE_OBSERVER,"侦测器顺序检查",0,82,()->draft.safeObserver,v->draft.safeObserver=v);
        toggle(SettingId.PRINTER_CONTAINER_FILL,"容器填充",1,82,()->draft.containerFill,v->draft.containerFill=v);
        setting(SettingId.PRINTER_SUPPLY,buttonAt("补给："+draft.supply.label(),cellX(0,2),118,cellWidth(2),()->{read();draft.supply=PrinterSupply.Source.values()[(draft.supply.ordinal()+1)%PrinterSupply.Source.values().length];refresh();},true,false));
    }
    private void highlightPage(){
        toggle(SettingId.PRINTER_HIGHLIGHTS,"施工高亮",0,56,()->draft.highlights,v->draft.highlights=v);toggle(SettingId.PRINTER_HIGHLIGHT_ON_TOP,"高亮置顶",1,56,()->draft.highlightOnTop,v->draft.highlightOnTop=v);
        setting(SettingId.PRINTER_HIGHLIGHT_STYLE,buttonAt("样式："+switch(draft.highlightStyle){case OUTLINE->"轮廓";case FILLED->"填充";case BOTH->"轮廓与填充";},cellX(0,4),96,cellWidth(4),()->{read();draft.highlightStyle=PrinterSettings.HighlightStyle.values()[(draft.highlightStyle.ordinal()+1)%3];refresh();},true,false));
        number(SettingId.PRINTER_HIGHLIGHT_TIME,"时长 / ms",draft.highlightMillis,1,4,82,v->draft.highlightMillis=v);
        hint(fields.get("时长 / ms"),"放置反馈最多 450 ms，其它动作反馈使用此时长");
        number(SettingId.PRINTER_HIGHLIGHT_RANGE,"高亮范围 / 格",draft.highlightRange,2,4,82,v->draft.highlightRange=v);
        number(SettingId.PRINTER_HIGHLIGHT_LIMIT,"高亮上限",draft.highlightLimit,3,4,82,v->draft.highlightLimit=v);
        hint(fields.get("高亮上限"),"0 不限");
        colorInput(SettingId.PRINTER_PLACE_COLOR,"放置颜色",draft.placeColor,0,132,v->draft.placeColor=v);colorInput(SettingId.PRINTER_ADJUST_COLOR,"调节颜色",draft.adjustColor,1,132,v->draft.adjustColor=v);
        colorInput(SettingId.PRINTER_BREAK_COLOR,"破坏颜色",draft.breakColor,0,182,v->draft.breakColor=v);colorInput(SettingId.PRINTER_FAILED_COLOR,"失败颜色",draft.failedColor,1,182,v->draft.failedColor=v);
        setting(SettingId.PRINTER_RESET_COLORS,buttonAt("恢复默认",cellX(0,2),232,cellWidth(2),this::resetColors,true,false));
    }
    private void resetColors(){
        var defaults=new PrinterSettings();draft.placeColor=defaults.placeColor;draft.adjustColor=defaults.adjustColor;draft.breakColor=defaults.breakColor;draft.failedColor=defaults.failedColor;
        for(String label:List.of("放置颜色","调节颜色","破坏颜色","失败颜色"))rawInputs.remove(label);
        error="";refresh();
    }
    private void colorInput(SettingId id,String label,int value,int column,int y,IntConsumer read){
        inputAt(id,label,String.format("%08X",value),cellX(column,2),y,cellWidth(2)-28,8,v->read.accept(color(v)));
        var field=fields.get(label);hint(field,"ARGB");
        addBody(setting(id,new ColorSwatch(cellX(column,2)+cellWidth(2)-20,field::getText,label,()->{
            int initial=value;try{initial=color(field.getText().trim());}catch(IllegalArgumentException ignored){}
            client.setScreen(new ColorPickerScreen(this,controller,label,initial,chosen->{read.accept(chosen);rawInputs.put(label,String.format("%08X",chosen));error="";}));
        })),y+14);
    }
    private static int color(String text){if(!text.matches("[a-fA-F0-9]{8}"))throw new IllegalArgumentException("颜色需要 8 位 ARGB");return (int)Long.parseLong(text,16);}
    private void performancePage(){
        number(SettingId.PRINTER_THREADS,"搜索线程",draft.threads,0,3,56,v->draft.threads=v);
        number(SettingId.PRINTER_BREAK_INTERVAL,"破坏间隔 / tick",draft.breakInterval,1,3,56,v->draft.breakInterval=v);
        number(SettingId.PRINTER_BREAK_PER_TICK,"每 tick 破坏上限",draft.breakPerTick,2,3,56,v->draft.breakPerTick=v);
        number(SettingId.PRINTER_BUDGET,"工作预算 / ms",draft.workBudgetMillis,0,3,104,v->draft.workBudgetMillis=v);
        hint(fields.get("工作预算 / ms"),"每 tick 的搜索与施工时间预算");
    }
    private void filtersPage(){
        listInput(SettingId.PRINTER_SKIP,"跳过方块",draft.skip,56,RegistryListScreen.Kind.BLOCK,v->draft.skip=v);
        listInput(SettingId.PRINTER_REPLACEABLE,"可替换方块",draft.replaceable,106,RegistryListScreen.Kind.BLOCK,v->draft.replaceable=v);
        listInput(SettingId.PRINTER_FLUIDS,"清理流体",draft.fluids,156,RegistryListScreen.Kind.FLUID,v->draft.fluids=v);
        listInput(SettingId.PRINTER_COMPOST_ITEMS,"堆肥材料",draft.compostItems,206,RegistryListScreen.Kind.ITEM,v->draft.compostItems=v);
    }
    private void listInput(SettingId id,String label,List<String> values,int y,RegistryListScreen.Kind kind,Consumer<List<String>> set){
        inputAt(id,label,String.join(",",values),left,y,innerWidth-80,8192,v->set.accept(list(v)));
        var choose=setting(id,buttonAt("选择",left+innerWidth-72,y+14,72,()->{read();client.setScreen(new RegistryListScreen(this,controller,label,kind,list(fields.get(label).getText()),v->{set.accept(new ArrayList<>(v));rawInputs.put(label,String.join(",",v));discardConfirmed=false;}));},true,false));
        choose.active=switch(id){case PRINTER_SKIP->draft.print;case PRINTER_REPLACEABLE->draft.replace;case PRINTER_FLUIDS->draft.fluid;case PRINTER_COMPOST_ITEMS->draft.composter&&draft.print;default->true;};hint(choose,"搜索并选择"+label+"；支持名称、ID 和拼音。");
    }
    private static List<String> list(String text){return new ArrayList<>(Arrays.stream(text.split("[,，\\s]+",-1)).filter(s->!s.isBlank()).toList());}
    private void toggle(SettingId id,String name,int column,int y,BooleanSupplier get,Consumer<Boolean> set){setting(id,buttonAt(name+"："+(get.getAsBoolean()?"开":"关"),cellX(column,2),y,cellWidth(2),()->{read();discardConfirmed=false;set.accept(!get.getAsBoolean());refresh();},true,get.getAsBoolean()));}
    private void cycleScope(SettingId id,String name,int column,int columns,int y,Supplier<PrinterSettings.Scope> get,Consumer<PrinterSettings.Scope> set){
        var scope=get.get();var button=setting(id,buttonAt(name+"："+switch(scope){case PROJECTION->"投影";case SELECTION->"选区";case BELOW->"选区 · 下方";case ABOVE->"选区 · 上方";},cellX(column,columns),y,cellWidth(columns),()->{read();set.accept(PrinterSettings.Scope.values()[(get.get().ordinal()+1)%4]);refresh();},true,false));
        if(scope==PrinterSettings.Scope.BELOW||scope==PrinterSettings.Scope.ABOVE)hint(button,scope==PrinterSettings.Scope.BELOW?"选区内 · 玩家下方":"选区内 · 玩家上方");
    }
    private void input(SettingId id,String label,String value,int column,int columns,int y,int max,Consumer<String> read){inputAt(id,label,value,cellX(column,columns),y,cellWidth(columns),max,read);}
    private void inputAt(SettingId id,String label,String value,int x,int y,int width,int max,Consumer<String> read){TextFieldWidget field=setting(id,fieldAt(label,rawInputs.getOrDefault(label,value),x,y,width,max));fields.put(label,field);field.setChangedListener(v->{rawInputs.put(label,v);try{read.accept(v.trim());invalidInputs.remove(label);}catch(RuntimeException invalid){invalidInputs.add(label);}discardConfirmed=false;error="";notice="";invalidField=null;if(label.equals("填充方块"))updateDirection();});readers.add(()->{try{read.accept(field.getText().trim());}catch(RuntimeException e){focusInvalidField(field);if(e instanceof NumberFormatException)throw new IllegalArgumentException(label+"：请输入有效数值");throw e;}});}
    private void number(SettingId id,String label,int value,int column,int columns,int y,IntConsumer read){number(id,label,Integer.toString(value),column,columns,y,v->read.accept(Integer.parseInt(v)));}
    private void number(SettingId id,String label,String value,int column,int columns,int y,Consumer<String> read){input(id,label,value,column,columns,y,10,read);}
    private void focusInvalidField(TextFieldWidget field){invalidField=field;focusControl(field);}
    private void focusInvalidField(){for(var field:fields.entrySet()){String label=field.getKey().split("[ /（]",2)[0];if(error.startsWith(label)||label.equals("批次间隔")&&error.startsWith("间隔")||label.equals("时长")&&error.startsWith("高亮时长")){focusInvalidField(field.getValue());break;}}}
    private void read(){discardConfirmed=false;notice="";invalidField=null;try{for(var reader:readers)reader.run();draft.validate();error="";}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"设置无效");focusInvalidField();throw new IllegalArgumentException(error);}}
    private void save(){if(sessionEpoch!=controller.sessionEpoch())throw new IllegalStateException("世界已切换");read();try{controller.printer().configure(PrinterSettings.read(draft.snapshot()));draft=PrinterSettings.read(controller.options().printer.snapshot());rawInputs.clear();invalidInputs.clear();discardConfirmed=false;}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"设置无效");focusInvalidField();throw e;}}
    private void commit(){save();super.close();}
    private boolean dirty(){return !invalidInputs.isEmpty()||!draft.snapshot().equals(controller.options().printer.snapshot());}
    private String exitLabel(){return discardConfirmed?"确认放弃":dirty()?"放弃":"返回";}
    private void discard(){close();}
    @Override public void close(){if(sessionEpoch!=controller.sessionEpoch()){client.setScreen(null);return;}if(dirty()&&!discardConfirmed){discardConfirmed=true;notice="再次返回将放弃未保存的更改";refresh();return;}super.close();}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==257||key==335)&&getFocused() instanceof TextFieldWidget){runAction(this::commit);return true;}return super.keyPressed(key,scan,modifiers);}
    private void dependencies(){
        if(!draft.highlights)for(var id:List.of(SettingId.PRINTER_HIGHLIGHT_ON_TOP,SettingId.PRINTER_HIGHLIGHT_STYLE,SettingId.PRINTER_HIGHLIGHT_TIME,SettingId.PRINTER_HIGHLIGHT_RANGE,SettingId.PRINTER_HIGHLIGHT_LIMIT,SettingId.PRINTER_PLACE_COLOR,SettingId.PRINTER_ADJUST_COLOR,SettingId.PRINTER_BREAK_COLOR,SettingId.PRINTER_FAILED_COLOR))enableSetting(id,false);
        if(!draft.hud)enableSetting(SettingId.PRINTER_NEARBY_HUD,false);
        if(!draft.replace)enableSetting(SettingId.PRINTER_REPLACEABLE,false);
        if(!draft.composter)enableSetting(SettingId.PRINTER_COMPOST_ITEMS,false);
        if(!draft.fluid){enableSetting(SettingId.PRINTER_FLUIDS,false);enableSetting(SettingId.PRINTER_FLOWING,false);enableSetting(SettingId.PRINTER_FLUID_SCOPE,false);}
        if(!draft.bedrock)enableSetting(SettingId.PRINTER_BEDROCK_SCOPE,false);
        if(!draft.fill)enableSetting(SettingId.PRINTER_FILL_SCOPE,false);
        if(!draft.fill&&!draft.fluid){enableSetting(SettingId.PRINTER_FILL_STATE,false);enableSetting(SettingId.PRINTER_FILL_DIRECTION,false);}
        if(!draft.print)for(var id:List.of(SettingId.PRINTER_STRIP_LOGS,SettingId.PRINTER_NOTE_TUNING,SettingId.PRINTER_BONEMEAL,SettingId.PRINTER_COMPOSTER,SettingId.PRINTER_CONTAINER_FILL,SettingId.PRINTER_CORAL,SettingId.PRINTER_ICE_WATER,SettingId.PRINTER_SAFE_OBSERVER,SettingId.PRINTER_SKIP_WATERLOGGED,SettingId.PRINTER_FORCE_SNEAK,SettingId.PRINTER_FALLING_CHECK,SettingId.PRINTER_AIR_PLACE,SettingId.PRINTER_SKIP))enableSetting(id,false);
    }
    @Override protected void runAction(Runnable action){try{error="";notice="";action.run();}catch(RuntimeException e){error=Objects.toString(e.getMessage(),"操作失败");}}
    @Override protected String displayedStatus(){return statusLine();}
    @Override protected int statusColor(){return error.isEmpty()?UiTheme.MUTED:UiTheme.ERROR;}
    @Override protected void updateMenu(){if(work==null)return;if(invalidField!=null){if(fields.containsValue(invalidField))setFocused(invalidField);invalidField=null;}var engine=controller.printer();if(exit!=null)exit.setMessage(Text.literal(exitLabel()));work.setMessage(Text.literal(engine.running()?"暂停":dirty()?"保存并开始":engine.state()==PrinterEngine.State.PAUSED?"继续":"开始"));work.active=engine.running()||engine.hasWorkArea(draft);stop.active=engine.state()!=PrinterEngine.State.STOPPED;}
    @Override protected String statusLine(){return !error.isEmpty()?error:!notice.isEmpty()?notice:controller.printer().status();}
}
