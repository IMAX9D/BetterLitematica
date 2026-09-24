package dev.betterlitematica.runtime;
import java.io.IOException;
import java.nio.file.*;
/** Cancellation and publishing a newly created artifact have one linearization point. */
public final class NewFileCommit {
    private enum State {OPEN,CANCELLED,COMMITTED}
    private State state=State.OPEN;
    public synchronized boolean cancel(){if(state!=State.OPEN)return false;state=State.CANCELLED;return true;}
    public synchronized boolean commit(Path temporary,Path target)throws IOException{if(state!=State.OPEN)return false;Files.move(temporary,target);state=State.COMMITTED;return true;}
    public synchronized boolean committed(){return state==State.COMMITTED;}
    public synchronized boolean cancelled(){return state==State.CANCELLED;}
}
