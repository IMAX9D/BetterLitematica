package dev.betterlitematica.fabric;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/** Checks the effective adapter, so an obsolete overlay cannot silently replace the optimized renderer. */
public final class ProjectionPipelineCheck {
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]);
        var compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new AssertionError("A JDK is required");
        var calls=new HashSet<String>();var fields=new HashMap<String,String>();
        var files=List.of("ProjectionRenderer1201.java","SectionMesher.java","ProjectionPages.java","ProjectionController.java");
        for(var file:files)if(!Files.isRegularFile(source.resolve(file)))throw new AssertionError("Missing rendering adapter: "+file);
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,
                manager.getJavaFileObjectsFromFiles(files.stream().map(source::resolve).map(Path::toFile).toList()));
            for(var unit:task.parse())new TreeScanner<Void,Void>(){
                @Override public Void visitMethodInvocation(MethodInvocationTree call,Void unused){
                    calls.add(call.getMethodSelect().toString());return super.visitMethodInvocation(call,unused);
                }
                @Override public Void visitVariable(VariableTree field,Void unused){
                    if(field.getType()!=null)fields.put(field.getName().toString(),field.getType().toString());return super.visitVariable(field,unused);
                }
            }.scan(unit,null);
        }
        for(var call:List.of("SectionMesher.submit","FrozenModel.capture","pages.allocate","pages.upload","pages.draw","pages.beginFrame","worldRefresh.completed","resources.concurrentBuilds"))
            if(!calls.contains(call))throw new AssertionError("Effective adapter omitted rendering capability: "+call);
        if(!"WorldColumnChanges".equals(fields.get("renderChanges")))throw new AssertionError("Chunk packets must coalesce by column");
        String mesher=Files.readString(source.resolve("SectionMesher.java"));
        if(!mesher.contains("BoundedTaskPool")||!mesher.contains("Output::free"))throw new AssertionError("Missing bounded/cancellation-safe mesh workers");
        String pages=Files.readString(source.resolve("ProjectionPages.java"));
        if(!pages.contains("RangeAllocator")||!pages.contains("reserveShared")||!pages.contains("owner.reference"))throw new AssertionError("Missing shared page ownership/budget accounting");
        for(var name:List.of("WeightedModelAccessor","MultipartModelAccessor"))
            if(Files.exists(source.resolve("mixin/"+name+".java")))verifyAccessor(name);
        System.out.println("Projection pipeline: effective adapter has asynchronous meshing, shared pages and column refresh");
    }
    private record Access(String field,String descriptor){}
    /** Mixin accessor annotations are not checked by javac; validate the real named-game field descriptors. */
    private static void verifyAccessor(String name)throws Exception {
        var targets=new ArrayList<String>();var accesses=new ArrayList<Access>();
        reader("dev/betterlitematica/fabric/mixin/"+name).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){
                if(!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;"))return null;
                return new AnnotationVisitor(Opcodes.ASM9){
                    @Override public AnnotationVisitor visitArray(String key){
                        if(!key.equals("value"))return null;
                        return new AnnotationVisitor(Opcodes.ASM9){@Override public void visit(String key,Object value){targets.add(((Type)value).getInternalName());}};
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                return new MethodVisitor(Opcodes.ASM9){
                    @Override public AnnotationVisitor visitAnnotation(String annotation,boolean visible){
                        if(!annotation.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;"))return null;
                        return new AnnotationVisitor(Opcodes.ASM9){@Override public void visit(String key,Object value){if(key.equals("value"))accesses.add(new Access((String)value,Type.getReturnType(descriptor).getDescriptor()));}};
                    }
                };
            }
        },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        if(targets.size()!=1||accesses.size()!=1)throw new AssertionError("Unexpected model accessor: "+name);
        var actual=new HashMap<String,String>();
        reader(targets.get(0)).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public FieldVisitor visitField(int access,String field,String descriptor,String signature,Object value){actual.put(field,descriptor);return null;}
        },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        for(var field:accesses)if(!field.descriptor.equals(actual.get(field.field)))throw new AssertionError("Model accessor does not match game field: "+name+" "+field);
    }
    private static ClassReader reader(String name)throws Exception {
        try(var input=ProjectionPipelineCheck.class.getClassLoader().getResourceAsStream(name+".class")){
            if(input==null)throw new AssertionError("Missing class: "+name);
            return new ClassReader(input);
        }
    }
}
