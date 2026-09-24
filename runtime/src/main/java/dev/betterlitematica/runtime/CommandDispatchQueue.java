package dev.betterlitematica.runtime;

import dev.betterlitematica.core.Vec3i;
import java.util.*;
import java.util.function.Predicate;

/** Fair, bounded dispatch of independent transactions; order within each transaction is preserved. */
public final class CommandDispatchQueue {
    private static final long MAX_TRANSACTION_BYTES=3L<<20,MAX_BYTES=32L<<20;
    private static final int MAX_TRANSACTIONS=128;
    private static final class Transaction {
        final List<String> lines;final List<Vec3i> required;final long bytes;int cursor;
        Transaction(List<String> lines,List<Vec3i> required,long bytes){this.lines=List.copyOf(lines);this.required=List.copyOf(required);this.bytes=bytes;}
    }
    private final ArrayDeque<Transaction> pending=new ArrayDeque<>();
    private long bytes;
    public boolean hasRoom(){return pending.size()<MAX_TRANSACTIONS&&bytes<=MAX_BYTES-MAX_TRANSACTION_BYTES;}
    public boolean isEmpty(){return pending.isEmpty();}
    public int size(){return pending.size();}
    public void add(List<String> lines,List<Vec3i> required){
        if(lines.isEmpty()||lines.size()>4096||required.size()>512)throw new IllegalArgumentException("Command transaction size");
        long charge=128L+32L*required.size();for(String line:lines)charge+=64L+2L*line.length();
        if(charge>MAX_TRANSACTION_BYTES||!hasRoom())throw new IllegalStateException("Command dispatch budget exceeded");
        pending.addLast(new Transaction(lines,required,charge));bytes+=charge;
    }
    public String poll(Predicate<Vec3i> loaded){
        int remaining=pending.size();
        while(remaining-->0){var next=pending.removeFirst();if(!next.required.stream().allMatch(loaded)){pending.addLast(next);continue;}
            String line=next.lines.get(next.cursor++);if(next.cursor<next.lines.size())pending.addLast(next);else bytes-=next.bytes;return line;
        }
        return null;
    }
}
