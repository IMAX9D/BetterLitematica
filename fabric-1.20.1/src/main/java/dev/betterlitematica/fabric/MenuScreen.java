package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import dev.betterlitematica.core.UiViewport;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.*;

/** Shared responsive frame. Scrolling only moves the body; navigation and status stay in place. */
abstract class MenuScreen extends Screen {
    protected final Screen parent;protected final ProjectionController controller;
    protected int left,bodyTop,bodyBottom,innerWidth;private int panelTop,panelBottom;
    private final String description;private final boolean wide;private final long sessionEpoch;
    private record Item(ClickableWidget widget,int y){}
    private record Caption(String text,int x,int y,int width){}
    private final List<Item> body=new ArrayList<>();private final List<ClickableWidget> footer=new ArrayList<>();
    private final List<ClickableWidget> navigation=new ArrayList<>();
    private final Map<ClickableWidget,String> hints=new IdentityHashMap<>();
    private final List<Caption> captions=new ArrayList<>();private int contentHeight,scroll;private boolean building;
    MenuScreen(String title,String description,Screen parent,ProjectionController controller,boolean wide){super(Text.literal(title));this.description=description;this.parent=parent;this.controller=controller;this.wide=wide;sessionEpoch=controller.sessionEpoch();}
    @Override protected final void init(){
        Item previousFocus=body.stream().filter(i->i.widget==getFocused()&&i.widget instanceof OverlayTextField).findFirst().orElse(null);
        int cursor=previousFocus==null?0:((OverlayTextField)previousFocus.widget).getCursor(),anchor=previousFocus==null?0:((OverlayTextField)previousFocus.widget).selectionAnchor();
        setFocused(null);
        width=UiViewport.WIDTH;height=UiViewport.HEIGHT;
        building=true;clearChildren();body.clear();footer.clear();navigation.clear();captions.clear();hints.clear();contentHeight=0;
        innerWidth=Math.max(160,Math.min(width-48,wide?680:440));left=(width-innerWidth)/2;
        int panelHeight=Math.min(height-20,preferredHeight());panelTop=(height-panelHeight)/2;panelBottom=panelTop+panelHeight;
        bodyTop=panelTop+(description.isEmpty()?32:52);bodyBottom=panelBottom-56;
        buildMenu();fixed(backLabel(),innerWidth-76,76,this::close);building=false;position();
        if(previousFocus!=null)for(var item:body)if(item.y==previousFocus.y&&item.widget.getX()==previousFocus.widget.getX()&&item.widget instanceof OverlayTextField field&&field.getMessage().equals(previousFocus.widget.getMessage())){field.setSelectionStart(Math.min(cursor,field.getText().length()));field.setSelectionEnd(Math.min(anchor,field.getText().length()));setFocused(field);break;}
    }
    protected abstract void buildMenu();
    protected String backLabel(){return parent==null?"返回游戏":"返回";}
    protected int preferredHeight(){return wide?560:430;}
    protected final void refresh(){init();}
    protected String heading(){return title.getString();}
    protected String statusLine(){return controller.menuStatus();}
    protected void runAction(Runnable action){controller.action(action);}
    protected String displayedStatus(){return controller.actionError().isEmpty()?statusLine():controller.actionError();}
    protected int statusColor(){return controller.actionError().isEmpty()?UiTheme.MUTED:UiTheme.ERROR;}
    protected void updateMenu(){}
    /** Optional isolated control preview: no menu panel, captions or background dimming. */
    protected ClickableWidget previewControl(){return null;}
    protected final void hint(ClickableWidget control,String value){hints.put(control,value);}
    protected final int cellWidth(int columns){return (innerWidth-(columns-1)*8)/columns;}
    protected final int cellX(int column,int columns){return left+column*(cellWidth(columns)+8);}
    protected final void label(String text,int row){caption(text,left,row*26,innerWidth);}
    protected final void caption(String text,int x,int y,int w){captions.add(new Caption(text,x,y,w));contentHeight=Math.max(contentHeight,y+14);}
    protected final ButtonWidget button(String text,int column,int columns,int row,Runnable action){return button(text,column,columns,row,action,true,false);}
    protected final ButtonWidget button(String text,int column,int columns,int row,Runnable action,boolean enabled,boolean primary){
        return buttonAt(text,cellX(column,columns),row*26,cellWidth(columns),action,enabled,primary);
    }
    /** x is a canvas coordinate; y is relative to the scrollable body. */
    protected final ButtonWidget buttonAt(String text,int x,int y,int w,Runnable action,boolean enabled,boolean primary){
        var button=new MenuButton(x,0,w,text,()->runAction(action),primary);
        button.active=enabled;addBody(button,y);return button;
    }
    protected final TextFieldWidget field(String label,String value,int column,int columns,int row,int max){
        return fieldAt(label,value,cellX(column,columns),row*26,cellWidth(columns),max);
    }
    protected final TextFieldWidget fieldAt(String label,String value,int x,int y,int w,int max){
        caption(label,x,y,w);
        var field=new OverlayTextField(x+6,0,w-12,label);field.setMaxLength(max);field.setText(value);addBody(field,y+14);return field;
    }
    protected final void addBody(ClickableWidget widget,int y){body.add(new Item(widget,y));contentHeight=Math.max(contentHeight,y+widget.getHeight());addDrawableChild(widget);if(!building)position();}
    protected final void clearRows(int row){int y=row*26;body.removeIf(item->{if(item.y>=y){remove(item.widget);return true;}return false;});captions.removeIf(c->c.y>=y);contentHeight=0;for(var item:body)contentHeight=Math.max(contentHeight,item.y+item.widget.getHeight());for(var caption:captions)contentHeight=Math.max(contentHeight,caption.y+14);}
    protected final int listRows(int start){return Math.max(1,(bodyBottom-bodyTop-start*26)/26);}
    protected final void pager(int page,int pages,Runnable previous,Runnable next){
        for(var widget:navigation){remove(widget);footer.remove(widget);}navigation.clear();
        if(pages<=1)return;
        var prev=fixed("上一页",0,60,previous);prev.active=page>0;navigation.add(prev);
        var forward=fixed("下一页",68,60,next);forward.active=page+1<pages;navigation.add(forward);
        var count=fixed((page+1)+" / "+Math.max(1,pages),136,54,()->{});count.active=false;navigation.add(count);
        if(!building)position();
    }
    protected final ButtonWidget fixed(String text,int x,int w,Runnable action){var b=new MenuButton(left+x,panelBottom-41,w,text,()->runAction(action),false);footer.add(b);addDrawableChild(b);return b;}
    protected final <T extends ClickableWidget> T fixedControl(T widget,int x){widget.setX(left+x);widget.setY(panelBottom-41);footer.add(widget);addDrawableChild(widget);return widget;}
    private int maxScroll(){return Math.max(0,contentHeight-Math.max(20,bodyBottom-bodyTop));}
    private void position(){scroll=Math.max(0,Math.min(scroll,maxScroll()));for(var item:body){item.widget.setY(bodyTop+item.y-scroll);item.widget.visible=item.widget.getY()>=bodyTop&&item.widget.getY()+item.widget.getHeight()<=bodyBottom;}}
    private UiViewport viewport(){var window=client.getWindow();return UiViewport.fit(Math.max(1,window.getFramebufferWidth()),Math.max(1,window.getFramebufferHeight()),Math.max(1,window.getScaledWidth()),Math.max(1,window.getScaledHeight()),window.getScaleFactor());}
    protected final boolean hits(ClickableWidget widget,double x,double y){var v=viewport();return widget!=null&&widget.isMouseOver(v.inputX(x),v.inputY(y));}
    protected final boolean controlHit(double x,double y){return footerHit(x,y)||body.stream().anyMatch(i->hits(i.widget,x,y));}
    protected final boolean footerHit(double x,double y){return footer.stream().anyMatch(w->hits(w,x,y));}
    @Override public boolean mouseClicked(double x,double y,int button){var v=viewport();return super.mouseClicked(v.inputX(x),v.inputY(y),button);}
    @Override public boolean mouseReleased(double x,double y,int button){var v=viewport();return super.mouseReleased(v.inputX(x),v.inputY(y),button);}
    @Override public void mouseMoved(double x,double y){var v=viewport();super.mouseMoved(v.inputX(x),v.inputY(y));}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){var v=viewport();return super.mouseDragged(v.inputX(x),v.inputY(y),button,v.deltaX(dx),v.deltaY(dy));}
    @Override public final boolean mouseScrolled(double x,double y,double amount){var v=viewport();x=v.inputX(x);y=v.inputY(y);if(x>=left&&x<=left+innerWidth&&y>=bodyTop&&y<bodyBottom&&maxScroll()>0){scroll-=Math.round(amount*26);position();return true;}return super.mouseScrolled(x,y,amount);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused() instanceof OverlayList list&&list.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof VerificationList results&&results.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof MaterialGrid grid&&grid.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof BrowserGrid grid&&grid.keyPressed(key,scan,modifiers))return true;if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN||key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP){scroll+=(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN?1:-1)*Math.max(26,bodyBottom-bodyTop-26);position();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void tick(){if(sessionEpoch!=controller.sessionEpoch()){client.setScreen(null);return;}if(getFocused()!=null&&!children().contains(getFocused()))setFocused(null);for(var item:List.copyOf(body))if(item.widget instanceof TextFieldWidget field)field.tick();updateMenu();}
    @Override public final void render(DrawContext ctx,int mouseX,int mouseY,float delta){
        var v=viewport();var window=client.getWindow();
        var preview=previewControl();
        if(preview==null){ctx.fill(0,0,window.getScaledWidth(),window.getScaledHeight(),UiTheme.BACKDROP);ctx.draw();}
        int x=(int)Math.floor(v.localPixelX(client.mouse.getX()*v.pixelWidth()/Math.max(1,window.getWidth())));
        int y=(int)Math.floor(v.localPixelY(client.mouse.getY()*v.pixelHeight()/Math.max(1,window.getHeight())));
        var ui=IndependentUi.INSTANCE;
        if(!ui.begin(ctx,v)){
            // A geometric progress indicator during the one-time background font load; no native glyph fallback.
            int cx=window.getScaledWidth()/2,cy=window.getScaledHeight()/2;ctx.fill(cx-30,cy-2,cx+30,cy+2,UiTheme.ACCENT);return;
        }
        try{if(preview!=null)preview.render(ctx,x,y,delta);else renderCanvas(ctx,x,y,delta,ui);}finally{ui.end();}
    }
    private void renderCanvas(DrawContext ctx,int mouseX,int mouseY,float delta,IndependentUi ui){
        ui.rect(left-15,panelTop+4,left+innerWidth+15,panelBottom+5,0x32000000);
        ui.rect(left-12,panelTop,left+innerWidth+12,panelBottom,UiTheme.PANEL);
        ui.frame(left-12,panelTop,left+innerWidth+12,panelBottom,UiTheme.DIVIDER);
        ui.title(heading(),left,panelTop+8,innerWidth,UiTheme.TEXT);
        if(!description.isEmpty())ui.text(description,left,panelTop+28,innerWidth,UiTheme.SECONDARY);
        ui.rect(left,bodyTop-8,left+innerWidth,bodyTop-7,UiTheme.DIVIDER);
        ui.clip(left-1,bodyTop,left+innerWidth+1,bodyBottom);
        try{
            for(var caption:captions)ui.text(caption.text,caption.x,bodyTop+caption.y-scroll,caption.width,UiTheme.SECONDARY);
            for(var item:body)if(item.widget.visible){
                if(item.widget instanceof TextFieldWidget){int x=item.widget.getX(),y=item.widget.getY();ui.rect(x-6,y-3,x+item.widget.getWidth()+6,y+item.widget.getHeight()+3,UiTheme.INPUT);ui.rect(x-6,y+item.widget.getHeight()+2,x+item.widget.getWidth()+6,y+item.widget.getHeight()+3,item.widget.isFocused()?UiTheme.ACCENT:UiTheme.BORDER);}
                item.widget.render(ctx,mouseX,mouseY,delta);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){int track=bodyBottom-bodyTop,thumb=Math.max(16,track*track/Math.max(1,contentHeight));int y=bodyTop+(track-thumb)*scroll/Math.max(1,maxScroll());ui.rect(left+innerWidth+5,bodyTop,left+innerWidth+7,bodyBottom,UiTheme.TRACK);ui.rect(left+innerWidth+5,y,left+innerWidth+7,y+thumb,UiTheme.ACCENT);}
        ui.rect(left,panelBottom-49,left+innerWidth,panelBottom-48,UiTheme.DIVIDER);for(var widget:footer)widget.render(ctx,mouseX,mouseY,delta);
        ui.text(displayedStatus(),left,panelBottom-16,innerWidth,statusColor());
        for(var entry:hints.entrySet())if(entry.getKey().visible&&entry.getKey().isMouseOver(mouseX,mouseY)){
            String[] lines=entry.getValue().split("\n");double w=Arrays.stream(lines).mapToDouble(ui::measure).max().orElse(0)+16,h=lines.length*(ui.lineHeight()+2)+10;
            double x=Math.max(8,Math.min(width-w-8,entry.getKey().getX())),y=Math.max(8,entry.getKey().getY()-h-4);ui.rect(x,y,x+w,y+h,UiTheme.TOOLTIP);for(int i=0;i<lines.length;i++)ui.text(lines[i],x+6,y+5+i*(ui.lineHeight()+2),w-12,UiTheme.TEXT);return;
        }
        for(var item:body)if(item.widget.visible&&item.widget instanceof MenuButton&&item.widget.isMouseOver(mouseX,mouseY)&&ui.measure(item.widget.getMessage().getString())>item.widget.getWidth()-12){
            String text=item.widget.getMessage().getString();List<String> lines=new ArrayList<>();
            while(!text.isEmpty()&&lines.size()<8){String line=ui.trim(text,320);if(line.endsWith("…"))line=line.substring(0,line.length()-1);if(line.isEmpty())break;lines.add(line);text=text.substring(line.length());}
            double step=ui.lineHeight()+2,h=lines.size()*step+12;int x=Math.min(width-336,Math.max(8,mouseX+12)),y=(int)Math.max(8,Math.min(height-h-8,mouseY+16));
            ui.rect(x,y,x+328,y+h,UiTheme.TOOLTIP);double lineY=y+6;for(String line:lines){ui.text(line,x+5,lineY,320,UiTheme.TEXT);lineY+=step;}break;
        }
    }
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
    private static final class MenuButton extends ButtonWidget {
        private final boolean primary;
        MenuButton(int x,int y,int width,String text,Runnable action,boolean primary){super(x,y,width,20,Text.literal(text),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.primary=primary;}
        @Override public void renderButton(DrawContext ctx,int mouseX,int mouseY,float delta){
            boolean focus=isHovered()||isFocused();int fill=!active?UiTheme.DISABLED:focus?UiTheme.HOVER:primary?UiTheme.SELECTED:UiTheme.SURFACE;
            var ui=IndependentUi.INSTANCE;
            ui.rect(getX(),getY(),getX()+width,getY()+height,fill);
            ui.frame(getX(),getY(),getX()+width,getY()+height,focus&&active?UiTheme.FOCUS:UiTheme.DIVIDER);
            if(primary&&active)ui.rect(getX(),getY()+4,getX()+1,getY()+height-4,UiTheme.ACCENT);
            ui.centered(getMessage().getString(),getX(),getY(),width,height,!active?UiTheme.DISABLED_TEXT:primary?UiTheme.FOCUS:UiTheme.TEXT);
        }
    }
}
