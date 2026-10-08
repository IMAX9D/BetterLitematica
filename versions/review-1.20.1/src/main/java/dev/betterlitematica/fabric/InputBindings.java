package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

final class InputBindings {
    private final Map<String,Boolean> previous=new HashMap<>();private boolean menuHeld,usedChord,awaitRelease=true;
    private Map<String,String> snapshot=Map.of();private List<Binding> compiled=List.of();
    private final Set<String> holds=new HashSet<>();
    private final ArrayDeque<Map<String,Boolean>> events=new ArrayDeque<>();
    private Object world;
    boolean held(String action){return holds.contains(action);}
    void suspend(){reset();}
    private static boolean holdAction(String action){return Set.of("easyPlaceHold","restrictionHold","hideProjection").contains(action);}
    private record Binding(String action,String chord,List<String> keys){}
    private static String key(String text){String name=text.strip().toUpperCase(Locale.ROOT).replace(' ','_');return switch(name){case "CONTROL"->"CTRL";case "LEFT_CTRL"->"LEFT_CONTROL";case "RIGHT_CTRL"->"RIGHT_CONTROL";case "ESC"->"ESCAPE";case "RETURN"->"ENTER";case "WIN","CMD"->"SUPER";default->name;};}
    static int code(String name){
        String value=key(name);if(value.matches("MOUSE[1-8]"))return -Integer.parseInt(value.substring(5));
        value=switch(value){case "CTRL"->"LEFT_CONTROL";case "SHIFT"->"LEFT_SHIFT";case "ALT"->"LEFT_ALT";case "SUPER"->"LEFT_SUPER";default->value;};
        if(value.equals("UNKNOWN")||value.equals("LAST"))throw new IllegalArgumentException("无效按键");
        try{return GLFW.class.getField("GLFW_KEY_"+value).getInt(null);}catch(ReflectiveOperationException e){throw new IllegalArgumentException("无效按键："+name);}
    }
    static String name(int code){
        return switch(code){case GLFW.GLFW_KEY_LEFT_CONTROL,GLFW.GLFW_KEY_RIGHT_CONTROL->"CTRL";case GLFW.GLFW_KEY_LEFT_ALT,GLFW.GLFW_KEY_RIGHT_ALT->"ALT";case GLFW.GLFW_KEY_LEFT_SHIFT,GLFW.GLFW_KEY_RIGHT_SHIFT->"SHIFT";case GLFW.GLFW_KEY_LEFT_SUPER,GLFW.GLFW_KEY_RIGHT_SUPER->"SUPER";default->{String result="";for(var f:GLFW.class.getFields())if(f.getName().startsWith("GLFW_KEY_")&&!f.getName().endsWith("_LAST")&&!f.getName().endsWith("_UNKNOWN")){try{if(f.getInt(null)==code){result=f.getName().substring(9);break;}}catch(IllegalAccessException ignored){}}if(result.isEmpty())throw new IllegalArgumentException("无效按键");yield result;}};
    }
    static String normalize(String chord){if(chord==null||chord.length()>120)throw new IllegalArgumentException("无效组合键");if(chord.isBlank())return "";var keys=new TreeSet<String>(Comparator.comparingInt(InputBindings::priority).thenComparing(Comparator.naturalOrder()));for(String token:chord.split("\\+",-1)){String value=key(token);value=switch(value){case "LEFT_CONTROL","RIGHT_CONTROL"->"CTRL";case "LEFT_SHIFT","RIGHT_SHIFT"->"SHIFT";case "LEFT_ALT","RIGHT_ALT"->"ALT";case "LEFT_SUPER","RIGHT_SUPER"->"SUPER";default->value;};code(value);if(!keys.add(value))throw new IllegalArgumentException("组合键包含重复按键");}if(keys.size()>4)throw new IllegalArgumentException("最多四键组合");return String.join("+",keys);}
    private static int priority(String key){return switch(key){case "CTRL"->0;case "ALT"->1;case "SHIFT"->2;case "SUPER"->3;default->4;};}
    static void validate(String chord){normalize(chord);}
    static String conflict(Map<String,String> bindings,String action,String value){String chord=normalize(value);if(chord.isEmpty())return "";for(var e:bindings.entrySet())if(!e.getKey().equals(action)&&!action.endsWith("Modifier")&&!e.getKey().endsWith("Modifier")&&!action.startsWith("toolEdit")&&!e.getKey().startsWith("toolEdit")&&(normalize(e.getValue()).equals(chord)||((action.equals("wheel")||e.getKey().equals("wheel"))&&!normalize(e.getValue()).isEmpty()&&(moreSpecific(chord,e.getValue())||moreSpecific(e.getValue(),chord)))))return e.getKey();return "";}
    static boolean pressedChord(MinecraftClient client,String chord){String normalized=normalize(chord);return !normalized.isEmpty()&&Arrays.stream(normalized.split("\\+")).allMatch(k->down(client,k));}
    static boolean toolDirect(String action){return Set.of("toolPrimary","toolSecondary","toolSelect","toolCycleModifier","toolNudgeModifier","toolGrabModifier","toolGrowModifier","toolPrimaryModifier","toolSecondaryModifier","toolEditDirection","toolEditAll","toolEditType","toolEditExcept","toolEditFillAir").contains(action);}
    private static boolean down(MinecraftClient client,String key){if(ModeWheelInput.reservesKey(client,code(key)))return false;long window=client.getWindow().getHandle();return switch(key){case "CTRL"->InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_LEFT_CONTROL)||InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_RIGHT_CONTROL);case "SHIFT"->InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_LEFT_SHIFT)||InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_RIGHT_SHIFT);case "ALT"->InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_LEFT_ALT)||InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_RIGHT_ALT);case "SUPER"->InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_LEFT_SUPER)||InputUtil.isKeyPressed(window,GLFW.GLFW_KEY_RIGHT_SUPER);default->{int code=code(key);yield code<0?GLFW.glfwGetMouseButton(window,-code-1)==GLFW.GLFW_PRESS:InputUtil.isKeyPressed(window,code);}};}
    private static boolean down(MinecraftClient client,Binding binding){return !binding.keys.isEmpty()&&binding.keys.stream().allMatch(k->down(client,k));}
    static boolean matches(MinecraftClient client,String chord,int pressed){
        String normalized=normalize(chord);if(normalized.isEmpty())return false;var keys=List.of(normalized.split("\\+"));if(keys.stream().anyMatch(k->ModeWheelInput.reservesKey(client,code(k))))return false;return keys.stream().anyMatch(k->code(k)==pressed)&&keys.stream().allMatch(k->code(k)==pressed||down(client,k));
    }
    private void reset(){events.clear();previous.clear();holds.clear();menuHeld=usedChord=false;awaitRelease=true;}
    private void configure(Map<String,String> keys,Object session){
        if(world!=session){world=session;reset();}
        if(!snapshot.equals(keys)){snapshot=Map.copyOf(keys);compiled=keys.entrySet().stream().filter(e->!e.getKey().equals("wheel")&&!toolDirect(e.getKey())).map(e->{String chord=normalize(e.getValue());return new Binding(e.getKey(),chord,chord.isEmpty()?List.of():List.of(chord.split("\\+")));}).toList();reset();}
    }
    private Map<String,Boolean> sample(Predicate<String> down){var held=new LinkedHashMap<String,Boolean>();for(var binding:compiled)held.put(binding.action,!binding.keys.isEmpty()&&binding.keys.stream().allMatch(down));return held;}
    // Capture transitions while GLFW still exposes the other keys in this chord.
    void event(MinecraftClient client,InteractionOptions options,long window,int changed,int event){
        if(window!=client.getWindow().getHandle()||event==GLFW.GLFW_REPEAT)return;
        capture(options.keys,client.world,client.world!=null&&client.player!=null&&client.currentScreen==null&&client.isWindowFocused(),key->{
            if(ModeWheelInput.reservesKey(client,code(key)))return false;
            int code=code(key);boolean pressed=event==GLFW.GLFW_PRESS;
            int right=switch(key){case "CTRL"->GLFW.GLFW_KEY_RIGHT_CONTROL;case "SHIFT"->GLFW.GLFW_KEY_RIGHT_SHIFT;case "ALT"->GLFW.GLFW_KEY_RIGHT_ALT;case "SUPER"->GLFW.GLFW_KEY_RIGHT_SUPER;default->0;};
            if(right!=0){boolean left=code==changed?pressed:InputUtil.isKeyPressed(window,code);boolean r=right==changed?pressed:InputUtil.isKeyPressed(window,right);return left||r;}
            return code==changed?pressed:down(client,key);
        });
    }
    void capture(Map<String,String> keys,Object session,boolean active,Predicate<String> down){
        configure(keys,session);if(!active){reset();return;}
        if(events.size()==64){reset();return;}events.addLast(sample(down));
    }
    void tick(MinecraftClient client,InteractionOptions options,Consumer<String> action){
        poll(options.keys,client.world,()->client.world!=null&&client.player!=null&&client.currentScreen==null&&client.isWindowFocused(),key->down(client,key),action);
    }
    void poll(Map<String,String> keys,Object session,BooleanSupplier active,Predicate<String> down,Consumer<String> action){
        configure(keys,session);holds.clear();if(!active.getAsBoolean()){reset();return;}
        while(!events.isEmpty()){process(events.removeFirst(),active,action);if(!active.getAsBoolean()){reset();return;}}
        process(sample(down),active,action);
    }
    private void process(Map<String,Boolean> held,BooleanSupplier active,Consumer<String> action){
        holds.clear();if(awaitRelease){if(held.values().stream().noneMatch(Boolean::booleanValue)){awaitRelease=false;usedChord=false;menuHeld=false;previous.clear();}return;}boolean menu=held.getOrDefault("menu",false);var fired=new HashSet<String>();
        for(var binding:compiled){boolean pressed=held.get(binding.action),old=previous.getOrDefault(binding.action,false);previous.put(binding.action,pressed);if(binding.action.equals("menu")){if(pressed)fired.add(binding.chord);continue;}
            boolean shadowed=pressed&&compiled.stream().anyMatch(b->held.get(b.action)&&b.keys.size()>binding.keys.size()&&b.keys.containsAll(binding.keys));
            if(holdAction(binding.action)){if(pressed&&!shadowed){holds.add(binding.action);usedChord|=menu;}continue;}
            if(pressed&&!old&&!shadowed&&fired.add(binding.chord)&&active.getAsBoolean()){usedChord|=menu;action.accept(binding.action);}
        }
        if(menuHeld&&!menu){if(!usedChord&&active.getAsBoolean())action.accept("menu");usedChord=false;}menuHeld=menu;
    }
    static boolean moreSpecific(String chord,String base){String a=normalize(chord),b=normalize(base);if(a.isEmpty()||b.isEmpty())return false;var keys=new HashSet<>(List.of(a.split("\\+")));var original=new HashSet<>(List.of(b.split("\\+")));return keys.size()>original.size()&&keys.containsAll(original);}
    static String label(String key){return switch(key){case "editUndo"->"撤销投影编辑";case "editRedo"->"重做投影编辑";case "easyPlaceHold"->"暂时简单放置";case "restrictionHold"->"暂时放置限制";case "hideProjection"->"暂时隐藏投影";case "restriction"->"放置限制";case "toolMode"->"下一工具模式";case "toolModePrevious"->"上一工具模式";case "toolPrimary"->"工具主操作";case "toolSecondary"->"工具副操作";case "toolSelect"->"工具选择";case "toolCycleModifier"->"工具切换修饰键";case "toolNudgeModifier"->"工具微调修饰键";case "toolGrabModifier"->"工具抓取修饰键";case "toolGrowModifier"->"选区扩缩修饰键";case "toolPrimaryModifier"->"主方块取样修饰键";case "toolSecondaryModifier"->"副方块取样修饰键";case "toolExecute"->"执行工具操作";case "toolSettings"->"工具设置";case "toolClone"->"克隆选区";case "toolResetOrigin"->"重置选区原点";case "toolMoveSelection"->"移动整个选区";case "toolSaveSelection"->"保存选区";case "toolGrow"->"选区自动扩大";case "toolShrink"->"选区自动收缩";case "toolEditDirection"->"编辑直线修饰键";case "toolEditAll"->"编辑同状态修饰键";case "toolEditType"->"编辑同类型修饰键";case "toolEditExcept"->"编辑排除类型修饰键";case "toolEditFillAir"->"编辑填空气修饰键";case "toolNudgePositive"->"工具正向微调";case "toolNudgeNegative"->"工具反向微调";case "toolSelectionShape"->"角点与扩展选择";case "layerModePrevious"->"上一分层模式";case "layerFollow"->"分层跟随玩家";case "selectionFirst"->"角点 1";case "selectionSecond"->"角点 2";case "selectionOrigin"->"选区原点";case "selectionAdd"->"新增选区";case "selectionRemove"->"移除选区";case "selectionMode"->"选区模式";case "placementHere"->"投影移到玩家";case "rotate"->"投影旋转";case "reload"->"重载投影";case "information"->"方块信息";case "layerMode"->"分层模式";case "layerPlayer"->"分层移到玩家";case "pickLast"->"拾取末端方块";case "printer"->"打印机";case "printerWork"->"打印开关";case "printerStop"->"停止打印";case "printerMode"->"轮换施工模式";case "printerPrint"->"切换打印模式";case "printerMine"->"切换挖掘模式";case "printerFill"->"切换填充模式";case "printerDrain"->"切换排流体模式";case "printerBedrock"->"切换破基岩模式";case "printerAllOff"->"全部施工模式关闭";case "menu"->"主菜单";case "wheel"->"快捷轮盘";case "placements"->"摆放列表";case "materials"->"材料清单";case "verifier"->"校验器";case "selection"->"选区";case "settings"->"设置";case "rendering"->"渲染开关";case "tool"->"工具开关";case "easyPlace"->"简单放置";case "layerNext"->"分层上移";case "layerPrevious"->"分层下移";default->key;};}
}
