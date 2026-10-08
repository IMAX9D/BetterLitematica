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
    private final LinkedHashMap<String,UiMotion> buttonMotions=new LinkedHashMap<>(64,.75f,true);
    private final List<Caption> captions=new ArrayList<>();private int contentHeight,scroll;private boolean building;
    private static java.lang.ref.WeakReference<MenuScreen> departed=new java.lang.ref.WeakReference<>(null);
    private static long departureTime;
    private final UiWindowMotion motion=new UiWindowMotion();
    private boolean presented;
    private double animatedX,animatedY;
    private BlueprintPreviewPanel previewCapture;
    private ClickableWidget pendingFocus;
    private ButtonWidget back;
    private ClickableWidget hintTarget;private long hintSince;
    private static final long HINT_DELAY=350_000_000L;
    MenuScreen(String title,String description,Screen parent,ProjectionController controller,boolean wide){super(Text.literal(title));this.description=description;this.parent=parent;this.controller=controller;this.wide=wide;sessionEpoch=controller.sessionEpoch();}
    @Override protected final void init(){
        cancelPreviewCapture();
        if(!presented){var previous=departed.get();motion.enter(previous!=null&&previous.previewControl()==null&&System.nanoTime()-departureTime<500_000_000L,previous!=null&&previous.parent==this);presented=true;}
        Item previousFocus=body.stream().filter(i->i.widget==getFocused()&&i.widget instanceof OverlayTextField).findFirst().orElse(null);
        int cursor=previousFocus==null?0:((OverlayTextField)previousFocus.widget).getCursor(),anchor=previousFocus==null?0:((OverlayTextField)previousFocus.widget).selectionAnchor();
        setFocused(null);
        width=UiViewport.WIDTH;height=UiViewport.HEIGHT;
        building=true;clearChildren();body.clear();footer.clear();navigation.clear();captions.clear();hints.clear();fieldMotion.clear();contentHeight=0;
        innerWidth=Math.max(160,Math.min(width-48,wide?680:440));left=(width-innerWidth)/2;
        int panelHeight=Math.min(height-20,preferredHeight());panelTop=(height-panelHeight)/2;panelBottom=panelTop+panelHeight;
        bodyTop=panelTop+(description.isEmpty()?40:58);bodyBottom=panelBottom-56;
        buildMenu();back=fixed(backLabel(),innerWidth-76,76,this::close);building=false;position();
        if(previousFocus!=null)for(var item:body)if(item.y==previousFocus.y&&item.widget.getX()==previousFocus.widget.getX()&&item.widget instanceof OverlayTextField field&&field.getMessage().equals(previousFocus.widget.getMessage())){field.setSelectionStart(Math.min(cursor,field.getText().length()));field.setSelectionEnd(Math.min(anchor,field.getText().length()));setFocused(field);break;}
    }
    protected abstract void buildMenu();
    protected String backLabel(){return parent==null?"返回游戏":"返回";}
    protected int preferredHeight(){return wide?560:430;}
    protected final void refresh(){init();}
    protected String heading(){return title.getString();}
    protected String statusLine(){return "";}
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
        var button=new MenuButton(x,0,w,text,()->runAction(action),primary,buttonMotion("body:"+x+":"+y+":"+w));
        button.active=enabled;addBody(button,y);return button;
    }
    /** Square icon control; the name doubles as its hover hint. */
    protected final GlyphButton iconAt(dev.betterlitematica.runtime.UiGlyphArt.Kind glyph,String name,int x,int y,int size,Runnable action,boolean enabled,Look look,int tint){
        var button=new GlyphButton(x,size,glyph,name,look,tint,buttonMotion("icon:"+x+":"+y+":"+glyph),()->runAction(action));
        button.active=enabled;addBody(button,y);hint(button,name);return button;
    }
    protected final MenuTile tileAt(dev.betterlitematica.runtime.UiGlyphArt.Kind glyph,String label,int x,int y,int w,int h,Runnable action,boolean emphasis){
        var tile=new MenuTile(x,w,h,glyph,label,emphasis,buttonMotion("tile:"+x+":"+y+":"+w),()->runAction(action));addBody(tile,y);return tile;
    }
    protected final RowButton rowAt(String name,int x,int y,int w,boolean selected,boolean live,Runnable action){
        var row=new RowButton(x,w,name,selected,live,buttonMotion("row:"+x+":"+y+":"+w),()->runAction(action));addBody(row,y);return row;
    }
    /** Underlined tab; a hairline bridges the gaps so a row of tabs reads as one strip. */
    protected final ButtonWidget tabAt(String text,int x,int y,int w,Runnable action,boolean selected){
        var button=new MenuButton(x,0,w,text,()->runAction(action),selected?Look.TAB_SELECTED:Look.TAB,buttonMotion("tab:"+x+":"+y+":"+w));addBody(button,y);return button;
    }
    protected final ButtonWidget tab(String text,int column,int columns,int row,Runnable action,boolean selected){return tabAt(text,cellX(column,columns),row*26,cellWidth(columns),action,selected);}
    protected final ButtonWidget ghostAt(String text,int x,int y,int w,Runnable action,boolean enabled){
        var button=new MenuButton(x,0,w,text,()->runAction(action),Look.GHOST,buttonMotion("ghost:"+x+":"+y+":"+w));button.active=enabled;addBody(button,y);return button;
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
        // The position is read-only text, not a greyed-out control.
        navigation.add(fixedControl(new OverlayLabel(0,54,(page+1)+" / "+Math.max(1,pages)),142));
        if(!building)position();
    }
    private UiMotion buttonMotion(String key){var value=buttonMotions.get(key);if(value==null){if(buttonMotions.size()>=256)buttonMotions.remove(buttonMotions.keySet().iterator().next());value=new UiMotion();buttonMotions.put(key,value);}return value;}
    protected final ButtonWidget fixed(String text,int x,int w,Runnable action){return footerButton(text,x,w,action,Look.STANDARD);}
    /** The page's one committing action (load, save); drawn solid so it is never mistaken for Back. */
    protected final ButtonWidget fixedAction(String text,int x,int w,Runnable action){return footerButton(text,x,w,action,Look.ACCENT);}
    private ButtonWidget footerButton(String text,int x,int w,Runnable action,Look look){var b=new MenuButton(left+x,panelBottom-41,w,text,()->runAction(action),look,buttonMotion("footer:"+x+":"+w));footer.add(b);addDrawableChild(b);return b;}
    protected final <T extends ClickableWidget> T fixedControl(T widget,int x){widget.setX(left+x);widget.setY(panelBottom-41);footer.add(widget);addDrawableChild(widget);return widget;}
    protected int scrollLeft(){return left;}
    protected int scrollRight(){return left+innerWidth;}
    protected int scrollTop(){return bodyTop;}
    protected boolean scrolls(int x,int y){return true;}
    private int maxScroll(){return Math.max(0,contentHeight-Math.max(20,bodyBottom-bodyTop));}
    private void position(){scroll=Math.max(0,Math.min(scroll,maxScroll()));for(var item:body){boolean moving=scrolls(item.widget.getX(),item.y);item.widget.setY(bodyTop+item.y-(moving?scroll:0));item.widget.visible=item.widget.getY()>=(moving?scrollTop():bodyTop)&&item.widget.getY()+item.widget.getHeight()<=bodyBottom;}}
    /** Validation must reveal the field, including fields below the current scroll position. */
    protected final void focusControl(ClickableWidget widget){
        for(var item:body)if(item.widget==widget&&scrolls(widget.getX(),item.y)){
            int top=bodyTop+item.y-scroll,bottom=top+widget.getHeight();
            if(top<scrollTop())scroll-=scrollTop()-top;
            else if(bottom>bodyBottom)scroll+=bottom-bodyBottom;
            position();break;
        }
        setFocused(widget);pendingFocus=widget;
    }
    private UiViewport viewport(){var window=client.getWindow();return UiViewport.fit(Math.max(1,window.getFramebufferWidth()),Math.max(1,window.getFramebufferHeight()),Math.max(1,window.getScaledWidth()),Math.max(1,window.getScaledHeight()),window.getScaleFactor());}
    private double inputX(UiViewport v,double x){return v.inputX(x)-(previewControl()==null?animatedX:0);}
    private double inputY(UiViewport v,double y){return v.inputY(y)-(previewControl()==null?animatedY:0);}
    protected final boolean hits(ClickableWidget widget,double x,double y){var v=viewport();return widget!=null&&widget.isMouseOver(inputX(v,x),inputY(v,y));}
    protected final boolean controlHit(double x,double y){return footerHit(x,y)||body.stream().anyMatch(i->hits(i.widget,x,y));}
    protected final boolean footerHit(double x,double y){return footer.stream().anyMatch(w->hits(w,x,y));}
    private void cancelPreviewCapture(){if(previewCapture!=null){previewCapture.cancelCapture();previewCapture=null;setDragging(false);}}
    private void checkPreviewCapture(){
        if(previewCapture==null)return;previewCapture.pollCapture();
        if(!previewCapture.capturing()||!children().contains(previewCapture)||previewControl()!=null)cancelPreviewCapture();
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        var v=viewport();x=inputX(v,x);y=inputY(v,y);checkPreviewCapture();
        if(previewCapture!=null)return true;
        if(previewControl()==null)for(var item:body)if(item.widget instanceof BlueprintPreviewPanel panel&&panel.mouseClicked(x,y,button)){
            previewCapture=panel;setFocused(panel);setDragging(button==0);return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        // Release goes to its owner even beyond the preview or menu; no hovered-child lookup.
        if(previewCapture!=null){previewCapture.releaseDrag(button);if(!previewCapture.capturing()){previewCapture=null;setDragging(false);}return true;}
        var v=viewport();return super.mouseReleased(inputX(v,x),inputY(v,y),button);
    }
    @Override public void mouseMoved(double x,double y){var v=viewport();super.mouseMoved(inputX(v,x),inputY(v,y));}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        var v=viewport();checkPreviewCapture();
        if(previewCapture!=null){previewCapture.mouseDragged(inputX(v,x),inputY(v,y),button,v.deltaX(dx),v.deltaY(dy));return true;}
        return super.mouseDragged(inputX(v,x),inputY(v,y),button,v.deltaX(dx),v.deltaY(dy));
    }
    @Override public final boolean mouseScrolled(double x,double y,double horizontal,double amount){
        var v=viewport();x=inputX(v,x);y=inputY(v,y);
        if(previewControl()==null)for(var item:body)if(item.widget instanceof BlueprintPreviewPanel panel&&panel.mouseScrolled(x,y,horizontal,amount))return true;
        if(previewCapture!=null)return true;
        if(x>=scrollLeft()&&x<=scrollRight()+8&&y>=scrollTop()&&y<bodyBottom&&maxScroll()>0){scroll-=Math.round(amount*26);position();return true;}return super.mouseScrolled(x,y,horizontal,amount);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused() instanceof OverlayList list&&list.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof VerificationList results&&results.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof MaterialGrid grid&&grid.keyPressed(key,scan,modifiers))return true;if(getFocused() instanceof BrowserGrid grid&&grid.keyPressed(key,scan,modifiers))return true;if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN||key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP){scroll+=(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN?1:-1)*Math.max(26,bodyBottom-bodyTop-26);position();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void tick(){
        if(sessionEpoch!=controller.sessionEpoch()){client.setScreen(null);return;}checkPreviewCapture();if(getFocused()!=null&&!children().contains(getFocused()))setFocused(null);updateMenu();
        // Screen.mouseClicked focuses the button after its callback; validation owns the next focus.
        if(pendingFocus!=null){var requested=pendingFocus;pendingFocus=null;if(children().contains(requested))setFocused(requested);}
        // Back can read "放弃" once a page holds a draft; keep its wording current without rebuilding.
        if(back!=null){String label=backLabel();if(!back.getMessage().getString().equals(label))back.setMessage(Text.literal(label));}
    }
    /** Full-window dimming is drawn by this canvas, independent of logical Screen dimensions. */
    @Override public void renderBackground(DrawContext ctx,int mouseX,int mouseY,float delta){
        if(previewControl()==null)applyBlur();
    }
    @Override public final void render(DrawContext ctx,int mouseX,int mouseY,float delta){
        ctx.getMatrices().push();
        try{
        ctx.getMatrices().translate(0,0,IndependentUi.MENU_DEPTH);
        checkPreviewCapture();
        var v=viewport();var window=client.getWindow();
        var preview=previewControl();
        var frame=motion.frame();animatedX=preview==null?frame.x():0;animatedY=preview==null?frame.y():0;
        if(preview==null){ctx.fill(0,0,window.getScaledWidth(),window.getScaledHeight(),UiMotion.alpha(UiTheme.BACKDROP,frame.alpha()));ctx.draw();}
        int x=(int)Math.floor(v.localPixelX(client.mouse.getX()*v.pixelWidth()/Math.max(1,window.getWidth()))-animatedX);
        int y=(int)Math.floor(v.localPixelY(client.mouse.getY()*v.pixelHeight()/Math.max(1,window.getHeight()))-animatedY);
        var ui=IndependentUi.INSTANCE;
        if(!ui.beginMenu(ctx,v)){
            // A geometric progress indicator during the one-time background font load; no native glyph fallback.
            int cx=window.getScaledWidth()/2,cy=window.getScaledHeight()/2;ctx.fill(cx-24,cy,cx+24,cy+1,UiMotion.alpha(UiTheme.ACCENT,.5));int p=(int)((System.nanoTime()/8_000_000L)%48);ctx.fill(cx-24+Math.max(0,p-12),cy,cx-24+p,cy+1,UiTheme.ACCENT);return;
        }
        try{
            if(preview!=null){
                if(preview instanceof OverlayOpacitySlider){double l=preview.getX()-8,t=preview.getY()-6,r=preview.getX()+preview.getWidth()+8,b=preview.getY()+preview.getHeight()+6;ui.shadow(l,t,r,b,UiTheme.CARD_RADIUS);ui.roundRect(l,t,r,b,UiTheme.CARD_RADIUS,UiTheme.OVERLAY_PANEL);ui.roundFrame(l,t,r,b,UiTheme.CARD_RADIUS,UiTheme.BORDER);}
                preview.render(ctx,x,y,delta);
            }else{ui.effect(animatedX,animatedY,frame.alpha());renderCanvas(ctx,x,y,delta,ui);}
        }finally{ui.end();}
        }finally{ctx.getMatrices().pop();}
    }
    private String crumb(){
        if(!(parent instanceof MenuScreen menu))return "";
        String name=menu.heading();return name.equals(heading())?"":name;
    }
    private void renderCanvas(DrawContext ctx,int mouseX,int mouseY,float delta,IndependentUi ui){
        double l=left-14,r=left+innerWidth+14,radius=UiTheme.PANEL_RADIUS,footerTop=panelBottom-49;
        ui.elevation(l,panelTop,r,panelBottom,radius);
        ui.roundRect(l,panelTop,r,panelBottom,radius,UiTheme.PANEL);
        // Footer band: same silhouette, square top edge, one tone deeper.
        ui.roundRect(l,footerTop,r,panelBottom,radius,UiTheme.FOOTER);ui.rect(l,footerTop,r,footerTop+radius,UiTheme.FOOTER);
        ui.rect(l,footerTop,r,footerTop+ui.pixel(),UiTheme.DIVIDER);
        ui.roundFrame(l,panelTop,r,panelBottom,radius,UiTheme.BORDER);
        ui.sheen(l,panelTop+ui.pixel(),r,radius);
        // Header: brand mark, breadcrumb and title on one optical baseline.
        double headerMid=panelTop+(bodyTop-8-panelTop)/2d,titleY=headerMid-ui.titleLineHeight()/2-.5;
        ui.glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind.BRAND,left,headerMid-7,14,UiTheme.ACCENT);
        double tx=left+21;String crumb=crumb();
        if(!crumb.isEmpty()){
            double cw=Math.min(innerWidth*.35,ui.measure(crumb)+1),ty=headerMid-ui.lineHeight()/2-.5;ui.text(crumb,tx,ty,cw,UiTheme.MUTED);tx+=cw+6;
            ui.text("/",tx,ty,10,UiMotion.alpha(UiTheme.MUTED,.6));tx+=ui.measure("/")+6;
        }
        ui.title(heading(),tx,titleY,left+innerWidth-tx,UiTheme.TEXT);
        if(!description.isEmpty())ui.text(description,left,panelTop+34,innerWidth,UiTheme.SECONDARY);
        ui.rect(l,bodyTop-8,r,bodyTop-8+ui.pixel(),UiTheme.DIVIDER);
        ui.clip(left-4,bodyTop,left+innerWidth+4,bodyBottom);
        try{
            for(var caption:captions)ui.text(caption.text,caption.x,bodyTop+caption.y-(scrolls(caption.x,caption.y)?scroll:0),caption.width,UiTheme.MUTED);
            for(var item:body)if(item.widget.visible){
                if(item.widget instanceof TextFieldWidget field)paintField(ui,field,mouseX,mouseY);
                item.widget.render(ctx,mouseX,mouseY,delta);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){int track=bodyBottom-scrollTop(),thumb=Math.max(20,track*track/Math.max(1,contentHeight-(scrollTop()-bodyTop)));int y=scrollTop()+(track-thumb)*scroll/Math.max(1,maxScroll());ui.roundRect(scrollRight()+6,scrollTop(),scrollRight()+8,bodyBottom,1,UiMotion.alpha(UiTheme.TRACK,.8));ui.roundRect(scrollRight()+6,y,scrollRight()+8,y+thumb,1,UiTheme.THUMB);}
        for(var widget:footer)widget.render(ctx,mouseX,mouseY,delta);
        String status=displayedStatus();
        if(!status.isEmpty()){int color=statusColor();double sy=panelBottom-15;ui.roundRect(left,sy+ui.lineHeight()/2-1.5,left+3,sy+ui.lineHeight()/2+1.5,1.5,color==UiTheme.MUTED?UiTheme.ACCENT:color);ui.text(status,left+8,sy,innerWidth-8,color);}
        // Tooltips wait for a short rest on one control so sweeping the pointer across icons stays quiet.
        ClickableWidget resting=null;
        for(var entry:hints.entrySet())if(entry.getKey().visible&&entry.getKey().isMouseOver(mouseX,mouseY)){resting=entry.getKey();break;}
        if(resting==null)for(var item:body)if(item.widget.visible&&item.widget instanceof MenuButton&&item.widget.isMouseOver(mouseX,mouseY)){resting=item.widget;break;}
        long now=System.nanoTime();if(resting!=hintTarget){hintTarget=resting;hintSince=now;}
        if(resting==null||now-hintSince<HINT_DELAY)return;
        for(var entry:hints.entrySet())if(entry.getKey()==resting&&(!(entry.getKey() instanceof BlueprintPreviewPanel panel)||!panel.capturing()&&!panel.recentlyUsed())){
            String[] lines=entry.getValue().split("\n");double w=Arrays.stream(lines).mapToDouble(ui::measure).max().orElse(0)+20,h=lines.length*(ui.lineHeight()+2)+10;
            double x=Math.max(8,Math.min(width-w-8,entry.getKey().getX()+entry.getKey().getWidth()/2d-w/2)),y=entry.getKey().getY()-h-6;if(y<8)y=entry.getKey().getY()+entry.getKey().getHeight()+6;
            tooltip(ui,x,y,w,h);for(int i=0;i<lines.length;i++)ui.text(lines[i],x+9,y+5+i*(ui.lineHeight()+2),w-18,UiTheme.TEXT);return;
        }
        if(hints.containsKey(resting))return;
        for(var item:body)if(item.widget==resting&&ui.measure(item.widget.getMessage().getString())>item.widget.getWidth()-12){
            String text=item.widget.getMessage().getString();List<String> lines=new ArrayList<>();
            while(!text.isEmpty()&&lines.size()<8){String line=ui.trim(text,320);if(line.endsWith("…"))line=line.substring(0,line.length()-1);if(line.isEmpty())break;lines.add(line);text=text.substring(line.length());}
            double step=ui.lineHeight()+2,h=lines.size()*step+12;int x=Math.min(width-336,Math.max(8,mouseX+12)),y=(int)Math.max(8,Math.min(height-h-8,mouseY+16));
            tooltip(ui,x,y,328,h);double lineY=y+6;for(String line:lines){ui.text(line,x+5,lineY,320,UiTheme.TEXT);lineY+=step;}break;
        }
    }
    static void tooltip(IndependentUi ui,double x,double y,double w,double h){ui.shadow(x,y,x+w,y+h,6);ui.roundRect(x,y,x+w,y+h,6,UiTheme.TOOLTIP);ui.roundFrame(x,y,x+w,y+h,6,UiTheme.BORDER);ui.sheen(x,y+ui.pixel(),x+w,6);}
    private final IdentityHashMap<TextFieldWidget,UiMotion> fieldMotion=new IdentityHashMap<>();
    private void paintField(IndependentUi ui,TextFieldWidget field,int mouseX,int mouseY){
        var motion=fieldMotion.computeIfAbsent(field,f->new UiMotion());double focus=motion.hover(field.isFocused());
        boolean over=field.isMouseOver(mouseX,mouseY);
        int x=field.getX(),y=field.getY();double l=x-6,t=y-1,r=x+field.getWidth()+6,b=y+field.getHeight()+1;
        ui.ring(l,t,r,b,UiTheme.BUTTON_RADIUS,focus);
        ui.roundRect(l,t,r,b,UiTheme.BUTTON_RADIUS,UiTheme.INPUT);
        ui.roundFrame(l,t,r,b,UiTheme.BUTTON_RADIUS,UiMotion.mix(over?UiTheme.BORDER_STRONG:UiTheme.BORDER,UiTheme.ACCENT,focus));
    }
    static void paintFieldStatic(IndependentUi ui,TextFieldWidget field){
        int x=field.getX(),y=field.getY();double l=x-6,t=y-1,r=x+field.getWidth()+6,b=y+field.getHeight()+1;
        if(field.isFocused())ui.ring(l,t,r,b,UiTheme.BUTTON_RADIUS,1);
        ui.roundRect(l,t,r,b,UiTheme.BUTTON_RADIUS,UiTheme.INPUT);ui.roundFrame(l,t,r,b,UiTheme.BUTTON_RADIUS,field.isFocused()?UiTheme.ACCENT:UiTheme.BORDER);
    }
    @Override public void close(){client.setScreen(parent);}
    @Override public void removed(){cancelPreviewCapture();presented=false;departed=new java.lang.ref.WeakReference<>(this);departureTime=System.nanoTime();super.removed();}
    @Override public boolean shouldPause(){return false;}
    enum Look { STANDARD, PRIMARY, ACCENT, GHOST, TAB, TAB_SELECTED }
    static final class MenuButton extends ButtonWidget {
        private static final java.util.WeakHashMap<UiMotion,UiMotion> SWITCHES=new java.util.WeakHashMap<>();
        private final Look look;private final UiMotion motion;private final UiMotion switchMotion;
        MenuButton(int x,int y,int width,String text,Runnable action,boolean primary,UiMotion motion){this(x,y,width,text,action,primary?Look.PRIMARY:Look.STANDARD,motion);}
        MenuButton(int x,int y,int width,String text,Runnable action,Look look,UiMotion motion){super(x,y,width,20,Text.literal(text),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.look=look;this.motion=motion;boolean on=text.endsWith("：开")||text.endsWith("：开启")||text.endsWith("：是");switchMotion=SWITCHES.computeIfAbsent(motion,m->new UiMotion(on?1:0));}
        @Override public void renderWidget(DrawContext ctx,int mouseX,int mouseY,float delta){
            boolean hot=isHovered()||isFocused();double hover=motion.hover(active&&hot),press=motion.pressed();
            if(look==Look.TAB||look==Look.TAB_SELECTED){paintTab(hover);return;}
            var ui=IndependentUi.INSTANCE;double y=getY()+press*.5;
            String text=getMessage().getString();int split=text.lastIndexOf('：');
            Boolean state=split<=0||width<110?null:switch(text.substring(split+1)){case "开","开启","是"->Boolean.TRUE;case "关","关闭","否"->Boolean.FALSE;default->null;};
            if(state!=null&&look!=Look.GHOST){
                // Binary settings read as a labelled switch rather than a button that names its own state.
                paint(ui,getX(),getY(),width,height,Look.STANDARD,active,isFocused(),hover,press);
                double sw=24,sh=12,sx=getX()+width-10-sw,sy=y+(height-sh)/2,on=switchMotion.hover(state);
                ui.roundRect(sx,sy,sx+sw,sy+sh,sh/2,!active?UiTheme.DIVIDER:UiMotion.mix(UiTheme.TRACK,UiTheme.ACCENT,on));
                if(!state)ui.roundFrame(sx,sy,sx+sw,sy+sh,sh/2,UiTheme.BORDER_STRONG);
                double k=sh-4,kx=sx+2+(sw-4-k)*on;ui.roundRect(kx,sy+2,kx+k,sy+2+k,k/2,!active?UiTheme.DISABLED_TEXT:UiMotion.mix(UiTheme.MUTED,UiTheme.DARK?UiTheme.ON_ACCENT:UiTheme.INPUT,on));
                ui.text(text.substring(0,split),getX()+12,y+(height-ui.lineHeight())/2-.5,sx-getX()-20,!active?UiTheme.DISABLED_TEXT:UiTheme.TEXT);
                return;
            }
            paint(ui,getX(),getY(),width,height,look,active,isFocused(),hover,press);
            if(split>0&&split<text.length()-1&&width>=110&&look==Look.STANDARD){
                // Cycling choice: name on the left, current value and a chevron on the right.
                String name=text.substring(0,split),value=text.substring(split+1);double ty=y+(height-ui.lineHeight())/2-.5;
                double vw=Math.min(width*.55,ui.measure(value)+1),vx=getX()+width-22-vw;
                ui.glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind.NEXT,getX()+width-18,y+(height-10)/2,10,!active?UiTheme.DISABLED_TEXT:UiMotion.mix(UiTheme.MUTED,UiTheme.ACCENT,hover));
                ui.text(value,vx,ty,vw,!active?UiTheme.DISABLED_TEXT:UiTheme.SECONDARY);
                ui.text(name,getX()+12,ty,vx-getX()-20,!active?UiTheme.DISABLED_TEXT:UiTheme.TEXT);
                return;
            }
            int color=!active?UiTheme.DISABLED_TEXT:look==Look.ACCENT?UiTheme.ON_ACCENT:look==Look.PRIMARY?UiMotion.mix(UiTheme.FOCUS,UiTheme.ACCENT_HOVER,hover*.6):look==Look.GHOST?UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover):UiTheme.TEXT;
            ui.centered(getMessage().getString(),getX(),y,width,height,color);
        }
        private void paintTab(double hover){
            var ui=IndependentUi.INSTANCE;boolean on=look==Look.TAB_SELECTED;double x=getX(),y=getY(),b=y+height+3;
            ui.rect(x-4,b-ui.pixel(),x+width+4,b,UiTheme.DIVIDER);
            if(hover>.01&&!on)ui.roundRect(x,y,x+width,y+height,UiTheme.BUTTON_RADIUS,UiMotion.alpha(UiTheme.HOVER,hover*.8));
            String label=getMessage().getString();double w=Math.min(width-8,IndependentUi.INSTANCE.measure(label)+18);
            if(on)ui.roundRect(x+(width-w)/2,b-2,x+(width+w)/2,b,1,UiTheme.ACCENT);
            if(isFocused())ui.ring(x,y,x+width,y+height,UiTheme.BUTTON_RADIUS,1);
            ui.centered(label,x,y,width,height,!active?UiTheme.DISABLED_TEXT:on?UiTheme.TEXT:UiMotion.mix(UiTheme.MUTED,UiTheme.TEXT,hover));
        }
        /** Shared control surface so every bespoke widget presses, hovers and focuses identically. */
        static void paint(IndependentUi ui,double x,double y,double width,double height,Look look,boolean active,boolean focused,double hover,double press){
            double inset=press*.6,top=y+press*.5,l=x+inset,r=x+width-inset,b=top+height,radius=UiTheme.BUTTON_RADIUS;
            if(!active){ui.roundRect(l,top,r,b,radius,UiTheme.DISABLED);ui.roundFrame(l,top,r,b,radius,UiTheme.DIVIDER);return;}
            int fill,edge;
            switch(look){
                case PRIMARY -> {fill=UiMotion.mix(UiTheme.SELECTED,UiTheme.PRESSED,hover*.7);edge=UiMotion.mix(UiMotion.alpha(UiTheme.ACCENT,.45),UiTheme.ACCENT,hover*.6);}
                case ACCENT -> {fill=UiMotion.mix(UiTheme.ACCENT,UiTheme.ACCENT_HOVER,hover);edge=fill;}
                case GHOST -> {fill=UiMotion.alpha(UiTheme.HOVER,hover);edge=UiMotion.alpha(UiTheme.BORDER,hover);}
                default -> {fill=UiMotion.mix(UiTheme.SURFACE,UiTheme.HOVER,hover);edge=UiMotion.mix(UiTheme.BORDER,UiTheme.BORDER_STRONG,hover);}
            }
            fill=UiMotion.mix(fill,look==Look.ACCENT?UiTheme.FOCUS:look==Look.PRIMARY?UiTheme.PRESSED:UiTheme.HOVER,press*.8);
            if(focused)ui.ring(l,top,r,b,radius,1);
            if(look!=Look.GHOST&&!UiTheme.DARK)ui.contact(l,top,r,b,radius);
            if((fill>>>24)!=0)ui.roundRect(l,top,r,b,radius,fill);
            if((edge>>>24)!=0)ui.roundFrame(l,top,r,b,radius,focused?UiTheme.ACCENT:edge);
            if(look!=Look.GHOST)ui.sheen(l,top+ui.pixel(),r,radius);
        }
        @Override public void onPress(){motion.press();super.onPress();}
    }
}
