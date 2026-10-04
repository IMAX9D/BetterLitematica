package dev.betterlitematica.fabric;

/** Small geometric HUD marks, independent of font coverage and Minecraft glyph scaling. */
final class HudIcons {
    private HudIcons() {}
    private static void line(IndependentUi ui,double x1,double y1,double x2,double y2,int color){
        int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(x2-x1),Math.abs(y2-y1))*1.5));
        for(int i=0;i<=steps;i++){double x=x1+(x2-x1)*i/steps,y=y1+(y2-y1)*i/steps;ui.rect(x-.45,y-.45,x+.45,y+.45,color);}
    }
    static void projection(IndependentUi ui,double x,double y,double s,int color){
        double[][] p={{.5,0},{1,.25},{1,.75},{.5,1},{0,.75},{0,.25}};
        for(int i=0;i<6;i++)line(ui,x+p[i][0]*s,y+p[i][1]*s,x+p[(i+1)%6][0]*s,y+p[(i+1)%6][1]*s,color);
        line(ui,x,y+s*.25,x+s*.5,y+s*.5,color);line(ui,x+s,y+s*.25,x+s*.5,y+s*.5,color);line(ui,x+s*.5,y+s*.5,x+s*.5,y+s,color);
    }
    static void block(IndependentUi ui,double x,double y,double s,int color){
        ui.roundRect(x,y+s*.15,x+s*.75,y+s*.9,1.2,color);
        line(ui,x+s*.8,y+s*.15,x+s,y,color);line(ui,x+s*.8,y+s*.9,x+s,y+s*.7,color);line(ui,x+s,y,x+s,y+s*.7,color);
    }
    static void printer(IndependentUi ui,double x,double y,double s,int color){
        ui.roundFrame(x,y+s*.3,x+s,y+s*.78,1.2,color);
        ui.frame(x+s*.2,y,x+s*.8,y+s*.32,color);ui.frame(x+s*.2,y+s*.6,x+s*.8,y+s,color);
        ui.rect(x+s*.12,y+s*.45,x+s*.24,y+s*.53,color);
    }
    static void pause(IndependentUi ui,double x,double y,double s,int color){ui.roundRect(x+s*.1,y,x+s*.36,y+s,1,color);ui.roundRect(x+s*.64,y,x+s*.9,y+s,1,color);}
    static void play(IndependentUi ui,double x,double y,double s,int color){for(int i=0;i<(int)s;i++){double t=i/s;ui.rect(x+i,y+s*.5*t,x+i+1,y+s*(1-.5*t),color);}}
    static void waitMark(IndependentUi ui,double x,double y,double s,int color){ui.sector(x+s*.5,y+s*.5,s*.37,s*.46,0,Math.PI*2,color);line(ui,x+s*.5,y+s*.2,x+s*.5,y+s*.5,color);line(ui,x+s*.5,y+s*.5,x+s*.72,y+s*.58,color);}
    static void warning(IndependentUi ui,double x,double y,double s,int color){ui.roundRect(x+s*.4,y,x+s*.6,y+s*.65,1,color);ui.roundRect(x+s*.4,y+s*.82,x+s*.6,y+s,1,color);}
    static void hidden(IndependentUi ui,double x,double y,double s,int color){line(ui,x-1,y+s+1,x+s+1,y-1,color);}
    static void empty(IndependentUi ui,double x,double y,double s,int color){ui.sector(x+s*.5,y+s*.5,s*.37,s*.44,0,Math.PI*2,color);line(ui,x+s*.2,y+s*.8,x+s*.8,y+s*.2,color);}
    static void arrow(IndependentUi ui,double x,double y,double s,int color){line(ui,x,y+s*.5,x+s,y+s*.5,color);line(ui,x+s*.6,y+s*.1,x+s,y+s*.5,color);line(ui,x+s*.6,y+s*.9,x+s,y+s*.5,color);}
    static void target(IndependentUi ui,double x,double y,double s,int color){ui.sector(x+s*.5,y+s*.5,s*.27,s*.35,0,Math.PI*2,color);line(ui,x+s*.5,y,x+s*.5,y+s*.2,color);line(ui,x+s*.5,y+s*.8,x+s*.5,y+s,color);line(ui,x,y+s*.5,x+s*.2,y+s*.5,color);line(ui,x+s*.8,y+s*.5,x+s,y+s*.5,color);}
    static void selection(IndependentUi ui,double x,double y,double s,boolean expanded,int color){
        double arm=s*.3;for(int i=0;i<4;i++){double px=x+((i&1)==0?0:s),py=y+(i<2?0:s),dx=(i&1)==0?1:-1,dy=i<2?1:-1;line(ui,px,py,px+dx*arm,py,color);line(ui,px,py,px,py+dy*arm,color);}
        if(expanded){line(ui,x+s*.3,y+s*.5,x+s*.7,y+s*.5,color);line(ui,x+s*.5,y+s*.3,x+s*.5,y+s*.7,color);}
    }
    static void tool(IndependentUi ui,double x,double y,double s,int color){
        line(ui,x+s*.1,y+s*.9,x+s*.62,y+s*.38,color);line(ui,x+s*.03,y+s*.83,x+s*.55,y+s*.31,color);
        line(ui,x+s*.55,y+s*.31,x+s*.58,y+s*.08,color);line(ui,x+s*.62,y+s*.38,x+s*.86,y+s*.35,color);
        line(ui,x+s*.58,y+s*.08,x+s*.75,y+s*.24,color);line(ui,x+s*.86,y+s*.35,x+s,y+s*.2,color);
    }
}
