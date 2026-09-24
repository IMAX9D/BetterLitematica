package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Creative fill / replace / delete over an immutable selection snapshot. */
final class CreativeFillTask {
    private final ServerWorld world;private final UUID player;private final RegionCursor cursor;private final StateResolver1201 states;
    private final boolean replace;private final CompletableFuture<String> result=new CompletableFuture<>();
    private volatile boolean cancelled,paused;private volatile String status="准备填充";private long changed;
    CreativeFillTask(ServerWorld world,UUID player,List<SelectionBox> boxes,BlockStateSpec replacement,BlockStateSpec match){
        this.world=world;this.player=player;var regions=boxes.stream().map(SelectionBox::region).toList();
        if(regions.isEmpty()||regions.stream().mapToLong(Region::volume).sum()>16_777_216)throw new IllegalArgumentException("选区为空或超过填充预算");
        for(var r:regions)if(r.min().y()<world.getBottomY()||(long)r.min().y()+r.size().y()>world.getTopY())throw new IllegalArgumentException("选区超出世界高度");
        cursor=new RegionCursor(regions);replace=match!=null;states=new StateResolver1201(match==null?List.of(replacement):List.of(replacement,match),new PlacementTransform(Vec3i.ZERO,0,false,false));
        if(states.unresolved(0)||replace&&states.unresolved(1))throw new IllegalArgumentException("未知方块状态");
    }
    CompletableFuture<String> result(){return result;}String status(){return status;}void cancel(){cancelled=true;}void pause(){paused=!paused;}
    void tick(){
        if(result.isDone())return;if(cancelled){result.complete("已取消，已写入方块保留");return;}if(paused){status="填充已暂停";return;}
        var actor=world.getServer().getPlayerManager().getPlayer(player);if(actor==null||!actor.isCreative()||actor.getServerWorld()!=world){result.completeExceptionally(new IllegalStateException("需要在同一世界保持创造模式"));return;}
        long until=System.nanoTime()+2_000_000;int budget=512;
        try{while(!cursor.done()&&budget-->0&&System.nanoTime()<until){Vec3i p=cursor.local();BlockPos pos=new BlockPos(p.x(),p.y(),p.z());if(!world.isChunkLoaded(pos)){status="填充等待区块 "+(p.x()>>4)+","+(p.z()>>4);return;}if(!world.getWorldBorder().contains(pos))throw new IllegalStateException("选区超出世界边界");
            var current=world.getBlockState(pos);if((!replace||current.equals(states.resolve(1)))&&!current.equals(states.resolve(0))){world.setBlockState(pos,states.resolve(0),Block.NOTIFY_LISTENERS|Block.FORCE_STATE|Block.SKIP_DROPS);changed++;}cursor.advance();}
            status="已检查 "+cursor.processed()+" 格，修改 "+changed;if(cursor.done())result.complete("填充完成："+status);
        }catch(RuntimeException e){result.completeExceptionally(e);}
    }
}
