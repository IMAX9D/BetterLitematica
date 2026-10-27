package offline;

import java.io.*;
import java.net.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.launch.platform.container.*;

public final class OfflineService extends MixinServiceAbstract implements IClassProvider,IClassBytecodeProvider {
    public static OfflineService instance;
    public OfflineService(){instance=this;}
    public String getName(){return "offline-bytecode-validation";}
    public boolean isValid(){return true;}
    public IClassProvider getClassProvider(){return this;}
    public IClassBytecodeProvider getBytecodeProvider(){return this;}
    public ITransformerProvider getTransformerProvider(){return null;}
    public IClassTracker getClassTracker(){return null;}
    public IMixinAuditTrail getAuditTrail(){return null;}
    public IFeatureValidator getFeatureValidator(){return null;}
    public IAdviceProvider getAdviceProvider(){return null;}
    public Collection<String> getPlatformAgents(){return List.of();}
    public IContainerHandle getPrimaryContainer(){return new ContainerHandleVirtual("offline");}
    public InputStream getResourceAsStream(String name){return getClass().getClassLoader().getResourceAsStream(name);}
    public URL[] getClassPath(){return new URL[0];}
    public Class<?> findClass(String name)throws ClassNotFoundException{return findClass(name,false);}
    public Class<?> findClass(String name,boolean init)throws ClassNotFoundException{return Class.forName(name,init,getClass().getClassLoader());}
    public Class<?> findAgentClass(String name,boolean init)throws ClassNotFoundException{return findClass(name,init);}
    public ClassNode getClassNode(String name)throws IOException,ClassNotFoundException{return getClassNode(name,false);}
    public ClassNode getClassNode(String name,boolean transform)throws IOException,ClassNotFoundException{return getClassNode(name,transform,0);}
    public ClassNode getClassNode(String name,boolean transform,int flags)throws IOException,ClassNotFoundException{
        try(var in=getResourceAsStream(name.replace('.','/')+".class")){if(in==null)throw new ClassNotFoundException(name);var node=new ClassNode();new ClassReader(in).accept(node,flags);return node;}
    }
    public IMixinTransformer transformer(){return getInternal(IMixinTransformerFactory.class).createTransformer();}
}
