package dev.betterlitematica.core;

import java.util.*;

/** Immutable replaceable contribution from one source section, including a bounded position sample. */
public record VerificationSection(Map<VerificationReport.Key,Long> groups,long matched,List<VerificationReport.Sample> samples,List<VerificationReport.Sample> positions) {
    public VerificationSection(Map<VerificationReport.Key,Long> groups,long matched,List<VerificationReport.Sample> samples){this(groups,matched,samples,samples.stream().filter(s->s.key().type()!=Comparison.UNKNOWN).toList());}
    public static final VerificationSection EMPTY=new VerificationSection(Map.of(),0,List.of());
    public VerificationSection {groups=Collections.unmodifiableMap(new LinkedHashMap<>(groups));samples=List.copyOf(samples);positions=List.copyOf(positions);if(matched<0||groups.size()>4096||samples.size()>32||positions.size()>4096||scanned(groups,matched)>4096)throw new IllegalArgumentException("Section report budget");for(long n:groups.values())if(n<1)throw new IllegalArgumentException("Invalid contribution");for(var position:positions)if(position.key().type()==Comparison.MATCH||position.key().type()==Comparison.UNKNOWN||!groups.containsKey(position.key()))throw new IllegalArgumentException("Invalid spatial contribution");}
    private static long scanned(Map<VerificationReport.Key,Long> groups,long matched){long n=matched;for(long value:groups.values())n=Math.addExact(n,value);return n;}
    public long scanned(){return scanned(groups,matched);}
    public long estimatedBytes(){long n=112+samples.size()*64L+positions.size()*64L;for(var key:groups.keySet())n+=96L+2L*(key.expected().length()+key.actual().length());return n;}
    public static final class Builder {
        private final Map<VerificationReport.Key,Long> groups=new LinkedHashMap<>();private final Map<VerificationReport.Key,VerificationReport.Key> identities=new HashMap<>();private final List<VerificationReport.Sample> samples=new ArrayList<>(),unknown=new ArrayList<>(),positions=new ArrayList<>();private final Map<VerificationReport.Key,Integer> represented=new HashMap<>();private long matched;
        private final java.util.function.Predicate<VerificationReport.Key> ignored;
        public Builder(){this(key->false);}public Builder(java.util.function.Predicate<VerificationReport.Key> ignored){this.ignored=ignored;}
        public void add(Vec3i at,Comparison type,String expected,String actual){if(type==Comparison.MATCH){matched++;return;}var proposed=new VerificationReport.Key(type,expected,actual);var key=identities.computeIfAbsent(proposed,k->k);groups.merge(key,1L,Long::sum);if(type!=Comparison.UNKNOWN){if(positions.size()>=4096)throw new IllegalArgumentException("Section position budget");positions.add(new VerificationReport.Sample(at,key));}if(ignored.test(key))return;
            var sample=new VerificationReport.Sample(at,key);if(type==Comparison.UNKNOWN){if(unknown.size()<8)unknown.add(sample);return;}
            if(samples.size()==32&&!represented.containsKey(key))for(int i=samples.size()-1;i>=0;i--){var previous=samples.get(i).key();if(represented.get(previous)>1){samples.remove(i);represented.merge(previous,-1,Integer::sum);break;}}
            if(samples.size()<32){samples.add(sample);represented.merge(key,1,Integer::sum);}
        }
        public VerificationSection build(){return new VerificationSection(groups,matched,samples.isEmpty()?unknown:samples,positions);}
    }
}
