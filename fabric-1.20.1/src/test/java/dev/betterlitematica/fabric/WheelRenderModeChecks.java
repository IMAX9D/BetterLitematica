package dev.betterlitematica.fabric;

import dev.betterlitematica.core.LayerRange;
import dev.betterlitematica.core.Vec3i;
import java.nio.file.Files;
import java.io.IOException;
import java.util.EnumSet;

/** Production mode math plus actual preference-file migration; no client or world is created. */
public final class WheelRenderModeChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,message);}
    public static int run() throws Exception {
        checks=0;
        check(WheelRenderMode.values().length==7,"Seven distinct wheel render modes");
        var editable=EnumSet.of(WheelRenderMode.SINGLE,WheelRenderMode.ABOVE,WheelRenderMode.BELOW,WheelRenderMode.RANGE);
        for(var mode:WheelRenderMode.values()){
            check(mode.editable()==editable.contains(mode),"Only fixed modes expose coordinate input: "+mode);
            check(mode.follows()==(mode==WheelRenderMode.PLAYER_ABOVE||mode==WheelRenderMode.PLAYER_BELOW),"Follow semantics separate from fixed modes: "+mode);
        }
        check(WheelRenderMode.step(false,false)==1,"Unmodified step is one");
        check(WheelRenderMode.step(true,false)==5,"Control step is five");
        check(WheelRenderMode.step(false,true)==10,"Shift step is ten");
        check(WheelRenderMode.step(true,true)==20,"Combined modifiers step is twenty");
        for(int y:new int[]{-30_000_000,-64,-39,-1,0,1,320,30_000_000}){
            check(WheelRenderMode.coordinate(Integer.toString(y))==y,"Signed layer coordinate roundtrip: "+y);
            var single=WheelRenderMode.SINGLE.range(y,999,7);
            check(single.axis()==LayerRange.Axis.Y&&single.min()==y&&single.max()==y,"Single ignores stale second value and player Y");
            check(single.contains(new Vec3i(-20,y,20)),"Single includes its exact layer");
            check(!single.contains(new Vec3i(0,y-1,0))&&!single.contains(new Vec3i(0,y+1,0)),"Single excludes both adjacent layers");
            var above=WheelRenderMode.ABOVE.range(y,0,7);var below=WheelRenderMode.BELOW.range(y,0,7);
            check(above.contains(new Vec3i(0,y,0))&&above.contains(new Vec3i(0,y+1,0))&&!above.contains(new Vec3i(0,y-1,0)),"Above includes boundary and excludes lower layer");
            check(below.contains(new Vec3i(0,y,0))&&below.contains(new Vec3i(0,y-1,0))&&!below.contains(new Vec3i(0,y+1,0)),"Below includes boundary and excludes upper layer");
            check(WheelRenderMode.PLAYER_ABOVE.range(121,122,y).equals(above),"Player-above uses current signed player layer");
            check(WheelRenderMode.PLAYER_BELOW.range(121,122,y).equals(below),"Player-below uses current signed player layer");
        }
        check(WheelRenderMode.coordinate(" \t-39 \n")==-39,"Coordinate trims surrounding whitespace");
        check(WheelRenderMode.coordinate("+12")==12,"Explicit positive coordinate accepted");
        for(var bad:new String[]{""," ","-","1.5","NaN","1e3","30000001","-30000001","2147483648","-2147483649","1 2"})
            rejects(()->WheelRenderMode.coordinate(bad),"Reject malformed/out-of-world layer: "+bad);
        var all=WheelRenderMode.ALL.range(Integer.MAX_VALUE,Integer.MIN_VALUE,-39);
        check(all.equals(LayerRange.ALL),"All ignores dormant inputs and removes all limits");
        check(all.contains(new Vec3i(0,Integer.MIN_VALUE,0))&&all.contains(new Vec3i(0,Integer.MAX_VALUE,0)),"All retains sentinel-inclusive semantics");
        var range=WheelRenderMode.RANGE.range(-39,-20,500);
        check(range.min()==-39&&range.max()==-20&&range.axis()==LayerRange.Axis.Y,"Range keeps negative ordered endpoints");
        check(range.contains(new Vec3i(0,-39,0))&&range.contains(new Vec3i(0,-20,0))&&!range.contains(new Vec3i(0,-40,0))&&!range.contains(new Vec3i(0,-19,0)),"Both range endpoints inclusive");
        rejects(()->WheelRenderMode.RANGE.range(2,1,0),"Reversed range rejected without normalization");
        rejects(()->WheelRenderMode.RANGE.range(-30_000_001,0,0),"Range first endpoint limit validated");
        rejects(()->WheelRenderMode.RANGE.range(0,30_000_001,0),"Range second endpoint limit validated");
        var same=WheelRenderMode.RANGE.range(-39,-39,0);
        check(same.mode()==LayerRange.Mode.SINGLE,"Core representation intentionally collapses equal endpoints");
        check(WheelRenderMode.selected(same,false,WheelRenderMode.RANGE)==WheelRenderMode.RANGE,"Preferred RANGE survives equal endpoint representation");
        check(WheelRenderMode.selected(same,false,WheelRenderMode.SINGLE)==WheelRenderMode.SINGLE,"Explicit legacy single selection overrides range preference");
        check(WheelRenderMode.selected(range,true,WheelRenderMode.PLAYER_ABOVE)==WheelRenderMode.RANGE,"Legacy follow-range remains range, not player-above");
        check(WheelRenderMode.selected(all,true,WheelRenderMode.PLAYER_BELOW)==WheelRenderMode.ALL,"All wins over stale follow preference");
        for(var mode:new WheelRenderMode[]{WheelRenderMode.ABOVE,WheelRenderMode.BELOW}){
            var fixed=mode.range(-39,0,0);
            var expected=mode==WheelRenderMode.ABOVE?WheelRenderMode.PLAYER_ABOVE:WheelRenderMode.PLAYER_BELOW;
            check(WheelRenderMode.selected(fixed,true,WheelRenderMode.ALL)==expected,"Y follow maps to matching player mode");
            check(WheelRenderMode.selected(fixed,false,expected)==mode,"Fixed mode wins over stale player preference");
            for(var axis:new LayerRange.Axis[]{LayerRange.Axis.X,LayerRange.Axis.Z}){
                var legacy=new LayerRange(axis,fixed.min(),fixed.max());
                check(WheelRenderMode.selected(legacy,true,expected)==mode,"Legacy non-Y follow is never misidentified as player Y");
                check(legacy.axis()==axis,"Reading mode does not mutate legacy axis");
            }
        }
        for(var mode:editable)for(int step:new int[]{-20,-10,-5,-1,1,5,10,20}){
            var shifted=WheelRenderMode.shifted(mode,-39,-20,step);
            check(shifted.first()==-39+step,"Signed step applies to first coordinate");
            check(shifted.second()==(mode==WheelRenderMode.RANGE?-20+step:-39+step),"Range moves together; single-value modes normalize second");
            if(mode==WheelRenderMode.RANGE)check(shifted.second()-shifted.first()==19,"Range width is preserved under both directions");
        }
        var original=new WheelRenderMode.Coordinates(29_999_999,30_000_000);
        rejects(()->WheelRenderMode.shifted(WheelRenderMode.RANGE,original.first(),original.second(),1),"Second endpoint overflow rejects complete range shift");
        check(original.first()==29_999_999&&original.second()==30_000_000,"Rejected shift cannot partially mutate immutable coordinates");
        rejects(()->WheelRenderMode.shifted(WheelRenderMode.RANGE,-30_000_000,-29_999_999,-1),"First endpoint underflow rejected");
        rejects(()->WheelRenderMode.shifted(WheelRenderMode.RANGE,0,1,Integer.MAX_VALUE),"Large positive step cannot int-wrap");
        rejects(()->WheelRenderMode.shifted(WheelRenderMode.SINGLE,-1,-1,Integer.MIN_VALUE),"Large negative step cannot int-wrap");
        rejects(()->WheelRenderMode.shifted(WheelRenderMode.RANGE,5,4,1),"Invalid original range is rejected before shifting");
        for(var mode:new WheelRenderMode[]{WheelRenderMode.ALL,WheelRenderMode.PLAYER_ABOVE,WheelRenderMode.PLAYER_BELOW})
            rejects(()->WheelRenderMode.shifted(mode,0,0,1),"Non-editable mode cannot shift: "+mode);
        rejects(()->WheelRenderMode.shifted(null,0,0,1),"Missing mode cannot shift");
        var path=Files.createTempFile("betterlitematica-wheel-mode-",".json");
        try {
            Files.writeString(path,"{\"version\":1,\"followLayer\":true,\"unrelatedSetting\":{\"keep\":17}}");
            var legacy=InteractionOptions.read(path);
            check(legacy.followLayer&&legacy.wheelRenderMode==WheelRenderMode.ALL,"Legacy preference file retains follow and receives safe missing-field default");
            check(WheelRenderMode.selected(WheelRenderMode.ABOVE.range(-39,0,0),legacy.followLayer,legacy.wheelRenderMode)==WheelRenderMode.PLAYER_ABOVE,"Old saved Y-above plus follow migrates through effective mode");
            for(var mode:WheelRenderMode.values()){
                legacy.wheelRenderMode=mode;legacy.followLayer=mode.follows();InteractionOptions.write(path,legacy.snapshot());var restored=InteractionOptions.read(path);
                check(restored.wheelRenderMode==mode&&restored.followLayer==mode.follows(),"Actual options file roundtrip: "+mode);
                check(restored.snapshot().getAsJsonObject("unrelatedSetting").get("keep").getAsInt()==17,"Wheel settings preserve unrelated JSON fields");
            }
            Files.writeString(path,"{\"version\":1,\"wheelRenderMode\":\"UNKNOWN_MODE\"}");
            boolean rejected=false;try{InteractionOptions.read(path);}catch(IOException expected){rejected=true;}
            check(rejected,"Corrupt persisted mode is reported rather than silently enabling other rendering");
            check(Files.readString(path).contains("UNKNOWN_MODE"),"Reading bad settings never overwrites the source file");
        } finally {Files.deleteIfExists(path);}
        return checks;
    }
}
