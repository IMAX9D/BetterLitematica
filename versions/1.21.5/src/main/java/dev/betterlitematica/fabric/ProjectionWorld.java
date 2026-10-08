package dev.betterlitematica.fabric;

import net.minecraft.world.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.*;
import net.minecraft.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.entity.EntityLookup;
import net.minecraft.world.tick.*;
import net.minecraft.world.biome.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.*;
import net.minecraft.world.event.GameEvent;
import java.util.*;

/** Detached read-only rendering world. It never exposes the real world's chunks or entities. */
final class ProjectionWorld extends World {
    private final ClientWorld base;private BlockRenderView view;private java.util.function.Function<BlockPos,BlockEntity> blockEntities=p->null;
    private final net.minecraft.scoreboard.Scoreboard scoreboard=new net.minecraft.scoreboard.Scoreboard();
    private final EntityLookup<Entity> entities=new EntityLookup<>(){
        public Entity get(int id){return null;}public Entity get(UUID id){return null;}public Iterable<Entity> iterate(){return List.of();}
        public <U extends Entity> void forEach(net.minecraft.util.TypeFilter<Entity,U> type,net.minecraft.util.function.LazyIterationConsumer<U> action){}
        public void forEachIntersects(Box box,java.util.function.Consumer<Entity> action){}
        public <U extends Entity> void forEachIntersects(net.minecraft.util.TypeFilter<Entity,U> type,Box box,net.minecraft.util.function.LazyIterationConsumer<U> action){}
    };
    private final ChunkManager chunks=new ChunkManager(){
        public BlockView getWorld(){return ProjectionWorld.this;}
        public Chunk getChunk(int x,int z,ChunkStatus status,boolean create){return null;}
        public void tick(java.util.function.BooleanSupplier time,boolean tick){}public String getDebugString(){return "projection";}public int getLoadedChunkCount(){return 0;}
        public LightingProvider getLightingProvider(){return base.getLightingProvider();}
    };
    @Override public net.minecraft.item.FuelRegistry getFuelRegistry(){return base.getFuelRegistry();}
    @Override public java.util.Collection<net.minecraft.entity.boss.dragon.EnderDragonPart> getEnderDragonParts(){return List.of();}
    @Override public int getSeaLevel(){return base.getSeaLevel();}
    ProjectionWorld(ClientWorld base){super(new ClientWorld.Properties(base.getDifficulty(),false,false),base.getRegistryKey(),base.getRegistryManager(),base.getDimensionEntry(),true,false,0,0);this.base=base;}
    @Override public TickManager getTickManager(){return base.getTickManager();}
    void view(BlockRenderView view,java.util.function.Function<BlockPos,BlockEntity> blockEntities){this.view=view;this.blockEntities=blockEntities;}
    @Override public BlockState getBlockState(BlockPos pos){return view==null?Blocks.AIR.getDefaultState():view.getBlockState(pos);}
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){return blockEntities.apply(pos);}
    @Override public boolean setBlockState(BlockPos p,BlockState state,int flags,int depth){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void addBlockEntity(BlockEntity entity){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void removeBlockEntity(BlockPos pos){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void createExplosion(Entity entity,net.minecraft.entity.damage.DamageSource damage,net.minecraft.world.explosion.ExplosionBehavior behavior,double x,double y,double z,float power,boolean fire,ExplosionSourceType source,net.minecraft.particle.ParticleEffect small,net.minecraft.particle.ParticleEffect large,RegistryEntry<SoundEvent> sound){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public boolean isChunkLoaded(int x,int z){return true;}
    @Override public BlockView getChunkAsView(int x,int z){return this;}
    @Override public ChunkManager getChunkManager(){return chunks;}
    @Override public QueryableTickScheduler<Block> getBlockTickScheduler(){return EmptyTickSchedulers.getClientTickScheduler();}
    @Override public QueryableTickScheduler<Fluid> getFluidTickScheduler(){return EmptyTickSchedulers.getClientTickScheduler();}
    @Override public float getBrightness(Direction side,boolean shade){return base.getBrightness(side,shade);}
    @Override public int getColor(BlockPos pos,ColorResolver resolver){return base.getColor(pos,resolver);}
    @Override public int getLightLevel(LightType type,BlockPos pos){return 15;}
    @Override public int getBaseLightLevel(BlockPos pos,int ambient){return 15;}
    @Override public long getTime(){return 0;}
    @Override public long getTimeOfDay(){return base.getTimeOfDay();}
    @Override public RegistryEntry<Biome> getGeneratorStoredBiome(int x,int y,int z){return base.getGeneratorStoredBiome(x,y,z);}
    @Override public net.minecraft.resource.featuretoggle.FeatureSet getEnabledFeatures(){return base.getEnabledFeatures();}
    @Override public List<? extends PlayerEntity> getPlayers(){return List.of();}
    @Override public void updateListeners(BlockPos p,BlockState before,BlockState after,int flags){}
    @Override public void playSound(Entity p,double x,double y,double z,RegistryEntry<SoundEvent> event,SoundCategory cat,float volume,float pitch,long seed){}
    @Override public void playSoundFromEntity(Entity p,Entity entity,RegistryEntry<SoundEvent> event,SoundCategory cat,float volume,float pitch,long seed){}
    @Override public void syncWorldEvent(Entity p,int event,BlockPos pos,int data){}
    @Override public void emitGameEvent(RegistryEntry<GameEvent> event,Vec3d pos,GameEvent.Emitter emitter){}
    @Override public String asString(){return "BetterLitematica projection";}
    @Override public Entity getEntityById(int id){return null;}
    @Override public net.minecraft.item.map.MapState getMapState(net.minecraft.component.type.MapIdComponent id){return base.getMapState(id);}
    @Override public void setBlockBreakingInfo(int id,BlockPos pos,int progress){}
    @Override public net.minecraft.scoreboard.Scoreboard getScoreboard(){return scoreboard;}
    @Override public net.minecraft.recipe.BrewingRecipeRegistry getBrewingRecipeRegistry(){return base.getBrewingRecipeRegistry();}
    @Override public net.minecraft.recipe.RecipeManager getRecipeManager(){return base.getRecipeManager();}
    @Override protected EntityLookup<Entity> getEntityLookup(){return entities;}
}
