package dev.betterlitematica.runtime;

/** Retains input ownership across menus and failures until a physical release in the world. */
public final class InputReleaseGuard {
    private boolean armed,waiting;
    public void arm(){armed=true;waiting=true;}
    public void pause(){waiting|=armed;armed=false;}
    public void poll(boolean focused,boolean screenOpen,boolean pressed){if(focused&&!screenOpen&&!pressed)waiting=false;}
    public boolean owns(){return armed||waiting;}
    public boolean ready(){return armed&&!waiting;}
}
