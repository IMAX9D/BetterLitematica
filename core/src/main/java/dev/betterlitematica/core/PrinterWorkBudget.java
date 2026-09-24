package dev.betterlitematica.core;

/** One shared tick deadline for scanning and interaction; zero action limit never means an unbounded loop. */
public final class PrinterWorkBudget {
    private final long deadline;
    private int pages,attempts;
    public PrinterWorkBudget(long now,int milliseconds){
        if(milliseconds<1||milliseconds>32)throw new IllegalArgumentException("Printer work budget");
        deadline=now+milliseconds*1_000_000L;
    }
    public long deadline(){return deadline;}
    public boolean hasTime(long now){return now-deadline<0;}
    public boolean scan(long now){if(!hasTime(now)||pages>=8)return false;pages++;return true;}
    public boolean attempt(long now){if(!hasTime(now)||attempts>=4096)return false;attempts++;return true;}
}
