package offline;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
public class TransformChecks {
    public static void main(String[] args)throws Exception {
        MixinBootstrap.init();
        var phase=MixinEnvironment.class.getDeclaredMethod("gotoPhase",MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,MixinEnvironment.Phase.DEFAULT);
        MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("offline.mixins.json");
        boolean combined=args.length>1&&args[1].equals("combined");
        if(combined)Mixins.addConfiguration("fabric-networking-api-v1.client.mixins.json");
        var transformer=OfflineService.instance.transformer();
        var transformed=new java.util.HashMap<String,ClassNode>();
        String[] targets={"dev.betterlitematica.fabric.PrinterSupply","net.fabricmc.fabric.impl.screenhandler.client.ClientNetworking","net.minecraft.class_634","net.minecraft.class_310","dev.betterlitematica.fabric.PrinterEngine","dev.betterlitematica.fabric.PrinterActions","org.anti_ad.mc.ipnext.event.AutoRefillHandler","net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon"};
        int[] expected={5,1,2,1,1,1,1,1};
        for(int i=0;i<targets.length;i++){
            String name=targets[i];byte[] original;
            try(var in=TransformChecks.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){original=in.readAllBytes();}
            byte[] output=transformer.transformClassBytes(name,name,original);
            ClassNode node=new ClassNode();new ClassReader(output).accept(node,0);
            transformed.put(name,node);
            long injected=node.methods.stream().filter(m->m.name.contains("retrieve$")).count();
            if(injected!=expected[i])throw new AssertionError(name+" expected "+expected[i]+" handlers, got "+injected);
            Files.createDirectories(Path.of(args[0]));Files.write(Path.of(args[0],name+".class"),output);
            System.out.println("PASS real Mixin transform "+name+": "+injected+" handlers");
        }
        if(combined)verifyPipeline(transformed);
    }
    static void verifyPipeline(java.util.Map<String,ClassNode> nodes){
        var network=nodes.get("net.minecraft.class_634");
        var payload=network.methods.stream().filter(m->m.name.equals("method_11152")).findFirst().orElseThrow();
        MethodInsnNode dispatch=null;
        for(var op:payload.instructions)if(op instanceof MethodInsnNode call&&call.name.contains("handleCustomPayload"))dispatch=call;
        if(dispatch==null)throw new AssertionError("Real Fabric cancellation hook not installed");
        String hook=dispatch.name;var fabric=network.methods.stream().filter(m->m.name.equals(hook)).findFirst().orElseThrow();
        int route=-1,cancel=-1,i=0;
        for(var op:fabric.instructions){if(op instanceof MethodInsnNode call){
            if(call.owner.equals("net/fabricmc/fabric/impl/networking/client/ClientPlayNetworkAddon")&&call.name.equals("handle"))route=i;
            if(call.name.equals("cancel"))cancel=i;
        }i++;}
        if(route<0||cancel<=route)throw new AssertionError("Unexpected Fabric receiver/cancellation flow");
        var addon=nodes.get("net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon");
        var handle=addon.methods.stream().filter(m->m.name.equals("handle")&&m.desc.equals("(Lnet/minecraft/class_2658;)Z")).findFirst().orElseThrow();
        int observer=-1,read=Integer.MAX_VALUE,release=Integer.MAX_VALUE;i=0;
        for(var op:handle.instructions){if(op instanceof MethodInsnNode call){
            if(call.name.contains("retrieve$trash"))observer=i;
            if(call.name.equals("method_11458")||call.name.equals("handle"))read=Math.min(read,i);
            if(call.name.equals("release"))release=Math.min(release,i);
        }i++;}
        if(observer<0||observer>=read||observer>=release)throw new AssertionError("Trash observer is too late for Fabric-owned payloads");
        System.out.println("PASS combined Fabric + backpack pipeline: observer before decoder/release, Fabric dispatch before vanilla cancellation");
    }
}
