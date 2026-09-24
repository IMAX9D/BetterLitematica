package dev.betterlitematica.core;
import java.io.InterruptedIOException;
@FunctionalInterface public interface Cancellation {
    boolean cancelled();
    Cancellation THREAD = () -> Thread.currentThread().isInterrupted();
    Cancellation NEVER = () -> false;
    default void check() throws InterruptedIOException { if(cancelled())throw new InterruptedIOException("Operation cancelled"); }
}
