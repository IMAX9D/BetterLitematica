package dev.betterlitematica.core;

import java.util.*;

/** Sparse, region-qualified draft over an immutable complete-source palette. */
public final class SchematicEdits {
    public static final int MAX_CELLS=65_536,MAX_SECTIONS=4096,MAX_TRANSACTION=16_384,MAX_HISTORY_CELLS=262_144;
    public record Patch(int original,int state,boolean blockData,boolean blockTicks,boolean fluidTicks){}
    public record Request(SectionKey section,int cell,int original,BlockStateSpec state,
                          boolean keepBlockData,boolean keepBlockTicks,boolean keepFluidTicks){}
    public record Snapshot(BlueprintMetadata metadata,Map<SectionKey,Map<Integer,Patch>> sections,long revision){
        public Patch patch(SectionKey section,int cell){var values=sections.get(section);return values==null?null:values.get(cell);}
    }
    public record Address(SectionKey section,int cell){public Address{Objects.requireNonNull(section);}}
    public record Delta(Address address,Patch before,Patch after){public Delta{Objects.requireNonNull(address);}}
    public record Action(List<Delta> values){public Action{values=List.copyOf(values);}}
    /** Immutable recovery image. Live checkpoints reuse already immutable section maps and actions. */
    public static final class Checkpoint {
        private final BlueprintMetadata metadata;private final int originalPaletteSize;private final Map<SectionKey,Map<Integer,Patch>> sections;
        private final long revision;private final List<Action> undo,redo;private volatile long estimate=-1;
        public Checkpoint(BlueprintMetadata metadata,int originalPaletteSize,Map<SectionKey,Map<Integer,Patch>> sections,long revision,List<Action> undo,List<Action> redo){this(metadata,originalPaletteSize,freeze(sections),revision,undo,redo,true);}
        private Checkpoint(BlueprintMetadata metadata,int originalPaletteSize,Map<SectionKey,Map<Integer,Patch>> sections,long revision,List<Action> undo,List<Action> redo,boolean trusted){
            this.metadata=Objects.requireNonNull(metadata);this.originalPaletteSize=originalPaletteSize;this.sections=sections;this.revision=revision;this.undo=List.copyOf(undo);this.redo=List.copyOf(redo);
        }
        private static Map<SectionKey,Map<Integer,Patch>> freeze(Map<SectionKey,Map<Integer,Patch>> source){
            if(source.size()>MAX_SECTIONS)throw new IllegalArgumentException("Draft section limit");var copy=new HashMap<SectionKey,Map<Integer,Patch>>();int count=0;
            for(var e:source.entrySet()){count=Math.addExact(count,e.getValue().size());if(count>MAX_CELLS)throw new IllegalArgumentException("Draft cell limit");copy.put(e.getKey(),Map.copyOf(e.getValue()));}return Map.copyOf(copy);
        }
        public BlueprintMetadata metadata(){return metadata;}public int originalPaletteSize(){return originalPaletteSize;}public Map<SectionKey,Map<Integer,Patch>> sections(){return sections;}
        public long revision(){return revision;}public List<Action> undo(){return undo;}public List<Action> redo(){return redo;}
        public long estimatedBytes(){long result=estimate;if(result>=0)return result;long n=256L+sections.size()*128L;for(var s:sections.values())n+=s.size()*112L;for(var a:undo)n+=64L+a.values().size()*160L;for(var a:redo)n+=64L+a.values().size()*160L;estimate=n+metadataBytes(metadata);return estimate;}
    }
    private final BlueprintMetadata original;
    private final Map<BlockStateSpec,Integer> ids=new HashMap<>();
    private final ArrayDeque<Action> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private Snapshot snapshot;
    private long[] counts;
    private int cells,historyCells;
    private long metadataBytes;private Checkpoint cachedCheckpoint;
    public SchematicEdits(BlueprintMetadata metadata,long[] counts){
        if(counts.length!=metadata.palette().size())throw new IllegalArgumentException("Invalid source histogram");
        original=metadata;this.counts=counts.clone();for(int i=0;i<metadata.palette().size();i++)ids.put(metadata.palette().get(i),i);
        snapshot=new Snapshot(metadata,Map.of(),0);
        metadataBytes=metadataBytes(metadata);
    }
    private SchematicEdits(SchematicEdits source){original=source.original;counts=source.counts.clone();ids.putAll(source.ids);undo.addAll(source.undo);redo.addAll(source.redo);snapshot=source.snapshot;cells=source.cells;historyCells=source.historyCells;metadataBytes=source.metadataBytes;cachedCheckpoint=source.cachedCheckpoint;}
    /** Candidate transactions share immutable frames until admitted by the checkpoint writer. */
    public SchematicEdits fork(){return new SchematicEdits(this);}
    public Snapshot snapshot(){return snapshot;}
    public Checkpoint checkpoint(){if(cachedCheckpoint==null){cachedCheckpoint=new Checkpoint(snapshot.metadata(),original.palette().size(),snapshot.sections(),snapshot.revision(),List.copyOf(undo),List.copyOf(redo),true);cachedCheckpoint.estimate=256L+snapshot.sections().size()*128L+cells*112L+(undo.size()+redo.size())*64L+historyCells*160L+metadataBytes;}return cachedCheckpoint;}
    private static long stateBytes(BlockStateSpec state){long n=96L+state.name().length()*2L;for(var p:state.properties().entrySet())n+=96L+2L*(p.getKey().length()+p.getValue().length());return n;}
    private static long metadataBytes(BlueprintMetadata metadata){long n=128L+2L*(metadata.name().length()+metadata.sourceSha256().length());for(var state:metadata.palette())n+=stateBytes(state);for(var r:metadata.regions())n+=128L+2L*r.name().length();for(var w:metadata.warnings())n+=48L+2L*w.length();return n;}
    /** Structural validation also checks the undo/redo chain before any recovered state is published. */
    public static void validateCheckpoint(Checkpoint image){
        var meta=image.metadata();int originalSize=image.originalPaletteSize();
        if(originalSize<1||originalSize>meta.palette().size()||image.revision()<0||image.undo().size()+image.redo().size()>128||image.estimatedBytes()>(96L<<20))throw new IllegalArgumentException("Invalid draft checkpoint limits");
        long volume=0;var names=new HashSet<String>();for(var r:meta.regions()){volume=Math.addExact(volume,r.volume());if(!names.add(r.name()))throw new IllegalArgumentException("Duplicate draft region");}if(volume>536_870_912L)throw new IllegalArgumentException("Draft source volume limit");
        if(new HashSet<>(meta.palette()).size()!=meta.palette().size())throw new IllegalArgumentException("Duplicate draft state");
        var state=new HashMap<Address,Patch>();var originals=new HashMap<Address,Integer>();
        for(var e:image.sections().entrySet()){if(e.getValue().isEmpty())throw new IllegalArgumentException("Empty draft section");for(var cell:e.getValue().entrySet()){var at=new Address(e.getKey(),cell.getKey());checkPatch(image,at,cell.getValue(),originals);state.put(at,cell.getValue());}}
        if(state.size()>MAX_CELLS||image.sections().size()>MAX_SECTIONS)throw new IllegalArgumentException("Draft cell limit");
        int history=0;for(var list:List.of(image.undo(),image.redo()))for(var action:list){if(action.values().isEmpty()||action.values().size()>MAX_TRANSACTION)throw new IllegalArgumentException("Invalid draft action size");history=Math.addExact(history,action.values().size());if(history>MAX_HISTORY_CELLS)throw new IllegalArgumentException("Draft history limit");var addresses=new HashSet<Address>();for(var change:action.values()){if(!addresses.add(change.address())||Objects.equals(change.before(),change.after()))throw new IllegalArgumentException("Invalid duplicate/no-op draft action");checkPatch(image,change.address(),change.before(),originals);checkPatch(image,change.address(),change.after(),originals);}}
        checkHistory(state,image.undo(),true);checkHistory(state,image.redo(),false);
    }
    private static void checkPatch(Checkpoint image,Address address,Patch patch,Map<Address,Integer> originals){
        var key=address.section();int cell=address.cell();if(key.region()<0||key.region()>=image.metadata().regions().size()||cell<0||cell>=4096)throw new IllegalArgumentException("Invalid draft address");
        var region=image.metadata().regions().get(key.region());var local=region.sectionOrigin(key).add(new Vec3i(cell&15,cell>>>8,(cell>>>4)&15));if(!region.contains(local))throw new IllegalArgumentException("Draft address outside region");
        if(patch==null)return;if(patch.original()<0||patch.original()>=image.originalPaletteSize()||patch.state()<0||patch.state()>=image.metadata().palette().size()||patch.original()==patch.state()&&patch.blockData()&&patch.blockTicks()&&patch.fluidTicks())throw new IllegalArgumentException("Invalid draft patch");
        Integer previous=originals.putIfAbsent(address,patch.original());if(previous!=null&&previous!=patch.original())throw new IllegalArgumentException("Draft original state mismatch");
    }
    private static void checkHistory(Map<Address,Patch> current,List<Action> actions,boolean backwards){
        var state=new HashMap<>(current);for(int i=actions.size()-1;i>=0;i--){for(var delta:actions.get(i).values()){var expected=backwards?delta.after():delta.before();if(!Objects.equals(state.get(delta.address()),expected))throw new IllegalArgumentException("Broken draft history chain");var replacement=backwards?delta.before():delta.after();if(replacement==null)state.remove(delta.address());else state.put(delta.address(),replacement);}if(state.size()>MAX_CELLS)throw new IllegalArgumentException("Historical draft cell limit");var sections=new HashSet<SectionKey>();for(var at:state.keySet())sections.add(at.section());if(sections.size()>MAX_SECTIONS)throw new IllegalArgumentException("Historical draft section limit");}
    }
    public static SchematicEdits restore(Checkpoint image,BlueprintMetadata loadedSource,long[] baseCounts){
        validateCheckpoint(image);var meta=image.metadata();if(!meta.sourceSha256().equals(loadedSource.sourceSha256())||meta.dataVersion()!=loadedSource.dataVersion()||!meta.regions().equals(loadedSource.regions())||image.originalPaletteSize()!=loadedSource.palette().size()||!meta.palette().subList(0,image.originalPaletteSize()).equals(loadedSource.palette()))throw new IllegalArgumentException("Draft source identity mismatch");
        long total=0;for(long n:baseCounts){if(n<0)throw new IllegalArgumentException("Negative source count");total=Math.addExact(total,n);}long volume=loadedSource.regions().stream().mapToLong(Region::volume).sum();if(total!=volume)throw new IllegalArgumentException("Source histogram mismatch");
        var originalCells=new HashMap<Address,Integer>();for(var e:image.sections().entrySet())for(var value:e.getValue().entrySet())originalCells.put(new Address(e.getKey(),value.getKey()),value.getValue().original());
        for(var history:List.of(image.undo(),image.redo()))for(var action:history)for(var delta:action.values()){var patch=delta.before()==null?delta.after():delta.before();originalCells.put(delta.address(),patch.original());}
        var historicalCounts=new long[image.originalPaletteSize()];for(int id:originalCells.values())if(++historicalCounts[id]>baseCounts[id])throw new IllegalArgumentException("Draft history exceeds source histogram");
        var result=new SchematicEdits(loadedSource,baseCounts);result.counts=Arrays.copyOf(baseCounts,meta.palette().size());
        var touched=new long[image.originalPaletteSize()];for(var values:image.sections().values())for(var patch:values.values()){if(++touched[patch.original()]>baseCounts[patch.original()])throw new IllegalArgumentException("Draft exceeds source histogram");result.counts[patch.original()]--;result.counts[patch.state()]++;result.cells++;}
        result.ids.clear();for(int i=0;i<meta.palette().size();i++)result.ids.put(meta.palette().get(i),i);
        result.snapshot=new Snapshot(meta,image.sections(),image.revision());result.undo.addAll(image.undo());result.redo.addAll(image.redo());for(var a:image.undo())result.historyCells+=a.values().size();for(var a:image.redo())result.historyCells+=a.values().size();result.cachedCheckpoint=image;result.metadataBytes=image.estimatedBytes()-(256L+image.sections().size()*128L+result.cells*112L+(result.undo.size()+result.redo.size())*64L+result.historyCells*160L);return result;
    }
    public boolean dirty(){return !snapshot.sections().isEmpty();}
    public boolean canUndo(){return !undo.isEmpty();}public boolean canRedo(){return !redo.isEmpty();}
    public long[] counts(){return counts.clone();}
    public int sample(SectionKey section,int cell,int base){var patch=snapshot.patch(section,cell);return patch==null?base:patch.state();}
    public Set<SectionKey> apply(List<Request> requests){
        if(requests.size()>MAX_TRANSACTION)throw new IllegalArgumentException("本次编辑范围过大");
        var unique=new LinkedHashMap<Address,Request>();for(var r:requests){validate(r);unique.put(new Address(r.section(),r.cell()),r);}
        var added=new LinkedHashMap<BlockStateSpec,Integer>();var deltas=new ArrayList<Delta>();int nextCount=cells;
        for(var entry:unique.entrySet()){
            var address=entry.getKey();var request=entry.getValue();Patch before=snapshot.patch(address.section(),address.cell());
            if(before!=null&&before.original()!=request.original())throw new IllegalArgumentException("投影源数据已改变");
            int previous=before==null?request.original():before.state();
            if(snapshot.metadata().palette().get(previous).equals(request.state()))continue;
            Integer target=ids.get(request.state());if(target==null){target=added.computeIfAbsent(request.state(),state->snapshot.metadata().palette().size()+added.size());if(target>=65536)throw new IllegalArgumentException("方块状态数量超过限制");}
            boolean data=request.keepBlockData()&&(before==null||before.blockData()),ticks=request.keepBlockTicks()&&(before==null||before.blockTicks()),fluid=request.keepFluidTicks()&&(before==null||before.fluidTicks());
            Patch after=new Patch(request.original(),target,data,ticks,fluid);
            if(after.state()==after.original()&&data&&ticks&&fluid)after=null;
            nextCount+=(after==null?0:1)-(before==null?0:1);deltas.add(new Delta(address,before,after));
        }
        if(deltas.isEmpty())return Set.of();
        if(nextCount>MAX_CELLS)throw new IllegalArgumentException("草稿已满，请先另存投影");
        var sections=updated(snapshot.sections(),deltas,false);
        if(sections.size()>MAX_SECTIONS)throw new IllegalArgumentException("编辑范围过大，请先另存投影");
        var palette=new ArrayList<>(snapshot.metadata().palette());palette.addAll(added.keySet());
        var metadata=added.isEmpty()?snapshot.metadata():new BlueprintMetadata(original.name(),original.dataVersion(),original.sourceSha256(),palette,original.regions(),original.warnings());
        if(counts.length<palette.size())counts=Arrays.copyOf(counts,palette.size());
        applyCounts(deltas,false);ids.putAll(added);for(var state:added.keySet())metadataBytes+=stateBytes(state);cells=nextCount;snapshot=new Snapshot(metadata,sections,snapshot.revision()+1);cachedCheckpoint=null;
        for(var action:redo)historyCells-=action.values().size();redo.clear();
        while(!undo.isEmpty()&&(undo.size()>=128||historyCells+deltas.size()>MAX_HISTORY_CELLS))historyCells-=undo.removeFirst().values().size();
        undo.addLast(new Action(List.copyOf(deltas)));historyCells+=deltas.size();return affected(deltas);
    }
    private void validate(Request r){
        Objects.requireNonNull(r.state());int region=r.section().region();if(region<0||region>=original.regions().size()||r.cell()<0||r.cell()>=4096||r.original()<0||r.original()>=original.palette().size())throw new IllegalArgumentException("无效编辑位置或源状态");
        var bounds=original.regions().get(region);int i=r.cell();var local=bounds.sectionOrigin(r.section()).add(new Vec3i(i&15,i>>>8,(i>>>4)&15));if(!bounds.contains(local))throw new IllegalArgumentException("编辑位置超出子区域");
    }
    public Set<SectionKey> undo(){return travel(true);}public Set<SectionKey> redo(){return travel(false);}
    private Set<SectionKey> travel(boolean back){
        var source=back?undo:redo;var destination=back?redo:undo;if(source.isEmpty())return Set.of();var action=source.peekLast();
        var sections=updated(snapshot.sections(),action.values(),back);applyCounts(action.values(),back);
        for(var delta:action.values()){int change=(delta.after()==null?0:1)-(delta.before()==null?0:1);cells+=back?-change:change;}
        snapshot=new Snapshot(snapshot.metadata(),sections,snapshot.revision()+1);cachedCheckpoint=null;source.removeLast();destination.addLast(action);return affected(action.values());
    }
    private void applyCounts(List<Delta> changes,boolean reverse){
        for(var delta:changes){var any=delta.before()==null?delta.after():delta.before();int from=delta.before()==null?any.original():delta.before().state(),to=delta.after()==null?any.original():delta.after().state();if(reverse){int old=from;from=to;to=old;}counts[from]--;counts[to]++;}
    }
    private static Map<SectionKey,Map<Integer,Patch>> updated(Map<SectionKey,Map<Integer,Patch>> current,List<Delta> changes,boolean reverse){
        var result=new HashMap<>(current);var modified=new HashMap<SectionKey,Map<Integer,Patch>>();
        for(var delta:changes){var key=delta.address().section();var values=modified.computeIfAbsent(key,k->new HashMap<>(current.getOrDefault(k,Map.of())));var value=reverse?delta.before():delta.after();if(value==null)values.remove(delta.address().cell());else values.put(delta.address().cell(),value);}
        modified.forEach((key,values)->{if(values.isEmpty())result.remove(key);else result.put(key,Map.copyOf(values));});return Map.copyOf(result);
    }
    private static Set<SectionKey> affected(List<Delta> values){var keys=new HashSet<SectionKey>();for(var delta:values)keys.add(delta.address().section());return Set.copyOf(keys);}
}
