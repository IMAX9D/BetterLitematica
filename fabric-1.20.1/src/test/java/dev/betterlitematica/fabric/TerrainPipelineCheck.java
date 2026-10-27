package dev.betterlitematica.fabric;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Validate the effective native backend, including Mixin member names that javac cannot check.
 * Reading class files never initializes Minecraft or creates a graphics context. */
public final class TerrainPipelineCheck {
    private static final Map<String,ClassNode> CLASSES=new HashMap<>();
    private static int hooks,sites;
    public static void main(String[] args)throws Exception {
        var config=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Set<String> enabled=new LinkedHashSet<>();
        Set<String> registered=new LinkedHashSet<>();for(var item:config.getAsJsonArray("client"))registered.add(item.getAsString());
        if(Files.exists(Path.of(args[1]).resolve("mixin/ProjectionWorldRenderMixin.java")))
            require(registered.contains("ProjectionWorldRenderMixin"),"Extracted projection lifecycle is not registered");
        for(var item:config.getAsJsonArray("client"))if(item.getAsString().startsWith("Terrain"))enabled.add(item.getAsString());
        for(var name:List.of("TerrainVisibilityGraphMixin","TerrainUploadQueueMixin","TerrainSectionBufferMixin","TerrainDataPublishMixin","TerrainFrustumAccessor"))
            require(enabled.contains(name),"Native capability is not registered: "+name);
        boolean enginePages=enabled.contains("TerrainStagingBudgetMixin");
        require(enginePages||enabled.contains("TerrainRegionDrawMixin"),"Native shared-buffer draw hook is not registered");
        require(enginePages||enabled.contains("TerrainSortUploadMixin"),"Native sorting upload hook is not registered");
        if(enabled.contains("TerrainBufferCloseMixin"))require(enabled.contains("TerrainBatchDrawMixin"),"GPU API submission must include actual native multi-draw batching");
        for(var name:enabled)verify(name);
        for(var name:List.of("ProjectionWorldFinishMixin","ProjectionWorldRenderMixin"))if(registered.contains(name))verify(name);
        if(!enginePages){
            var world=read("net/minecraft/client/render/WorldRenderer");boolean setupScalesEntities=false;
            for(var method:world.methods)if(Set.of("setupTerrain","collectRenderableChunks","method_34808").contains(method.name))
                for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("setRenderDistanceMultiplier"))setupScalesEntities=true;
            if(setupScalesEntities){
                boolean preserved=false;for(var method:read("dev/betterlitematica/fabric/mixin/TerrainVisibilityGraphMixin").methods)
                    for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("setRenderDistanceMultiplier"))preserved=true;
                require(preserved,"Replacing terrain setup must preserve the user's entity render distance");
            }
        }
        String plugin=Files.readString(Path.of(args[1]).resolve("OptionalMixins.java"));
        require(plugin.contains("disableTerrainOptimizations")&&plugin.contains("startsWith(\"Terrain\")"),"Native backend must be gated atomically");
        for(String mod:List.of("sodium","iris","canvas","vulkanmod","optifabric"))require(plugin.contains("isModLoaded(\""+mod+"\")"),"Missing renderer coexistence guard: "+mod);
        System.out.println("Native terrain: "+hooks+" registered hooks, "+sites+" verified injection sites; complete backend and coexistence guards present");
    }
    private static void verify(String name)throws Exception {
        ClassNode mixin=read("dev/betterlitematica/fabric/mixin/"+name);var marker=annotation(mixin.visibleAnnotations,"Mixin");
        if(marker==null)marker=annotation(mixin.invisibleAnnotations,"Mixin");require(marker!=null,"Not a Mixin: "+name);
        List<ClassNode> targets=new ArrayList<>();
        for(Object value:values(get(marker,"value")))targets.add(read(((Type)value).getInternalName()));
        for(Object value:values(get(marker,"targets")))targets.add(read(value.toString().replace('.','/')));
        require(!targets.isEmpty(),"Missing target: "+name);hooks++;
        if((mixin.access&Opcodes.ACC_INTERFACE)!=0&&targets.stream().anyMatch(target->(target.access&Opcodes.ACC_INTERFACE)==0)){
            for(var method:mixin.methods)if((method.access&Opcodes.ACC_STATIC)==0){
                boolean accessor=annotations(method).stream().anyMatch(a->Set.of("Accessor","Invoker").contains(simple(a.desc)));
                require(accessor&&(method.access&Opcodes.ACC_ABSTRACT)!=0,name+" is an interface targeting a class with a non-accessor/default method");
            }
        }
        for(var field:mixin.fields){
            var shadow=annotation(field.visibleAnnotations,"Shadow");if(shadow==null)shadow=annotation(field.invisibleAnnotations,"Shadow");
            if(shadow==null)continue;Set<String> names=new HashSet<>();names.add(field.name);for(var alias:values(get(shadow,"aliases")))names.add(alias.toString());
            boolean found=false;for(var target:targets)found|=hasField(target,names,field.desc);
            require(found,name+" shadows missing field "+field.name+":"+field.desc);
        }
        for(var method:mixin.methods)for(var a:annotations(method)){
            if(a.desc.endsWith("/Accessor;")){
                String field=(String)get(a,"value");Type descriptor=Type.getMethodType(method.desc);String type=descriptor.getArgumentTypes().length==0?descriptor.getReturnType().getDescriptor():descriptor.getArgumentTypes()[0].getDescriptor();
                boolean found=false;for(var target:targets)found|=hasField(target,Set.of(field),type);
                require(found,name+" accesses missing field "+field+":"+type);sites++;continue;
            }
            if(!Set.of("Inject","Redirect","WrapOperation","WrapMethod","ModifyVariable","ModifyArg","ModifyArgs","ModifyConstant","ModifyExpressionValue").contains(simple(a.desc)))continue;
            List<MethodNode> methods=new ArrayList<>();
            for(var selector:values(get(a,"method")))for(var target:targets)for(var candidate:target.methods)
                if(matches(candidate,selector.toString()))methods.add(candidate);
            require(!methods.isEmpty(),name+" selects missing method "+get(a,"method"));
            List<?> locations=values(get(a,"at"));
            if(locations.isEmpty()){sites++;continue;}
            for(Object location:locations){
                if(!(location instanceof AnnotationNode at))continue;
                String kind=(String)get(at,"value"),member=(String)get(at,"target");
                if(member==null||member.isEmpty()||!Set.of("INVOKE","INVOKE_STRING","FIELD","NEW").contains(kind)){sites++;continue;}
                int count=0;
                for(var selected:methods)for(var instruction:selected.instructions){
                    if(kind.equals("INVOKE")&&instruction instanceof MethodInsnNode call&&member.equals("L"+call.owner+";"+call.name+call.desc))count++;
                    if(kind.equals("INVOKE_STRING")&&instruction instanceof MethodInsnNode call&&member.equals("L"+call.owner+";"+call.name+call.desc)){
                        String literal=null;for(var arg:values(get(at,"args")))if(arg.toString().startsWith("ldc="))literal=arg.toString().substring(4);
                        var previous=instruction.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();
                        if(previous instanceof LdcInsnNode constant&&Objects.equals(constant.cst,literal))count++;
                    }
                    if(kind.equals("FIELD")&&instruction instanceof FieldInsnNode field&&member.equals("L"+field.owner+";"+field.name+":"+field.desc))count++;
                    if(kind.equals("NEW")&&instruction instanceof MethodInsnNode call&&call.name.equals("<init>")){
                        String constructor=call.desc.substring(0,call.desc.length()-1)+"L"+call.owner+";";
                        if(member.equals(constructor)||member.equals(call.owner))count++;
                    }
                }
                int required=get(a,"require") instanceof Integer n&&n>=0?n:1;
                // Optional member access must still exist somewhere on its declared API family.
                require(count>=Math.max(1,required),name+" has no matching "+kind+" in "+get(a,"method")+": "+member+" (found "+count+")");sites+=count;
            }
        }
    }
    private static boolean matches(MethodNode method,String selector){int p=selector.indexOf('(');return p<0?method.name.equals(selector):method.name.equals(selector.substring(0,p))&&method.desc.equals(selector.substring(p));}
    private static boolean hasField(ClassNode target,Set<String> names,String descriptor)throws Exception {
        for(var field:target.fields)if(names.contains(field.name)&&descriptor.equals(field.desc))return true;
        return target.superName!=null&&!target.superName.equals("java/lang/Object")&&hasField(read(target.superName),names,descriptor);
    }
    private static List<AnnotationNode> annotations(MethodNode method){var result=new ArrayList<AnnotationNode>();if(method.visibleAnnotations!=null)result.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)result.addAll(method.invisibleAnnotations);return result;}
    private static AnnotationNode annotation(List<AnnotationNode> annotations,String name){if(annotations!=null)for(var a:annotations)if(simple(a.desc).equals(name))return a;return null;}
    private static String simple(String descriptor){return descriptor.substring(descriptor.lastIndexOf('/')+1,descriptor.length()-1);}
    private static Object get(AnnotationNode annotation,String key){if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);return null;}
    private static List<?> values(Object value){return value==null?List.of():value instanceof List<?> list?list:List.of(value);}
    private static ClassNode read(String name)throws Exception {
        var cached=CLASSES.get(name);if(cached!=null)return cached;
        try(var stream=TerrainPipelineCheck.class.getClassLoader().getResourceAsStream(name+".class")){
            require(stream!=null,"Missing class "+name);var node=new ClassNode();new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);CLASSES.put(name,node);return node;
        }
    }
    private static void require(boolean condition,String failure){if(!condition)throw new AssertionError(failure);}
}
