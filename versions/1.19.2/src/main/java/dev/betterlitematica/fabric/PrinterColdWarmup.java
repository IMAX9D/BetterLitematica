package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** One-time, read-only preparation before the player starts printing. */
final class PrinterColdWarmup {
    private static boolean dataReady,failed;
    private static int predictions;
    private PrinterColdWarmup(){}

    /** Called during mod initialization, outside a game tick and without a world. */
    static void initialize(){
        if(dataReady)return;
        var scan=new PrinterScan(Vec3i.ZERO,4,PrinterRange.Shape.SPHERE,"XZY",false,false,false);
        var queue=new PrinterQueue(64);
        var pacing=new PrinterPacing();
        var settings=new PrinterSettings();
        var policy=new PrinterDiscovery.Policy(true,false,false,false,false,false,false,false);
        var air=Blocks.AIR.getDefaultState();
        var concrete=Blocks.BROWN_CONCRETE.getDefaultState();
        var light=Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15,10);
        for(int page=0;page<256;page++){
            scan.beginTick(new Vec3i((page/8)&1,0,0),page);
            int n=32;long[] positions=new long[n];int[] expected=new int[n],actual=new int[n],flags=new int[n],scopes=new int[n],fill=new int[n];
            for(int i=0;i<n;i++){
                if(scan.hasNext())scan.next();
                positions[i]=((long)page<<8)+i;
                var wanted=(i&1)==0?concrete:light;
                expected[i]=Block.getRawIdFromState(wanted);actual[i]=Block.getRawIdFromState(air);
                flags[i]=PrinterDiscovery.KNOWN|PrinterDiscovery.ACTUAL_AIR|PrinterDiscovery.REPLACEABLE;scopes[i]=1;
                PrinterRules.adjustable(air,wanted,settings);PrinterRules.filtered(wanted,settings.skip);
            }
            var result=PrinterDiscovery.search(new PrinterDiscovery.Page(123,positions,expected,actual,flags,scopes,fill),policy);
            pacing.begin(page,0,0,0,0,1);
            for(var job:result.jobs())queue.offer(job,job.expected());
            for(PrinterQueue.Job job;(job=queue.pollBatch(expected[0]))!=null;)if(pacing.canDispatch(job.position(),false))pacing.dispatched(job.position(),false);
        }
        dataReady=true;
    }

    /** At most four vanilla read-only predictions per client tick. */
    static void step(MinecraftClient client){
        if(!dataReady||failed||predictions>=16||client.world==null||client.player==null||client.interactionManager==null)return;
        try{stepInternal(client);}catch(RuntimeException e){failed=true;BetterLitematicaClient.LOGGER.warn("Printer read-only placement preparation unavailable",e);}
    }
    private static void stepInternal(MinecraftClient client){
        BlockPos pos=null;var center=client.player.getBlockPos();
        for(int y=0;y<=3&&pos==null;y++)for(int x=-3;x<=3&&pos==null;x++)for(int z=-3;z<=3;z++){
            if(Math.abs(x)+Math.abs(z)<2)continue;
            var candidate=center.add(x,y,z);
            if(WorldChunks.loaded(client.world,candidate)&&client.world.getBlockState(candidate).isAir()){pos=candidate;break;}
        }
        if(pos==null)return;
        long started=System.nanoTime();int count=0;
        while(predictions<16&&count++<4&&System.nanoTime()-started<200_000L){
            boolean useLight=(predictions&1)!=0;
            var wanted=useLight?Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15,10):Blocks.BROWN_CONCRETE.getDefaultState();
            var stack=new ItemStack(wanted.getBlock());if(useLight)LegacyGame.light(stack,10);
            var hit=new BlockHitResult(Vec3d.ofCenter(pos).add(0,.5,0),Direction.UP,pos,false);
            var context=new ItemPlacementContext(client.player,Hand.MAIN_HAND,stack,hit);
            AccuratePlacement.predict(AccuratePlacement.resolve(client,AccuratePlacement.Mode.AUTO),context,wanted,hit);
            InventoryTransfers.matchesStack(stack,stack.copy());
            predictions++;
        }
    }

    static boolean ready(){return dataReady&&!failed&&predictions>=16;}
}
