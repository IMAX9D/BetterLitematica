package dev.betterlitematica.fabric;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/** Verifies effective adapters and compiled call sites, including late version overrides. */
public final class FeaturePromotionCheck {
    private static final String FABRIC="dev/betterlitematica/fabric/",IO="dev/betterlitematica/io/";
    private record Call(String owner,String name,String descriptor){}
    private static Set<Call> calls(String type)throws Exception {
        var calls=new HashSet<Call>();
        try(var input=FeaturePromotionCheck.class.getClassLoader().getResourceAsStream(type+".class")){
            if(input==null)throw new AssertionError("Missing class "+type);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf){calls.add(new Call(owner,method,desc));}
                        @Override public void visitInvokeDynamicInsn(String name,String desc,Handle bootstrap,Object... arguments){
                            for(var arg:arguments)if(arg instanceof Handle handle)calls.add(new Call(handle.getOwner(),handle.getName(),handle.getDesc()));
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]),shader=Path.of(args[1]);
        var engine=calls(FABRIC+"PrinterEngine");
        check(engine.stream().anyMatch(c->c.owner.equals("dev/betterlitematica/core/PrinterQueue")&&c.name.equals("pollBatch")&&c.descriptor.startsWith("(ILjava/util/function/IntPredicate;)")),"Compiled printer must select finite batches using available stock");
        check(engine.contains(new Call(FABRIC+"PrinterActions","materialAvailable","(I)Z")),"Cached stock predicate must bind the real actions adapter");
        var supply=calls(FABRIC+"PrinterSupply");
        check(supply.contains(new Call(FABRIC+"PrinterSupply","unavailable","(I)Z")),"Missing shulker material must return to scheduling");
        String supplySource=Files.readString(source.resolve("PrinterSupply.java"));
        check(!supplySource.contains("throw new PrinterReason.Failure(PrinterReason.of(PrinterReason.Id.MISSING,"),"Material shortage must not abort the printer");
        check(supplySource.contains("PrinterReason.Id.SUPPLY_INTERRUPTED")&&supplySource.contains("PrinterReason.Id.CONFIRM_TIMEOUT"),"Ownership and acknowledgement guards must remain");
        String previews=Files.readString(source.resolve("FilePreviews.java"));
        check(previews.split("PreviewSourceReader.read\\(",-1).length==3,"Both initial and detailed previews must use the shared reader");
        check(calls(FABRIC+"FilePreviews").stream().anyMatch(c->c.owner.equals(IO+"PreviewSourceReader")&&c.name.equals("read")),"Preview reader must resolve to shared IO bytecode");
        for(var type:List.of("SchematicImporter","AuxiliaryData","SchematicFileInfo"))
            check(calls(IO+type).stream().anyMatch(c->c.owner.equals(IO+"SchematicReadLimits")),type+" must use heap-derived limits");
        check(calls(FABRIC+"ToolHud").stream().anyMatch(c->c.name.equals("editor")),"Tool HUD must follow the active editor");
        String hud=Files.readString(source.resolve("ToolInteractions.java"));
        check(hud.contains("controller.placement(editor.target())")&&hud.contains("fixedEditBlock?editor.block:s.primary"),"Editor HUD must show its actual target and fixed block");
        String fragment=Files.readString(shader).replaceAll("//[^\\n]*","").replaceAll("\\s+","");
        check(fragment.contains("cross(dFdx(eyeOffset),dFdy(eyeOffset))")&&fragment.indexOf("dFdx(")<fragment.indexOf("discard"),"Face derivatives must precede divergent discards");
        check(fragment.contains("if(c<=LayerClip.y+0.01&&facing>0.999||c>=LayerClip.z-0.01&&facing<-0.999)discard;"),"Layer boundary must reject faces owned by excluded blocks");
        System.out.println("Feature promotion: compiled stock-aware scheduling, shortage recovery, shared dynamic source reading, editor HUD and layer boundary checks passed");
    }
}
