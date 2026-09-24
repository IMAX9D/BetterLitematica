package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Blocks;
import java.util.function.Function;

/** Raw per-region edit state and the verifier's actual filtering predicate. */
public final class SubregionAndSearchChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){checks=0;
        var drafts=new SubregionScreen.CoordinateDrafts();var a=new Vec3i(10,20,30);var b=new Vec3i(-4,5,6);
        check(drafts.value("A",0,a).equals("10"),"Initial committed coordinate populates draft");
        drafts.set("A",0,"00123");drafts.set("A",1,"-");drafts.set("A",2,"");
        check(drafts.value("B",0,b).equals("-4"),"Another region has independent initial coordinate");drafts.set("B",0,"88");
        for(int rebuild=0;rebuild<8;rebuild++){
            check(drafts.value("A",0,new Vec3i(900,901,902)).equals("00123"),"Rebuild/other transforms preserve exact unsubmitted X text");
            check(drafts.value("A",1,a).equals("-")&&drafts.value("A",2,a).isEmpty(),"Intermediate invalid text survives region switches and rebuilds");
            check(drafts.value("B",0,b).equals("88"),"Returning to B preserves its own draft");
        }
        try{drafts.position("A");throw new AssertionError("Invalid draft parsed");}catch(NumberFormatException expected){checks++;}
        try{drafts.replaceAfter("A",()->{throw new IllegalStateException("locked");});throw new AssertionError("Failed action accepted");}catch(IllegalStateException expected){checks++;}
        check(drafts.value("A",0,a).equals("00123")&&drafts.value("A",1,a).equals("-"),"Failed coordinate action preserves draft");
        drafts.set("A",1,"-90");drafts.set("A",2,"12");check(drafts.position("A").equals(new Vec3i(123,-90,12)),"Manual apply parses all three raw fields");
        final Vec3i[] applied={null};drafts.replaceAfter("A",()->applied[0]=drafts.position("A"));check(applied[0].equals(new Vec3i(123,-90,12)),"Successful action reads draft before replacement");
        check(drafts.value("A",0,applied[0]).equals("123"),"Successful apply refreshes committed canonical text");
        drafts.set("A",0,"999");drafts.replaceAfter("A",()->{});check(drafts.value("A",0,new Vec3i(40,50,60)).equals("40"),"Successful player-position action refreshes selected region");
        drafts.set("A",0,"777");drafts.replaceAfter("A",()->{});check(drafts.value("A",0,a).equals("10"),"Successful reset refreshes original coordinates");check(drafts.value("B",0,b).equals("88"),"Coordinate actions never erase another region's draft");

        var key=new VerificationReport.Key(Comparison.WRONG_STATE,"Block{minecraft:oak_stairs}[facing=north,half=top]","Block{minecraft:glass}");
        Function<String,String> chinese=s->s.equals(key.expected())?"橡木楼梯":s.equals(key.actual())?"玻璃":"未知";
        check(AnalysisScreen.matchesVerification(key,"橡木",chinese),"Expected localized block name searchable");
        check(AnalysisScreen.matchesVerification(key,"玻璃",chinese),"Actual localized block name searchable");
        check(AnalysisScreen.matchesVerification(key,"MINECRAFT:OAK_STAIRS",chinese),"Namespaced expected ID remains case-insensitive");
        check(AnalysisScreen.matchesVerification(key,"glass",chinese),"Actual ID remains searchable");
        check(AnalysisScreen.matchesVerification(key,"FACING=NORTH",chinese),"Block-state property remains searchable");
        check(AnalysisScreen.matchesVerification(key,"状态错误",chinese),"Localized mismatch type remains searchable");
        check(AnalysisScreen.matchesVerification(key,"wrong_state",chinese),"Raw mismatch type remains searchable");
        check(!AnalysisScreen.matchesVerification(key,"海晶灯",chinese),"Unrelated localized query excluded");
        check(AnalysisScreen.matchesVerification(key," ",s->{throw new AssertionError("Blank query should not resolve names");}),"Blank query includes all without name work");
        check(AnalysisScreen.localizedStateName("Block{minecraft:oak_stairs}[facing=north]").equals(Blocks.OAK_STAIRS.getName().getString()),"Runtime block-state form resolves actual localized block name");
        check(AnalysisScreen.localizedStateName("minecraft:glass").equals(Blocks.GLASS.getName().getString()),"Plain registry ID resolves localized block name");
        check(AnalysisScreen.localizedStateName("missing:block[facing=north]").equals("missing:block[facing=north]"),"Unknown registry state retained for searching");
        check(AnalysisScreen.localizedStateName("").equals("未知"),"Absent state has explicit fallback");
        return checks;
    }
}
