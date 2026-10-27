import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Verify actual installed-jar integration, without initializing a Minecraft client. */
public final class StockAdapterChecks {
    static ClassNode resource(String name)throws Exception{
        try(var in=StockAdapterChecks.class.getClassLoader().getResourceAsStream(name+".class")){
            if(in==null)throw new AssertionError(name);ClassNode n=new ClassNode();new ClassReader(in.readAllBytes()).accept(n,0);return n;
        }
    }
    static AbstractInsnNode previous(AbstractInsnNode at){do{at=at.getPrevious();}while(at!=null&&at.getOpcode()<0);return at;}
    public static void main(String[] args)throws Exception{
        String name="dev/betterlitematica/fabric/BackpackPrinterBridge";var bridge=resource(name);int fields=0;
        for(var method:bridge.methods)if(method.name.equals("<clinit>"))for(var insn:method.instructions){
            if(insn instanceof MethodInsnNode call&&call.owner.equals(name)&&call.name.equals("field")){
                var key=(LdcInsnNode)previous(insn);var owner=(LdcInsnNode)previous(key);var target=resource(((Type)owner.cst).getInternalName());
                if(target.fields.stream().noneMatch(f->f.name.equals(key.cst)))throw new AssertionError("Missing target field "+owner.cst+" / "+key.cst);
                fields++;
            }
        }
        // No per-placement allocation, inventory traversal, or map mutation in ordinary use tracking.
        for(var node:List.of(bridge,resource("com/retrievepanel/printer/StockPolicy")))for(var method:node.methods){
            if(!method.name.equals("used")&&!method.name.equals("attempted"))continue;
            for(var insn:method.instructions){
                if(insn.getOpcode()==Opcodes.NEW||insn.getOpcode()==Opcodes.ANEWARRAY||insn.getOpcode()==Opcodes.NEWARRAY)throw new AssertionError("Allocation in "+method.name);
                if(insn instanceof MethodInsnNode call&&!Set.of("get","attempted").contains(call.name))throw new AssertionError("Unexpected hot call "+call.name);
            }
        }
        var queue=resource("dev/betterlitematica/core/PrinterQueue");
        if(queue.methods.stream().noneMatch(m->m.name.equals("pollBatch")&&m.desc.contains("IntPredicate")))throw new AssertionError("Installed queue lacks availability-aware scheduler");
        var actions=resource("dev/betterlitematica/fabric/PrinterActions");
        if(actions.methods.stream().noneMatch(m->m.name.equals("equipInternal")&&m.desc.equals("(Lnet/minecraft/class_1792;I)Ldev/betterlitematica/fabric/PrinterActions$Outcome;")))throw new AssertionError("Unexpected equip descriptor");
        System.out.println("PASS "+fields+" bridge reflection fields in exact target classes; ordinary placement tracking has no allocation/scanning calls; queue/equip ABI checked");
    }
}
