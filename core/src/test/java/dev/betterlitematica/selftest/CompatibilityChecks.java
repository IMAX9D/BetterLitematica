package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import dev.betterlitematica.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Cross-version fixtures exercise the complete IO path without depending on a game registry. */
final class CompatibilityChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static final class Registry implements SourceVersions.Adapter {
        final boolean extra;int fixes;
        Registry(boolean extra){this.extra=extra;}
        public int version(){return 3465;}
        public String cacheKey(){return "compatibility-test-"+extra;}
        public boolean compatibleLoading(){return true;}
        public boolean known(SourceVersions.Kind kind,String name){return switch(kind){
            case STATE->Set.of("minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs","minecraft:structure_void").contains(name)||extra&&name.equals("minecraft:future_block");
            case BLOCK_ENTITY->name.equals("minecraft:sign");case ENTITY->name.equals("minecraft:armor_stand");};}
        public boolean supported(BlockStateSpec state){return known(SourceVersions.Kind.STATE,state.name())&&(state.name().equals("minecraft:oak_stairs")||state.properties().isEmpty());}
        public Map<String,Object> fix(SourceVersions.Kind kind,Map<String,Object> value,int source){fixes++;var copy=new LinkedHashMap<>(value);if("minecraft:old_stone".equals(copy.get("Name")))copy.put("Name","minecraft:stone");return copy;}
        public Map<String,Object> legacy(int id,int metadata,String name){return Map.of("Name","minecraft:stone");}
    }
    private static Map<String,Object> compound(Object value)throws IOException{return NbtReader.compound(value,"fixture");}
    private static Map<String,Object> future(boolean auxiliary){
        var region=Fixtures.region(0,0,0,4,1,1,i->(i+1)%4);
        region.put("BlockStatePalette",List.of(Fixtures.state("minecraft:air"),Fixtures.state("minecraft:stone"),
            Map.of("Name","minecraft:future_block","Properties",Map.of("future_payload",List.of(1,2,3))),
            Map.of("Name","minecraft:glass","Properties",Map.of("future_property","enabled"))));
        if(auxiliary){
            region.put("TileEntities",List.of(Map.of("id","minecraft:sign","x",1,"y",0,"z",0),Map.of("id","minecraft:future_entity","unreadable_position",1)));
            region.put("Entities",List.of(Map.of("id","minecraft:future_mob"),Map.of("id","minecraft:armor_stand","Pos",List.of(0d,0d,0d))));
            region.put("PendingBlockTicks",List.of(Map.of("x",1,"y",0,"z",0,"Block","minecraft:future_block","Time",1)));
            region.put("PendingFluidTicks",List.of(Map.of("x",2,"y",0,"z",0,"Fluid","minecraft:water","Time",1)));
        }
        var root=Fixtures.litematic(Map.of("main",region),"跨版本测试");root.put("Version",8);root.put("MinecraftDataVersion",9999);return root;
    }
    private static SchematicImporter.Result load(Path source,Path directory)throws IOException{return SchematicImporter.importFile(source,directory,Cancellation.NEVER,p->{});}
    private static void rejects(Path source,Path directory)throws Exception{try{load(source,directory);throw new AssertionError("Malformed input accepted");}catch(IOException expected){checks++;}}
    static int run(Path directory)throws Exception{
        checks=0;Files.createDirectories(directory);var field=SourceVersions.class.getDeclaredField("adapter");field.setAccessible(true);Object previous=field.get(null);
        try{
            var registry=new Registry(false);SourceVersions.install(registry);Path cacheDir=directory.resolve("cache"),source=directory.resolve("future.litematic");Fixtures.write(source,future(true));String original=SchematicImporter.sha256(source,Cancellation.NEVER);
            var imported=load(source,cacheDir);check(!imported.cacheHit(),"Cold compatible import");
            try(var cache=BlueprintCache.open(imported.path())){
                var metadata=cache.metadata();var compatibility=metadata.compatibility();
                check(compatibility.incompatible()&&compatibility.newerFormat()&&compatibility.newerGame(),"Format and game versions recorded separately");
                check(compatibility.skippedBlockTypes()==2,"Unknown block and unsupported known-block state are reported");
                check(metadata.palette().equals(List.of(BlockStateSpec.AIR,BlockStateSpec.parse("stone"),SourceVersions.SKIPPED)),"Discarded source states do not enter the resolved palette");
                check(Arrays.equals(cache.copyBlockStateCounts(),new long[]{1,1,2}),"Skipped cells retain no-op identity, never expected air");
                var section=cache.read(new SectionKey(0,0,0,0));
                check(section.globalId(0,0,0)==1&&section.globalId(1,0,0)==2&&section.globalId(2,0,0)==2&&section.globalId(3,0,0)==0,"Block positions and original air remain intact");
                var data=AuxiliaryData.read(source,cache,Cancellation.NEVER);
                check(data.parts().get(0).blocks().isEmpty(),"Discarded blocks never create block entities");
                check(data.parts().get(0).entities().size()==1&&data.parts().get(0).entities().get(0).get("id").equals("minecraft:armor_stand"),"Unknown entities are skipped before game parsing");
                for(var rule:ReplaceRule.values()){
                    var placement=new Placement(UUID.randomUUID(),"test","future.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false).overlap(rule);
                    var layout=new PlacementLayout(placement,metadata.regions());var values=new PlacementLayout.Source(){public int state(PlacementLayout.Part p,Vec3i v){return section.globalId(v.x(),v.y(),v.z());}public BlockStateSpec spec(int region,int id){return metadata.palette().get(id);}};
                    check(layout.sample(new Vec3i(1,0,0),values)==null&&layout.sample(new Vec3i(2,0,0),values)==null,"Discarded positions cannot be compared, mined or printed under "+rule);
                    check(layout.sample(Vec3i.ZERO,values).state()==1,"Known block survives "+rule);
                }
                var edits=new SchematicEdits(metadata,cache.copyBlockStateCounts());
                edits.apply(List.of(new SchematicEdits.Request(new SectionKey(0,0,0,0),0,1,BlockStateSpec.parse("glass"),true,true,true)));
                check(edits.snapshot().metadata().compatibility().equals(compatibility),"Editing a supported block retains warning provenance");
                var placement=new Placement(UUID.randomUUID(),"test","future.litematic",new PlacementTransform(Vec3i.ZERO,0,false,false),true,false);
                var draft=DraftStore.decode(DraftStore.encode(new DraftStore.Draft("compatibility",placement,edits.checkpoint())));
                check(draft.checkpoint().metadata().compatibility().equals(compatibility),"Recovery draft preserves compatibility warning");
                Path edited=directory.resolve("edited.litematic");SchematicPatchIO.write(source,edited,edits.snapshot(),Cancellation.NEVER);
                var root=NbtReader.read(edited,SchematicImporter.SOURCE_LIMITS,Cancellation.NEVER);var palette=(List<?>)compound(compound(root.get("Regions")).get("main")).get("BlockStatePalette");
                check(compound(palette.get(2)).get("Properties").equals(Map.of("future_payload",List.of(1,2,3))),"Editing supported cells preserves unknown raw source entries");
            }
            check(load(source,cacheDir).cacheHit(),"Warm import reuses compatible cache");
            try(var warm=BlueprintCache.open(imported.path())){check(warm.metadata().compatibility().skippedBlockTypes()==2,"Warning survives cache reopen");}
            var document=SchematicDocument.read(source,Cancellation.NEVER);var part=document.parts().get(0);
            check(part.nonAir().count()==1&&part.blockEntities().isEmpty()&&part.blockTicks().isEmpty()&&part.fluidTicks().isEmpty(),"Creative paste index and auxiliary queues exclude discarded cells");
            check(part.entities().size()==1,"Document skips unknown entity type");
            var geometry=SchematicPreview.readGeometry(future(true),Cancellation.NEVER);
            check(geometry.blocks()==1&&geometry.palette().equals(List.of(BlockStateSpec.parse("stone"))),"Browser preview and block total match filtered projection");
            check(SourceVersions.requiresFiltering(future(true),Cancellation.NEVER),"Foreign embedded preview must be regenerated");
            check(registry.fixes==0,"No reverse DataFixer or parsing of future block payloads");
            String key=SourceVersions.previewKey(original);check(key.matches("[a-f0-9]{64}")&&!key.equals(original),"Preview cache keys include registry and remain valid SHA-256 keys");
            var previewCache=new PreviewCache(directory.resolve("preview-cache"));var images=SchematicPreview.render(geometry,new int[]{0xffcccccc},Cancellation.NEVER);previewCache.write(key,images,Cancellation.NEVER);check(previewCache.read(key,Cancellation.NEVER).blocks()==1,"Filtered preview cache roundtrip");
            var plain=Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,4,1,1,i->1)),"known");
            for(int format:new int[]{5,6,7,8}){
                plain.put("Version",format);plain.put("MinecraftDataVersion",3465);Path path=directory.resolve("format-"+format+".litematic");Fixtures.write(path,plain);
                try(var cache=BlueprintCache.open(load(path,cacheDir).path())){check(cache.metadata().compatibility().incompatible()==(format>6)&&cache.copyBlockStateCounts()[1]==4,"Compatible packed block layout accepted for format "+format);}
            }
            check(!SourceVersions.requiresFiltering(plain,Cancellation.NEVER),"Known-block sources can keep their embedded preview");
            plain.put("Version",6);plain.put("MinecraftDataVersion",9999);Path newGame=directory.resolve("new-game.litematic");Fixtures.write(newGame,plain);
            try(var cache=BlueprintCache.open(load(newGame,cacheDir).path())){check(cache.metadata().compatibility().newerGame()&&!cache.metadata().compatibility().newerFormat()&&cache.metadata().compatibility().skippedBlockTypes()==0,"Newer game with supported blocks is preserved and marked");}
            var old=Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,1,1,1,i->1)),"old");old.put("MinecraftDataVersion",2000);var oldRegion=compound(compound(old.get("Regions")).get("main"));oldRegion.put("BlockStatePalette",List.of(Fixtures.state("minecraft:air"),Fixtures.state("minecraft:old_stone")));old.put("Regions",Map.of("main",oldRegion));Path oldFile=directory.resolve("old.litematic");Fixtures.write(oldFile,old);
            try(var cache=BlueprintCache.open(load(oldFile,cacheDir).path())){check(!cache.metadata().compatibility().incompatible()&&cache.metadata().palette().get(1).equals(BlockStateSpec.parse("stone")),"Old renamed block still upgrades before registry validation");}
            check(registry.fixes>0,"Old game data continues through normal DataFixer");
            var futureBlock=Fixtures.litematic(Map.of("main",Fixtures.region(0,0,0,1,1,1,i->1)),"registry");var r=compound(compound(futureBlock.get("Regions")).get("main"));r.put("BlockStatePalette",List.of(Fixtures.state("minecraft:air"),Fixtures.state("minecraft:future_block")));futureBlock.put("Regions",Map.of("main",r));Path registryFile=directory.resolve("registry.litematic");Fixtures.write(registryFile,futureBlock);var before=load(registryFile,cacheDir);
            SourceVersions.install(new Registry(true));var after=load(registryFile,cacheDir);check(!after.cacheHit()&&!after.path().equals(before.path()),"Installing a mod that supplies a block invalidates filtered source cache");
            try(var cache=BlueprintCache.open(after.path())){check(!cache.metadata().compatibility().incompatible()&&cache.metadata().palette().get(1).name().equals("minecraft:future_block"),"Available modded block is preserved regardless of source version");}
            check(!key.equals(SourceVersions.previewKey(original)),"Registry change also invalidates thumbnail and orbit caches");SourceVersions.install(registry);
            for(int version:new int[]{2,3}){
                var sponge=Fixtures.sponge(version,2,1,1,i->i==0?1:2,new int[]{0,1,2,3});var holder=version==3?compound(compound(sponge.get("Schematic")).get("Blocks")):sponge;var palette=compound(holder.get("Palette"));palette.remove("minecraft:glass");palette.put("minecraft:future_block",2);holder.put("Palette",palette);
                if(version==3){var main=compound(sponge.get("Schematic"));main.put("Blocks",holder);sponge.put("Schematic",main);}Path path=directory.resolve("sponge-"+version+".schem");Fixtures.write(path,sponge);
                try(var cache=BlueprintCache.open(load(path,cacheDir).path())){check(cache.metadata().compatibility().skippedBlockTypes()==1&&cache.metadata().palette().contains(SourceVersions.SKIPPED),"Sponge "+version+" shares unknown-block filtering");}
            }
            var bad=future(false);var badRegion=compound(compound(bad.get("Regions")).get("main"));badRegion.put("BlockStates",new long[0]);bad.put("Regions",Map.of("main",badRegion));Path broken=directory.resolve("broken.litematic");Fixtures.write(broken,bad);rejects(broken,cacheDir);
            bad=future(false);badRegion=compound(compound(bad.get("Regions")).get("main"));badRegion.put("Size",Fixtures.xyz(Integer.MAX_VALUE,2,2));bad.put("Regions",Map.of("main",badRegion));Fixtures.write(broken,bad);rejects(broken,cacheDir);
            bad=future(false);badRegion=compound(compound(bad.get("Regions")).get("main"));badRegion.remove("BlockStatePalette");bad.put("Regions",Map.of("main",badRegion));Fixtures.write(broken,bad);rejects(broken,cacheDir);
            try{SchematicImporter.importFile(source,directory.resolve("cancelled"),()->true,p->{});throw new AssertionError("Cancellation ignored");}catch(InterruptedIOException expected){checks++;}
            check(SchematicImporter.sha256(source,Cancellation.NEVER).equals(original),"Source file remains byte-identical after every operation");
            return checks;
        }finally{field.set(null,previous);}
    }
}
