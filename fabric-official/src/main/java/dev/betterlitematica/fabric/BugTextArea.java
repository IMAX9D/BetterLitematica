package dev.betterlitematica.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import dev.betterlitematica.fabric.NativeInput;
import java.util.*;
import java.util.function.Consumer;

/** Multiline editing in the same measured system font as the rest of the menu. Enter inserts a line. */
final class BugTextArea extends UiWidget {
    private record Line(int start,int end) {}
    private record Edit(String text,int cursor,int anchor) {}
    private final boolean editable;
    private final int maximum;
    private final Consumer<String> changed;
    private String text="",layoutText="";
    private int cursor,anchor,layoutPixels=-1,layoutWidth=-1;
    private List<Line> lines=List.of(new Line(0,0));
    private final ArrayDeque<Edit> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private double scroll;
    private boolean selecting;

    BugTextArea(int x,int width,int height,String value,boolean editable,int maximum,Consumer<String> changed){
        super(x,0,width,height,Component.literal(editable?"问题描述":"附带内容"));this.editable=editable;this.maximum=maximum;this.changed=changed;setValue(value);
    }
    String getValue(){return text;}
    @Override protected boolean acceptsTextInput(){return visible&&active&&editable&&isFocused();}
    void resizeHeight(int value){height=value;}
    void setValue(String value){text=limited(value);cursor=anchor=Math.min(cursor,text.length());layoutPixels=-1;}
    private String limited(String value){String clean=value.replace("\r\n","\n").replace('\r','\n').replace("\0","");int end=Math.min(clean.length(),maximum);if(end>0&&end<clean.length()&&Character.isHighSurrogate(clean.charAt(end-1)))end--;return clean.substring(0,end);}
    private double step(){return Math.max(14,IndependentUi.INSTANCE.lineHeight()+3);}
    private double maxScroll(){return Math.max(0,lines.size()*step()-(height-16));}
    private void layout(){
        var ui=IndependentUi.INSTANCE;if(!ui.ready())return;
        if(layoutText.equals(text)&&layoutPixels==ui.pixels()&&layoutWidth==width)return;
        var result=new ArrayList<Line>();int start=0;
        while(start<=text.length()){
            int newline=text.indexOf('\n',start),end=newline<0?text.length():newline;
            if(start==end)result.add(new Line(start,end));
            else for(int at=start;at<end;){String rest=text.substring(at,end),fit=ui.fittingText(rest,Math.max(1,width-24));int length=fit.length();if(length==0)length=Character.charCount(rest.codePointAt(0));result.add(new Line(at,at+length));at+=length;}
            if(newline<0)break;start=newline+1;
        }
        lines=List.copyOf(result);layoutText=text;layoutPixels=ui.pixels();layoutWidth=width;scroll=Math.max(0,Math.min(scroll,maxScroll()));
    }
    private int lineAtCursor(){for(int i=0;i<lines.size();i++){Line line=lines.get(i);if(cursor<line.end||cursor==line.end&&(i+1==lines.size()||lines.get(i+1).start>cursor))return i;}return lines.size()-1;}
    private void reveal(){layout();int index=lineAtCursor();double top=index*step(),bottom=top+step();if(top<scroll)scroll=top;else if(bottom>scroll+height-16)scroll=bottom-height+16;scroll=Math.max(0,Math.min(scroll,maxScroll()));}
    private int hit(double x,double y){layout();int index=Math.max(0,Math.min(lines.size()-1,(int)((y-getY()-8+scroll)/step())));Line line=lines.get(index);return line.start+IndependentUi.INSTANCE.hit(text.substring(line.start,line.end),Math.max(0,x-getX()-8));}
    private void move(int position,boolean extend){cursor=Math.max(0,Math.min(position,text.length()));if(!extend)anchor=cursor;reveal();}
    private String selected(){return text.substring(Math.min(cursor,anchor),Math.max(cursor,anchor));}
    private void replace(String inserted){
        if(!editable)return;undo.addLast(new Edit(text,cursor,anchor));while(undo.size()>32)undo.removeFirst();redo.clear();
        int low=Math.min(cursor,anchor),high=Math.max(cursor,anchor);String value=limited(inserted);
        int available=Math.max(0,maximum-text.length()+high-low),end=Math.min(available,value.length());if(end>0&&end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;
        value=value.substring(0,end);text=text.substring(0,low)+value+text.substring(high);cursor=anchor=low+value.length();changed.accept(text);reveal();
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!visible||!active||button!=0||!isMouseOver(x,y))return false;setFocused(true);int at=hit(x,y);move(at,UiEvents.shiftDown());selecting=true;return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!selecting||button!=0)return false;if(y<getY()+8)scroll=Math.max(0,scroll-step());else if(y>getY()+height-8)scroll=Math.min(maxScroll(),scroll+step());cursor=hit(x,y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){selecting=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!visible||!isMouseOver(x,y))return false;layout();scroll=Math.max(0,Math.min(maxScroll(),scroll-amount*step()*3));return true;}
    @Override public boolean charTyped(net.minecraft.client.input.CharacterEvent event){if(!active||!isFocused()||!editable||Character.isISOControl(event.codepoint()))return false;replace(new String(Character.toChars(event.codepoint())));return true;}
    public boolean charTyped(char character,int modifiers){if(!active||!isFocused()||!editable||Character.isISOControl(character))return false;replace(String.valueOf(character));return true;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(!active||!isFocused())return false;layout();boolean shift=UiEvents.shiftDown(),control=UiEvents.controlDown();var clipboard=Minecraft.getInstance().keyboardHandler;
        if(control)switch(key){
            case NativeInput.GLFW_KEY_A -> {anchor=0;cursor=text.length();reveal();return true;}
            case NativeInput.GLFW_KEY_C -> {clipboard.setClipboard(selected());return true;}
            case NativeInput.GLFW_KEY_X -> {clipboard.setClipboard(selected());replace("");return true;}
            case NativeInput.GLFW_KEY_V -> {replace(clipboard.getClipboard());return true;}
            case NativeInput.GLFW_KEY_Z,NativeInput.GLFW_KEY_Y -> {
                if(!editable)return true;var from=key==NativeInput.GLFW_KEY_Y||shift?redo:undo;var to=from==undo?redo:undo;
                if(!from.isEmpty()){to.addLast(new Edit(text,cursor,anchor));Edit edit=from.removeLast();text=edit.text;cursor=edit.cursor;anchor=edit.anchor;changed.accept(text);reveal();}return true;
            }
        }
        switch(key){
            case NativeInput.GLFW_KEY_ENTER,NativeInput.GLFW_KEY_KP_ENTER -> {replace("\n");return true;}
            case NativeInput.GLFW_KEY_BACKSPACE -> {if(cursor==anchor&&cursor>0)anchor=text.offsetByCodePoints(cursor,-1);replace("");return true;}
            case NativeInput.GLFW_KEY_DELETE -> {if(cursor==anchor&&cursor<text.length())anchor=text.offsetByCodePoints(cursor,1);replace("");return true;}
            case NativeInput.GLFW_KEY_LEFT -> {move(!shift&&cursor!=anchor?Math.min(cursor,anchor):cursor>0?text.offsetByCodePoints(cursor,-1):0,shift);return true;}
            case NativeInput.GLFW_KEY_RIGHT -> {move(!shift&&cursor!=anchor?Math.max(cursor,anchor):cursor<text.length()?text.offsetByCodePoints(cursor,1):cursor,shift);return true;}
            case NativeInput.GLFW_KEY_HOME -> {move(control?0:lines.get(lineAtCursor()).start,shift);return true;}
            case NativeInput.GLFW_KEY_END -> {move(control?text.length():lines.get(lineAtCursor()).end,shift);return true;}
            case NativeInput.GLFW_KEY_UP,NativeInput.GLFW_KEY_DOWN,NativeInput.GLFW_KEY_PAGE_UP,NativeInput.GLFW_KEY_PAGE_DOWN -> {
                int index=lineAtCursor(),distance=key==NativeInput.GLFW_KEY_PAGE_UP||key==NativeInput.GLFW_KEY_PAGE_DOWN?Math.max(1,(int)((height-16)/step())):1;
                int next=Math.max(0,Math.min(lines.size()-1,index+(key==NativeInput.GLFW_KEY_UP||key==NativeInput.GLFW_KEY_PAGE_UP?-distance:distance)));
                double x=IndependentUi.INSTANCE.measure(text.substring(lines.get(index).start,cursor));Line line=lines.get(next);move(line.start+IndependentUi.INSTANCE.hit(text.substring(line.start,line.end),x),shift);return true;
            }
            default -> {return false;}
        }
    }
    @Override public void renderWidget(GuiGraphicsExtractor context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;if(!ui.ready())return;layout();double x=getX(),y=getY();
        if(isFocused()&&active)ui.ring(x,y,x+width,y+height,UiTheme.BUTTON_RADIUS,1);
        ui.roundRect(x,y,x+width,y+height,UiTheme.BUTTON_RADIUS,UiTheme.INPUT);ui.roundFrame(x,y,x+width,y+height,UiTheme.BUTTON_RADIUS,isFocused()&&active?UiTheme.ACCENT:UiTheme.BORDER);
        ui.clip(x+6,y+4,x+width-8,y+height-4);
        try {
            if(text.isEmpty())ui.text(editable?"描述问题及如何复现…":"没有可附带的内容",x+8,y+8,width-20,UiTheme.MUTED);
            int first=Math.max(0,(int)(scroll/step())),last=Math.min(lines.size(),first+(int)Math.ceil(height/step())+1),caret=lineAtCursor();
            for(int i=first;i<last;i++){
                Line line=lines.get(i);double top=y+8+i*step()-scroll;int low=Math.max(line.start,Math.min(cursor,anchor)),high=Math.min(line.end,Math.max(cursor,anchor));
                if(low<high)ui.rect(x+8+ui.measure(text.substring(line.start,low)),top,x+8+ui.measure(text.substring(line.start,high)),top+ui.lineHeight(),UiTheme.SELECTION);
                ui.rawText(text.substring(line.start,line.end),x+8,top,active?UiTheme.TEXT:UiTheme.DISABLED_TEXT);
                if(i==caret&&isFocused()&&active&&editable&&(System.nanoTime()/500_000_000L)%2==0){double at=x+8+ui.measure(text.substring(line.start,cursor));ui.rect(at,top,at+ui.pixel(),top+ui.lineHeight(),UiTheme.FOCUS);}
            }
        } finally {ui.unclip();}
        if(maxScroll()>0){double track=height-12,thumb=Math.max(16,track*track/(lines.size()*step())),top=y+6+(track-thumb)*scroll/maxScroll();ui.roundRect(x+width-5,y+6,x+width-3,y+height-6,1,UiTheme.TRACK);ui.roundRect(x+width-5,top,x+width-3,top+thumb,1,UiTheme.THUMB);}
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput builder){defaultButtonNarrationText(builder);}
}
