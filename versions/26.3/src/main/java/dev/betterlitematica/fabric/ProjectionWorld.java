package dev.betterlitematica.fabric;

import net.minecraft.world.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import java.util.*;

/** Detached read-only rendering world. It never exposes the real world's chunks or entities. */
final class ProjectionWorld extends Level implements BlockAndTintGetter {
    private final net.minecraft.world.level.border.WorldBorder border=new net.minecraft.world.level.border.WorldBorder();
    @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder(){return border;}
    private final ClientLevel base;private BlockAndTintGetter view;private java.util.function.Function<BlockPos,BlockEntity> blockEntities=p->null;
    private final net.minecraft.world.scores.Scoreboard scoreboard=new net.minecraft.world.scores.Scoreboard();
    private final LevelEntityGetter<Entity> entities=new LevelEntityGetter<>(){
        public Entity get(int id){return null;}public Entity get(UUID id){return null;}public Iterable<Entity> getAll(){return List.of();}
        public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity,U> type,net.minecraft.util.AbortableIterationConsumer<U> action){}
        public void get(AABB box,java.util.function.Consumer<Entity> action){}
        public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity,U> type,AABB box,net.minecraft.util.AbortableIterationConsumer<U> action){}
    };
    private final ChunkSource chunks=new ChunkSource(){
        public BlockGetter getLevel(){return ProjectionWorld.this;}
        public ChunkAccess getChunk(int x,int z,ChunkStatus status,boolean create){return null;}
        public void tick(java.util.function.BooleanSupplier time,boolean tick){}public String gatherStats(){return "projection";}public int getLoadedChunksCount(){return 0;}
        public LevelLightEngine getLightEngine(){return base.getLightEngine();}
    };
    ProjectionWorld(ClientLevel base){super(new ClientLevel.ClientLevelData(base.getDifficulty(),false,false),base.dimension(),base.registryAccess(),base.dimensionTypeRegistration(),true,false,0,0);this.base=base;}
    void view(BlockAndTintGetter view,java.util.function.Function<BlockPos,BlockEntity> blockEntities){this.view=view;this.blockEntities=blockEntities;}
    @Override public BlockState getBlockState(BlockPos pos){return view==null?Blocks.AIR.defaultBlockState():view.getBlockState(pos);}
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){return blockEntities.apply(pos);}
    @Override public boolean setBlock(BlockPos p,BlockState state,int flags,int depth){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void setBlockEntity(BlockEntity entity){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void removeBlockEntity(BlockPos pos){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public boolean hasChunk(int x,int z){return true;}
    @Override public BlockGetter getChunkForCollisions(int x,int z){return this;}
    @Override public ChunkSource getChunkSource(){return chunks;}
    @Override public LevelTickAccess<Block> getBlockTicks(){return BlackholeTickAccess.emptyLevelList();}
    @Override public LevelTickAccess<Fluid> getFluidTicks(){return BlackholeTickAccess.emptyLevelList();}
    @Override public net.minecraft.world.level.CardinalLighting cardinalLighting(){return base.cardinalLighting();}
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){return base.getBlockTint(pos,resolver);}
    @Override public int getBrightness(LightLayer type,BlockPos pos){return 15;}
    @Override public int getRawBrightness(BlockPos pos,int ambient){return 15;}
    @Override public long getGameTime(){return 0;}

    @Override public Holder<Biome> getUncachedNoiseBiome(int x,int y,int z){return base.getUncachedNoiseBiome(x,y,z);}
    @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures(){return base.enabledFeatures();}
    @Override public List<? extends Player> players(){return List.of();}
    @Override public void sendBlockUpdated(BlockPos p,BlockState before,BlockState after,int flags){}
    @Override public void playSeededSound(Entity p,double x,double y,double z,Holder<SoundEvent> event,SoundSource cat,float volume,float pitch,long seed){}
    @Override public void playSeededSound(Entity p,Entity entity,Holder<SoundEvent> event,SoundSource cat,float volume,float pitch,long seed){}
    @Override public void levelEvent(Entity p,int event,BlockPos pos,int data){}
    @Override public void gameEvent(Holder<GameEvent> event,Vec3 pos,GameEvent.Context emitter){}
    @Override public String gatherChunkSourceStats(){return "BetterLitematica projection";}
    @Override public Entity getEntity(int id){return null;}
    @Override public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(net.minecraft.world.level.saveddata.maps.MapId id){return base.getMapData(id);}


    @Override public void destroyBlockProgress(int id,BlockPos pos,int progress){}
    @Override public net.minecraft.world.scores.Scoreboard getScoreboard(){return scoreboard;}
    @Override public net.minecraft.world.item.crafting.RecipeAccess recipeAccess(){return base.recipeAccess();}
    @Override protected LevelEntityGetter<Entity> getEntities(){return entities;}
    @Override public int getSeaLevel(){return base.getSeaLevel();}
    @Override public java.util.Collection<net.minecraft.world.entity.boss.enderdragon.EnderDragonPart> dragonParts(){return List.of();}
    @Override public net.minecraft.world.TickRateManager tickRateManager(){return base.tickRateManager();}
    @Override public net.minecraft.world.clock.ClockManager clockManager(){return base.clockManager();}
    @Override public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes(){return base.environmentAttributes();}
    @Override public net.minecraft.world.level.storage.LevelData.RespawnData getRespawnData(){return base.getRespawnData();}
    @Override public void setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData data){throw new UnsupportedOperationException("Read-only projection world");}
    @Override public void explode(Entity entity,net.minecraft.world.damagesource.DamageSource source,net.minecraft.world.level.ExplosionDamageCalculator calculator,double x,double y,double z,float radius,boolean fire,Level.ExplosionInteraction interaction,net.minecraft.core.particles.ParticleOptions small,net.minecraft.core.particles.ParticleOptions large,net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> particles,Holder<SoundEvent> sound){throw new UnsupportedOperationException("Read-only projection world");}
}
