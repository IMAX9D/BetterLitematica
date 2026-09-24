package dev.betterlitematica.core;

import java.util.*;

/** Bounded result aggregation, independent of rendering and the game world. */
public final class VerificationReport {
    public static final int MAX_GROUPS = 4096, MAX_SAMPLES = 2048;
    public record Key(Comparison type, String expected, String actual) {}
    public record Sample(Vec3i position, Key key) {}
    private final EnumMap<Comparison, Long> counts = new EnumMap<>(Comparison.class);
    private final Map<Key, Long> groups = new LinkedHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final Set<Key> ignored = new HashSet<>();
    private long scanned, ungrouped, ungroupedErrors;
    private Runnable ignoredChanged=()->{};
    public void onIgnoredChanged(Runnable listener){ignoredChanged=Objects.requireNonNull(listener);}
    public void add(Vec3i position, Comparison type, String expected, String actual) {
        scanned++; counts.merge(type, 1L, Long::sum);
        if (type == Comparison.MATCH) return;
        Key key = new Key(type, expected, actual);
        if (groups.containsKey(key) || groups.size() < MAX_GROUPS) groups.merge(key, 1L, Long::sum); else { ungrouped++; if(type!=Comparison.UNKNOWN)ungroupedErrors++; }
        if (samples.size() < MAX_SAMPLES) samples.add(new Sample(position, key));
    }
    public long scanned() { return scanned; }
    public long count(Comparison type) { return counts.getOrDefault(type, 0L); }
    public long ungrouped() { return ungrouped; }
    public Map<Key, Long> groups() {var result=new LinkedHashMap<Key,Long>();groups.forEach((k,v)->{if(v>0)result.put(k,v);});return Collections.unmodifiableMap(result);}
    public List<Sample> samples() { return samples.stream().filter(s -> !ignored.contains(s.key())).toList(); }
    public boolean ignored(Key key) { return ignored.contains(key); }
    public void ignore(Key key) { if (!groups.containsKey(key)) throw new IllegalArgumentException("Unknown mismatch group"); if(ignored.add(key)){samples.removeIf(s->s.key().equals(key));ignoredChanged.run();} }
    public Set<Key> ignoredKeys(){return Set.copyOf(ignored);}
    public void restore(Key key){if(ignored.remove(key))ignoredChanged.run();}
    public void resetIgnored() { if(!ignored.isEmpty()){ignored.clear();ignoredChanged.run();} }
    /** Replace, never accumulate, the contribution of a rescanned section. Group identities remain stable. */
    public void replace(VerificationSection before,VerificationSection after){
        if(!before.equals(after)){change(before,-1);change(after,1);samples.removeAll(before.samples());}
        samples.removeIf(sample->ignored.contains(sample.key()));
        if(samples.size()>=MAX_SAMPLES&&after.samples().stream().anyMatch(sample->sample.key().type()!=Comparison.UNKNOWN))samples.removeIf(sample->sample.key().type()==Comparison.UNKNOWN);
        for(var sample:after.samples())if(samples.size()<MAX_SAMPLES&&!ignored.contains(sample.key())&&!samples.contains(sample))samples.add(sample);
    }
    private void change(VerificationSection section,int sign){
        scanned=Math.addExact(scanned,sign*section.scanned());counts.merge(Comparison.MATCH,sign*section.matched(),Long::sum);
        for(var entry:section.groups().entrySet()){
            var key=entry.getKey();long n=sign*entry.getValue();counts.merge(key.type(),n,Long::sum);
            if(groups.containsKey(key)||sign>0&&groups.size()<MAX_GROUPS)groups.merge(key,n,Long::sum);
            else {ungrouped+=n;if(key.type()!=Comparison.UNKNOWN)ungroupedErrors+=n;}
        }
        if(scanned<0||ungrouped<0||ungroupedErrors<0)throw new IllegalStateException("Invalid verification replacement order");
    }
    public long remainingErrors() {
        long result = ungroupedErrors;
        for (var entry : groups.entrySet()) if (entry.getKey().type() != Comparison.UNKNOWN && !ignored.contains(entry.getKey())) result += entry.getValue();
        return result;
    }
}
