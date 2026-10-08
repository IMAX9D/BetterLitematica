package dev.betterlitematica.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla editing/clipboard model only; all measurement, painting and pointer selection use our outline font. */
final class OverlayTextField extends UiTextField {
    private int anchor,start;private boolean pointerSelecting;
    private java.util.function.Predicate<String> filter=value->true;
    void setFilter(java.util.function.Predicate<String> filter){this.filter=java.util.Objects.requireNonNull(filter);}
    @Override public void setValue(String value){if(filter==null||filter.test(value))super.setValue(value);}
    @Override public void insertText(String value){String old=getValue();int from=Math.min(getCursorPosition(),anchor),to=Math.max(getCursorPosition(),anchor);if(filter.test(old.substring(0,from)+value+old.substring(to)))super.insertText(value);}
    OverlayTextField(int x,int y,int width,String label){super(net.minecraft.client.Minecraft.getInstance().font,x,y,width,20,Component.literal(label));setBordered(false);}
    @Override public void setHighlightPos(int position){anchor=Math.max(0,Math.min(getValue().length(),position));super.setHighlightPos(position);}
    int selectionAnchor(){return anchor;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(!visible||!active||!isMouseOver(x,y)||button!=0)return false;
        setFocused(true);var ui=IndependentUi.INSTANCE;if(!ui.ready())return true;
        int hit=start+ui.hit(getValue().substring(Math.min(start,getValue().length())),x-getX());
        setCursorPosition(hit);if(!UiEvents.shiftDown())setHighlightPos(hit);pointerSelecting=true;return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!pointerSelecting||button!=0)return false;
        String text=getValue();start=Math.min(start,text.length());
        if(x<getX()&&start>0)start=text.offsetByCodePoints(start,-1);
        int hit=start+IndependentUi.INSTANCE.hit(text.substring(start),x-getX());setCursorPosition(hit);return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){pointerSelecting=false;return super.mouseReleased(x,y,button);}
    @Override public void renderWidget(GuiGraphicsExtractor context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;if(!ui.ready())return;
        String text=getValue();int cursor=Math.min(getCursorPosition(),text.length());start=Math.min(start,cursor);
        if(ui.measure(text.substring(start,cursor))>getWidth()-4)start=ui.startForCursor(text,cursor,getWidth()-4);
        String shown=ui.fittingText(text.substring(start),getWidth()-2);int end=start+shown.length();double y=getY()+(getHeight()-ui.lineHeight())/2;
        ui.clip(getX(),getY(),getX()+getWidth(),getY()+getHeight());
        try{
            int lo=Math.max(start,Math.min(cursor,anchor)),hi=Math.min(end,Math.max(cursor,anchor));
            if(lo<hi)ui.rect(getX()+ui.measure(text.substring(start,lo)),getY()+2,getX()+ui.measure(text.substring(start,hi)),getY()+getHeight()-2,UiTheme.SELECTION);
            ui.rawText(shown,getX(),y,active?UiTheme.TEXT:UiTheme.DISABLED_TEXT);
            if(isFocused()&&(System.nanoTime()/500_000_000L)%2==0){double x=getX()+ui.measure(text.substring(start,cursor));ui.rect(x,getY()+3,x+0.7,getY()+getHeight()-3,UiTheme.FOCUS);}
        }finally{ui.unclip();}
    }
}
