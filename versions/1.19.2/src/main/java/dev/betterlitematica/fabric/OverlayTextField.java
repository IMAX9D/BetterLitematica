package dev.betterlitematica.fabric;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
/** Vanilla editing/clipboard model only; all measurement, painting and pointer selection use our outline font. */
final class OverlayTextField extends TextFieldWidget {
    public int getX(){return x;} public int getY(){return y;} public void setX(int value){x=value;} public void setY(int value){y=value;}
    private int anchor,start;private boolean pointerSelecting;private final boolean search;private String placeholder="";
    OverlayTextField(int x,int y,int width,String label){this(x,y,width,label,false);}
    OverlayTextField(int x,int y,int width,String label,boolean search){super(null,x,y,width,20,Text.literal(label));this.search=search;setDrawsBackground(false);}
    private int textX(){return getX()+(search?18:0);}
    private int textWidth(){return Math.max(1,getWidth()-(search?18:0));}
    void placeholder(String value){placeholder=value;}
    @Override public void setSelectionEnd(int position){anchor=Math.max(0,Math.min(getText().length(),position));super.setSelectionEnd(position);}
    int selectionAnchor(){return anchor;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(!visible||!active||!isMouseOver(x,y)||button!=0)return false;
        setFocused(true);var ui=IndependentUi.INSTANCE;if(!ui.ready())return true;
        int hit=start+ui.hit(getText().substring(Math.min(start,getText().length())),Math.max(0,x-textX()));
        setSelectionStart(hit);if(!Screen.hasShiftDown())setSelectionEnd(hit);pointerSelecting=true;return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!pointerSelecting||button!=0)return false;
        String text=getText();start=Math.min(start,text.length());
        if(x<textX()&&start>0)start=text.offsetByCodePoints(start,-1);
        int hit=start+IndependentUi.INSTANCE.hit(text.substring(start),Math.max(0,x-textX()));setSelectionStart(hit);return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){pointerSelecting=false;return super.mouseReleased(x,y,button);}
    @Override public void renderButton(MatrixStack legacyMatrices,int mouseX,int mouseY,float delta){LegacyGuiContext context=new LegacyGuiContext(legacyMatrices);
        var ui=IndependentUi.INSTANCE;if(!ui.ready())return;
        String text=getText();int cursor=Math.min(getCursor(),text.length());start=Math.min(start,cursor);
        if(ui.measure(text.substring(start,cursor))>textWidth()-4)start=ui.startForCursor(text,cursor,Math.max(1,textWidth()-4));
        String shown=ui.fittingText(text.substring(start),Math.max(1,textWidth()-2));int end=start+shown.length();double y=getY()+(getHeight()-ui.lineHeight())/2;
        if(search)searchIcon(ui,getX()+1,getY()+5,active?UiTheme.MUTED:UiTheme.DISABLED_TEXT);
        ui.clip(textX(),getY(),getX()+getWidth(),getY()+getHeight());
        try{
            int lo=Math.max(start,Math.min(cursor,anchor)),hi=Math.min(end,Math.max(cursor,anchor));
            if(lo<hi)ui.rect(textX()+ui.measure(text.substring(start,lo)),getY()+2,textX()+ui.measure(text.substring(start,hi)),getY()+getHeight()-2,UiTheme.SELECTION);
            if(text.isEmpty()&&!placeholder.isEmpty())ui.rawText(ui.fittingText(placeholder,Math.max(1,textWidth()-2)),textX(),y,active?UiTheme.MUTED:UiTheme.DISABLED_TEXT);
            else ui.rawText(shown,textX(),y,active?UiTheme.TEXT:UiTheme.DISABLED_TEXT);
            if(isFocused()&&(System.nanoTime()/500_000_000L)%2==0){double x=textX()+ui.measure(text.substring(start,cursor));ui.rect(x,getY()+3,x+0.7,getY()+getHeight()-3,UiTheme.FOCUS);}
        }finally{ui.unclip();}
    }
    /** Geometry follows the same logical canvas and semantic theme tint as the input. */
    private static void searchIcon(IndependentUi ui,double x,double y,int color){
        ui.sector(x+4,y+4,2.8,3.6,0,Math.PI*2,color);
        for(int i=0;i<=6;i++){double offset=6.5+i*.5;ui.roundRect(x+offset-.45,y+offset-.45,x+offset+.45,y+offset+.45,.45,color);}
    }
}
