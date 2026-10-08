package dev.betterlitematica.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Vanilla editing/clipboard model only; all measurement, painting and pointer selection use our outline font. */
final class OverlayTextField extends TextFieldWidget {
    private int anchor,start;private boolean pointerSelecting;
    OverlayTextField(int x,int y,int width,String label){super(null,x,y,width,20,Text.literal(label));setDrawsBackground(false);}
    @Override public void setSelectionEnd(int position){anchor=Math.max(0,Math.min(getText().length(),position));super.setSelectionEnd(position);}
    int selectionAnchor(){return anchor;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(!visible||!active||!isMouseOver(x,y)||button!=0)return false;
        setFocused(true);var ui=IndependentUi.INSTANCE;if(!ui.ready())return true;
        int hit=start+ui.hit(getText().substring(Math.min(start,getText().length())),x-getX());
        setSelectionStart(hit);if(!Screen.hasShiftDown())setSelectionEnd(hit);pointerSelecting=true;return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!pointerSelecting||button!=0)return false;
        String text=getText();start=Math.min(start,text.length());
        if(x<getX()&&start>0)start=text.offsetByCodePoints(start,-1);
        int hit=start+IndependentUi.INSTANCE.hit(text.substring(start),x-getX());setSelectionStart(hit);return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){pointerSelecting=false;return super.mouseReleased(x,y,button);}
    @Override public void renderButton(MatrixStack legacyMatrices,int mouseX,int mouseY,float delta){LegacyGuiContext context=new LegacyGuiContext(legacyMatrices);
        var ui=IndependentUi.INSTANCE;if(!ui.ready())return;
        String text=getText();int cursor=Math.min(getCursor(),text.length());start=Math.min(start,cursor);
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
