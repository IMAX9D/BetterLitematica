package dev.betterlitematica.selftest;

import dev.betterlitematica.core.PrinterQueue;
import java.util.HashSet;
import java.util.Random;

/** Finite producer/consumer interleavings; no timing, threads, Minecraft or world writes. */
public final class PrinterBatchChecks {
    private PrinterBatchChecks() { }
    private static final class Checks {
        int count;
        void require(boolean value, String message) {
            count++;
            if (!value) throw new AssertionError(message);
        }
    }
    private static PrinterQueue.Job job(long position, int material) {
        return new PrinterQueue.Job(11, position, material, 0, PrinterQueue.Kind.PLACE);
    }
    public static int run() {
        var checks = new Checks();
        var longBatch = new PrinterQueue(512);
        longBatch.offer(job(900, 2), 2);
        for (int i = 0; i < 160; i++) longBatch.offer(job(i, 1), 1);
        for (int i = 0; i < 160; i++) {
            checks.require(longBatch.pollBatch(i < 80 ? 1 : 2).position() == i,
                    "Frozen batch exceeds 64 and survives changed hand across calls");
        }
        checks.require(longBatch.pollBatch(1).position() == 900, "Other material follows complete first batch");
        checks.require(longBatch.pollBatch(1) == null && longBatch.size() == 0, "Completed queue is empty");

        var appended = new PrinterQueue(64);
        for (int i = 0; i < 3; i++) appended.offer(job(i, 1), 1);
        appended.offer(job(100, 2), 2);
        checks.require(appended.pollBatch(1).position() == 0, "First batch prefers held material");
        for (int i = 3; i < 20; i++) appended.offer(job(i, 1), 1);
        appended.offer(job(200, 3), 3);
        checks.require(appended.pollBatch(1).position() == 1, "Second original item retained");
        checks.require(appended.pollBatch(1).position() == 2, "Third original item retained");
        checks.require(appended.pollBatch(1).position() == 100, "Append cannot extend original batch");
        checks.require(appended.pollBatch(1).position() == 200, "Late bucket is not overtaken by held material");
        for (int i = 3; i < 20; i++) checks.require(appended.pollBatch(1).position() == i,
                "Next batch contains appended work in original order");

        var stream = new PrinterQueue(32);
        for (int i = 0; i < 8; i++) stream.offer(job(i, 1), 1);
        stream.offer(job(1000, 2), 2);
        for (int i = 0; i < 8; i++) {
            checks.require(stream.pollBatch(1).position() == i, "Continuous producer cannot truncate batch");
            checks.require(stream.offer(job(20 + i, 1), 1), "Continuous producer stays bounded");
            if (i == 4) stream.offer(job(2000, 3), 3);
        }
        checks.require(stream.pollBatch(1).position() == 1000, "Continuous producer cannot starve existing bucket");
        checks.require(stream.pollBatch(1).position() == 2000, "Continuous producer cannot starve late bucket");
        for (int i = 0; i < 8; i++) checks.require(stream.pollBatch(1).position() == 20 + i,
                "Producer additions remain available for next finite batch");

        var duplicate = new PrinterQueue(2);
        var first = job(1, 5);
        checks.require(duplicate.offer(first, 5), "Initial offer accepted");
        checks.require(!duplicate.offer(first, 7), "Same position/kind cannot enter another bucket");
        checks.require(duplicate.offer(job(2, 6), 6) && !duplicate.offer(job(3, 5), 5), "Capacity remains enforced");
        checks.require(duplicate.pollBatch(5).equals(first), "Reserved item consumed once");
        checks.require(duplicate.offer(first, 5), "Consumed position can be rediscovered");
        checks.require(duplicate.pollBatch(5).position() == 2, "Rediscovered item cannot overtake pending other material");
        checks.require(duplicate.pollBatch(5).equals(first), "Rediscovered item appears in later batch");

        var reset = new PrinterQueue(16);
        for (int i = 0; i < 5; i++) reset.offer(job(i, 1), 1);
        reset.pollBatch(1);
        reset.clear();
        checks.require(reset.size() == 0 && reset.remaining() == 16 && reset.pollBatch(1) == null,
                "Clear removes all work and frozen batch state");
        reset.offer(job(100, 2), 2);
        reset.offer(job(200, 3), 3);
        checks.require(reset.pollBatch(3).position() == 200, "Clear restores initial held-item preference");
        reset.pollBatch(3);
        reset.offer(job(300, 2), 2);
        reset.offer(job(400, 3), 3);
        checks.require(reset.pollBatch(3).position() == 400, "Draining restores initial held-item preference");

        var legacy = new PrinterQueue(256);
        for (int i = 0; i < 128; i++) legacy.offer(job(i, 1), 1);
        legacy.offer(job(999, 2), 2);
        for (int i = 0; i < 64; i++) checks.require(legacy.poll(1, 64).position() == i,
                "Legacy fixed-limit poll preserves old ordering");
        checks.require(legacy.poll(1, 64).position() == 999, "Legacy fixed-limit fairness preserved");
        checks.require(legacy.pollBatch(1).position() == 64, "New API can start after legacy API");
        checks.require(legacy.poll().position() == 65, "Legacy poll can consume during a finite batch");
        checks.require(legacy.pollBatch(1).position() == 66, "Mixed APIs do not leave stale snapshot counts");
        legacy.clear();

        // Repeated producer/consumer interleavings: each accepted key is returned exactly once.
        var random = new Random(0xB47C11L);
        var randomQueue = new PrinterQueue(128);
        var pending = new HashSet<Long>();
        long next = 0;
        for (int step = 0; step < 4000; step++) {
            if (random.nextBoolean()) {
                int material = random.nextInt(7);
                long position = next++;
                boolean accepted = randomQueue.offer(job(position, material), material);
                if (accepted) pending.add(position);
                checks.require(accepted || randomQueue.remaining() == 0, "Only full queue rejects unique work");
            } else {
                var value = randomQueue.pollBatch(random.nextInt(7));
                checks.require(value == null ? pending.isEmpty() : pending.remove(value.position()),
                        "Frozen batches neither duplicate nor lose accepted jobs");
            }
            checks.require(randomQueue.size() == pending.size(), "Queue bookkeeping stays consistent");
        }
        while (randomQueue.size() > 0) checks.require(pending.remove(randomQueue.pollBatch(0).position()),
                "Remaining jobs drain exactly once");
        checks.require(pending.isEmpty(), "All accepted work accounted for");
        return checks.count;
    }
}
