package dev.betterlitematica.fabric;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Method;

/** Defer a shader-pack draw until the caller has finished the world pass. */
public final class ProjectionShaderPass {
    private static Object irisApi;
    private static Method shaders,shadows;
    private static boolean queried,reported;
    private static void discover(){
        if(queried)return;queried=true;if(!FabricLoader.getInstance().isModLoaded("iris"))return;
        for(String name:new String[]{"net.irisshaders.iris.api.v0.IrisApi","net.coderbot.iris.api.v0.IrisApi"}){
            try{var type=Class.forName(name);irisApi=type.getMethod("getInstance").invoke(null);shaders=type.getMethod("isShaderPackInUse");shadows=type.getMethod("isRenderingShadowPass");return;}
            catch(ClassNotFoundException ignored){}
            catch(ReflectiveOperationException|LinkageError failure){report(failure);return;}
        }
        report(new IllegalStateException("Iris rendering API unavailable"));
    }
    private static void report(Throwable failure){if(!reported){reported=true;BetterLitematicaClient.LOGGER.warn("Cannot query Iris render stage; using normal projection event",failure);}}
    private static boolean query(Method method){if(irisApi==null||method==null)return false;try{return (Boolean)method.invoke(irisApi);}catch(ReflectiveOperationException|LinkageError failure){report(failure);return false;}}
    public static boolean shadowPass(){discover();return query(shadows);}
    private static Object world;
    private static Runnable pending;
    static boolean enabled(){discover();return query(shaders);}
    static boolean begin(){if(shadowPass())return false;world=null;pending=null;return true;}
    static boolean pending(){return pending!=null;}
    static void defer(Object owner,Runnable work){world=owner;pending=work;}
    public static void flush(Object current){
        if(shadowPass())return;
        Runnable task=pending;Object owner=world;
        pending=null;world=null;
        if(task!=null&&owner==current)task.run();
    }
    private ProjectionShaderPass(){}
}
