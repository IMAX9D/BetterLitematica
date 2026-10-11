package dev.betterlitematica.fabric;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;

/** Executable adapter check. No game launch or runtime test dependency is needed. */
public final class SettingsIntegrityCheck {
    public static void main(String[] args)throws Exception {
        verifyDraftComparison();
        var ids=EnumSet.allOf(SettingId.class);
        for(var id:ids){var definition=SettingHelp.require(id);check(!definition.label().isBlank()&&!definition.description().isBlank(),"Missing help: "+id);}
        for(var id:PrinterReason.Id.values()){
            var first=PrinterReason.of(id,"原来的说明");var renamed=PrinterReason.of(id,"任意新措辞：高度、缺少、延迟、侦测器");
            check(first.shortLabel().equals(renamed.shortLabel()),"Reason depends on wording: "+id);
            check(first.missing()==renamed.missing(),"Missing classification depends on wording: "+id);
            if(id!=PrinterReason.Id.NONE&&id!=PrinterReason.Id.RUNNING&&id!=PrinterReason.Id.STOPPED&&id!=PrinterReason.Id.PAUSED)check(!first.shortLabel().isBlank(),"Invisible operational state: "+id);
        }
        check(PrinterReason.of(PrinterReason.Id.CONFIRMING,"等待确认").shortLabel().equals("等待确认"),"Normal acknowledgement must not claim excess latency");
        check(!PrinterReason.PAUSED.blocked(),"Ordinary pause must keep the compact paused badge");
        check(!PrinterReason.of(PrinterReason.Id.NO_WORK,"没有工作").shortLabel().equals(PrinterReason.Id.OUT_OF_REACH.shortLabel()),"Empty queue is not evidence of unreachable work");
        check(PrinterReason.failure(new IllegalStateException("internal implementation detail")).equals(PrinterReason.ERROR),"Unknown exception leaked to players");
        var supplied=PrinterReason.of(PrinterReason.Id.SUPPLY_INTERRUPTED,"补给已中断");
        check(PrinterReason.failure(new PrinterReason.Failure(supplied)).equals(supplied),"Typed failure lost its cause");
        Path source=Path.of(args[0]);var compiler=ToolProvider.getSystemJavaCompiler();check(compiler!=null,"JDK compiler required");
        var files=List.of("PrinterScreen.java","BedrockScreen.java","OptionsScreen.java","DisplayScreen.java").stream().map(source::resolve).map(Path::toFile).toList();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            int[] checked={0};
            for(var unit:task.parse())new TreePathScanner<Void,Void>(){
                @Override public Void visitMethodInvocation(MethodInvocationTree call,Void unused){
                    String method=name(call);
                    boolean ownFactory=call.getMethodSelect() instanceof IdentifierTree||call.getMethodSelect() instanceof MemberSelectTree member&&Set.of("this","super").contains(member.getExpression().toString());
                    if(ownFactory&&Set.of("button","buttonAt","field","fieldAt").contains(method)){
                        Tree parent=getCurrentPath().getParentPath().getLeaf();
                        check(parent instanceof MethodInvocationTree outer&&Set.of("setting","settingAction").contains(name(outer)),"Control lacks an explicit setting ID or action declaration: "+unit.getSourceFile().getName()+" / "+call);
                        checked[0]++;
                    }
                    return super.visitMethodInvocation(call,unused);
                }
            }.scan(unit,null);
            for(var diagnostic:diagnostics.getDiagnostics())check(diagnostic.getKind()!=Diagnostic.Kind.ERROR,"Invalid settings source: "+diagnostic);
            check(checked[0]>0,"No settings controls were inspected");
            System.out.println("Settings verified: "+ids.size()+" complete descriptions, "+checked[0]+" explicitly bound controls, "+PrinterReason.Id.values().length+" wording-independent reason IDs.");
        }
    }
    private static void verifyDraftComparison()throws Exception {
        // The review overlay owns this fix until promotion; other adapters still use their original settings.
        java.lang.reflect.Method printerDraft,bedrockDraft;
        try{printerDraft=PrinterSettings.class.getDeclaredMethod("draftSnapshot");bedrockDraft=BedrockSettings.class.getDeclaredMethod("draftSnapshot");}
        catch(NoSuchMethodException notPromoted){return;}
        var printer=new PrinterSettings();var saved=printer.snapshot();
        for(int millis:new int[]{0,1,15,99,10001}){
            printer.highlightMillis=millis;
            check(!printerDraft.invoke(printer).equals(saved),"Invalid printer draft must remain comparable");
            rejects(printer::snapshot,"Invalid highlight duration was saved");
        }
        printer.highlightMillis=1500;
        check(printerDraft.invoke(printer).equals(saved),"Restoring a field must clear its draft difference");
        for(double range:new double[]{-1,257,Double.NaN,Double.POSITIVE_INFINITY}){
            printer.range=range;printerDraft.invoke(printer);
            rejects(printer::snapshot,"Invalid printer range was saved");
        }
        var bedrock=new BedrockSettings();var bedrockSaved=bedrock.snapshot();
        for(int timeout:new int[]{0,1,19,1201}){
            bedrock.timeoutTicks=timeout;
            check(!bedrockDraft.invoke(bedrock).equals(bedrockSaved),"Invalid bedrock draft must remain comparable");
            rejects(bedrock::snapshot,"Invalid bedrock timeout was saved");
        }
        bedrock.timeoutTicks=120;bedrock.breakDirections.clear();bedrockDraft.invoke(bedrock);
        rejects(bedrock::snapshot,"Empty bedrock directions were saved");
        System.out.println("Draft regression: incomplete printer and bedrock values remain editable; invalid saves are rejected.");
    }
    private static String name(MethodInvocationTree call){var method=call.getMethodSelect();return method instanceof MemberSelectTree member?member.getIdentifier().toString():method.toString();}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(message);}
}
