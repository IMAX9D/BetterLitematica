package dev.betterlitematica.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import java.util.*;

final class OptionsScreen extends MenuScreen {
    private TextFieldWidget chord,search;private String selected="menu",query="";private int tab;private boolean recording;private OverlayList list;
    private final Map<String,String> drafts=new HashMap<>();private final LinkedHashSet<String> pressed=new LinkedHashSet<>();
    private net.minecraft.client.gui.widget.ButtonWidget cancelRecord;
    private final net.minecraft.client.gui.widget.ButtonWidget[] tabs=new net.minecraft.client.gui.widget.ButtonWidget[3];
    OptionsScreen(Screen parent,ProjectionController controller){super("设置与快捷键","",parent,controller,true);}
    private String on(boolean v){return v?"开启":"关闭";}
    private void save(){controller.saveOptions();refresh();}
    private void selectTab(int next){recording=false;pressed.clear();tab=next;refresh();}
    private void bind(String value){String normalized=InputBindings.normalize(value),conflict=InputBindings.conflict(controller.options().keys,selected,normalized);if(selected.equals("wheel"))ModeWheelInput.bindingCode(normalized);if(!conflict.isEmpty())throw new IllegalArgumentException("快捷键已用于："+InputBindings.label(conflict));controller.options().keys.put(selected,normalized);drafts.put(selected,normalized);controller.saveOptions();refresh();}
    private void rows(){if(list!=null)list.rows(controller.options().keys.keySet().stream().filter(k->(InputBindings.label(k)+k).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).map(k->new OverlayList.Row(k,InputBindings.label(k),!controller.options().keys.get(k).isEmpty())).toList(),selected);}
    @Override protected void buildMenu(){
        chord=null;search=null;cancelRecord=null;
        tabs[0]=tab("交互设置",0,3,0,()->selectTab(0),tab==0);tabs[1]=tab("显示",1,3,0,()->selectTab(1),tab==1);tabs[2]=tab("快捷键",2,3,0,()->selectTab(2),tab==2);var s=controller.options();
        if(tab==1){DisplayScreen.buildControls(this,controller,2,()->client.setScreen(new LayerScreen(this,controller)),this::save);}
        else if(tab==0){
            button("选区工具："+on(s.tool),0,2,2,()->{s.tool=!s.tool;save();});button("工具设置",1,2,2,()->client.setScreen(new ToolScreen(this,controller)));
            button("简单放置："+on(s.easyPlace),0,2,3,()->{s.easyPlace=!s.easyPlace;save();});button("按住连续："+on(s.hold),1,2,3,()->{s.hold=!s.hold;save();});
            button("放置限制："+on(s.restriction),0,2,4,()->{s.restriction=!s.restriction;save();});button("投影拾取："+on(s.pick),1,2,4,()->{s.pick=!s.pick;save();});
            // Selection boxes live only under 显示; one switch per setting.
            button("精确放置："+s.accurate.label(),0,2,5,()->{s.accurate=AccuratePlacement.Mode.values()[(s.accurate.ordinal()+1)%AccuratePlacement.Mode.values().length];controller.printer().pause("设置已更改");save();});
            button("取料与工具",1,2,5,()->client.setScreen(new InventoryScreen(this,controller)));
        }else{
            int w=180,right=left+w+18,rw=innerWidth-w-18;
            search=fieldAt("搜索",query,left,40,w,100);search.setChangedListener(v->{query=v;rows();});
            if(list==null)list=new OverlayList(left,w,Math.max(40,bodyBottom-bodyTop-88),id->{selected=id;recording=false;refresh();});rows();addBody(list,88);
            chord=fieldAt(InputBindings.label(selected),drafts.getOrDefault(selected,s.keys.get(selected)),right,40,rw,120);chord.setChangedListener(v->drafts.put(selected,v));chord.setEditable(!recording);
            buttonAt(recording?(selected.equals("wheel")?"按下按键…":"按下组合键…"):"录入按键",right,92,rw,()->{recording=true;pressed.clear();if(Screen.hasControlDown())pressed.add("CTRL");if(Screen.hasAltDown())pressed.add("ALT");if(Screen.hasShiftDown())pressed.add("SHIFT");setFocused(null);refresh();},true,recording);
            int half=(rw-8)/2;buttonAt("保存",right,124,half,()->bind(chord.getText()),!recording,true);buttonAt("清除",right+half+8,124,half,()->bind(""),!recording,false);
            cancelRecord=buttonAt(recording?"取消":"恢复默认",right,156,rw,()->{if(recording){recording=false;refresh();}else bind(new InteractionOptions().keys.getOrDefault(selected,""));},true,false);
            String current=InputBindings.normalize(s.keys.get(selected));
            if(!current.isEmpty()&&!current.contains("+")){int code=InputBindings.code(current);if(code>=0){
                boolean sharedList=selected.equals("wheel")&&client.options.keyPlayerList.matchesKey(code,-1);
                var overlaps=java.util.Arrays.stream(client.options.keysAll).filter(k->k.matchesKey(code,-1)&&(!sharedList||k!=client.options.keyPlayerList)).map(k->new net.minecraft.text.TranslatableText(k.getTranslationKey()).getString()).distinct().toList();
                if(sharedList)caption("按住时同时显示轮盘与玩家列表",right,192,rw);
                if(!overlaps.isEmpty())caption("游戏按键重叠："+String.join("、",overlaps),right,sharedList?214:192,rw);
            }}

        }
    }
    @Override protected void updateMenu(){if(recording&&!client.isWindowFocused()){recording=false;pressed.clear();refresh();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(!recording)return super.keyPressed(key,scan,modifiers);if(key==GLFW.GLFW_KEY_ESCAPE){recording=false;refresh();return true;}controller.action(()->{pressed.add(InputBindings.name(key));if(pressed.size()>4)throw new IllegalArgumentException("最多四键组合");});return true;}
    @Override public boolean keyReleased(int key,int scan,int modifiers){if(!recording)return super.keyReleased(key,scan,modifiers);if(!pressed.isEmpty()){recording=false;controller.action(()->bind(String.join("+",pressed)));refresh();}return true;}
    @Override public boolean mouseClicked(double x,double y,int button){if(!recording||hits(cancelRecord,x,y))return super.mouseClicked(x,y,button);if(button==0)for(var tab:tabs)if(hits(tab,x,y)){recording=false;pressed.clear();return super.mouseClicked(x,y,button);}if(footerHit(x,y)){recording=false;return super.mouseClicked(x,y,button);}if(button>=0&&button<8){pressed.add("MOUSE"+(button+1));recording=false;controller.action(()->bind(String.join("+",pressed)));refresh();}return true;}
    @Override public boolean charTyped(char chr,int modifiers){return recording||super.charTyped(chr,modifiers);}
}
