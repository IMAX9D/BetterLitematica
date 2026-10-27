import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Prevent regression of closing a menu inside the final packet callback. Checks production bytecode. */
public class CallbackBoundaryChecks {
    public static void main(String[] args)throws Exception{
        ClassNode n=new ClassNode();new ClassReader(Files.readAllBytes(Path.of(args[0],"com/retrievepanel/client/PrinterRefillSession.class"))).accept(n,0);
        Map<String,MethodNode> methods=new HashMap<>();for(var m:n.methods)methods.put(m.name,m);
        for(String entry:List.of("inventory","slot"))check(entry,methods,new HashSet<>());
        System.out.println("PASS inventory callbacks cannot close, send, clear pending transfers, or advance the state machine");
    }
    static void check(String name,Map<String,MethodNode> methods,Set<String> visited){
        if(!visited.add(name))return;var method=methods.get(name);if(method==null)return;
        for(var insn:method.instructions)if(insn instanceof MethodInsnNode call){
            if(Set.of("closeOwned","sendBatch","complete","advance","cancel","sendSetStack","sendOpenScreen","settleRecovery","method_2883").contains(call.name))throw new AssertionError("packet callback reaches "+call.name);
            if(call.owner.equals("com/retrievepanel/client/PrinterRefillSession"))check(call.name,methods,visited);
        }
    }
}
