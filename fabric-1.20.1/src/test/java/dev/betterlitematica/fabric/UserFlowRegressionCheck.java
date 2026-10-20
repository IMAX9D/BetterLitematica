package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Headless adapter regressions. No window, world, or MinecraftClient is constructed. */
public final class UserFlowRegressionCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Class<?> nested(String name)throws Exception{return Class.forName("dev.betterlitematica.fabric.NearbyBuildHud$"+name);}
    private static Object make(Class<?> type,Object... args)throws Exception{for(var ctor:type.getDeclaredConstructors())if(ctor.getParameterCount()==args.length){ctor.setAccessible(true);return ctor.newInstance(args);}throw new AssertionError(type);}
    private static Field field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object owner,String name)throws Exception{return field(owner,name).get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{field(owner,name).set(owner,value);}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... args)throws Exception{var m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    @SuppressWarnings("unchecked") private static Map<Object,Object> map(Object owner,String name)throws Exception{return (Map<Object,Object>)get(owner,name);}
    public static void main(String[] args)throws Exception{
        constructionScopes();cursorCoverage();movementRetention();coordinateDrafts();nativeShortcuts();
        System.out.println("User flow regressions: "+checks+" checks passed.");
    }
    private static void nativeShortcuts()throws Exception{
        // SDL exists only in the 26.3 adapter. Earlier games retain their native GLFW events.
        try{Class.forName("org.lwjgl.sdl.SDLKeyboard");}catch(ClassNotFoundException olderGame){return;}
        var adapter=Class.forName("dev.betterlitematica.fabric.UiEvents");
        var key=adapter.getDeclaredMethod("key",int.class,int.class,int.class);key.setAccessible(true);
        String[] actions={"isSelectAll","isCopy","isPaste","isCut"};int[] letters={'A','C','V','X'};
        for(int control:new int[]{64,128})for(int caps:new int[]{0,8192})for(int i=0;i<letters.length;i++){
            int logical=Character.toLowerCase(letters[i]),modifiers=control|caps;
            Object event=key.invoke(null,letters[i],logical,modifiers);
            check((int)event.getClass().getMethod("keycode").invoke(event)==logical,"Preserve native shortcut key under Caps Lock");
            check((int)event.getClass().getMethod("modifiers").invoke(event)==modifiers,"Preserve native modifier bits");
            check((boolean)event.getClass().getMethod(actions[i]).invoke(event),actions[i]+" must work with either control and Caps Lock state");
        }
    }
    private static void constructionScopes()throws Exception{
        var method=PrinterEngine.class.getDeclaredMethod("selectionWork",PrinterSettings.class,boolean.class);method.setAccessible(true);
        for(var scope:PrinterSettings.Scope.values())for(String mode:List.of("fill","fluid","bedrock")){
            var s=new PrinterSettings();s.print=false;s.fill=s.fluid=s.bedrock=false;
            var enabled=PrinterSettings.class.getDeclaredField(mode);enabled.setAccessible(true);enabled.setBoolean(s,true);
            var range=PrinterSettings.class.getDeclaredField(mode.equals("fluid")?"fluidScope":mode+"Scope");range.setAccessible(true);range.set(s,scope);
            check((boolean)method.invoke(null,s,true)==(scope!=PrinterSettings.Scope.PROJECTION),mode+" selection scope must work without an enabled projection");
            check(!(boolean)method.invoke(null,s,false),"A selection-only job cannot start without a selection");
        }
    }
    private static void cursorCoverage()throws Exception{
        var type=nested("CellCursor");var random=new Random(40310);
        for(int pass=0;pass<50;pass++){
            var min=new Vec3i(random.nextInt(80)-40,random.nextInt(80)-40,random.nextInt(80)-40);
            var max=min.add(new Vec3i(random.nextInt(16),random.nextInt(16),random.nextInt(16)));var bounds=new PlacementBounds(min,max);
            var cursor=make(type,bounds);var seen=new HashSet<Vec3i>();
            while((boolean)invoke(cursor,"hasNext",new Class<?>[0])){var point=(Vec3i)invoke(cursor,"next",new Class<?>[0]);check(PlacementLayout.inside(bounds,point)&&seen.add(point),"Clipped cursor must neither escape nor double-count");}
            check(seen.size()==(max.x()-min.x()+1)*(max.y()-min.y()+1)*(max.z()-min.z()+1),"Every clipped cell must be visited exactly once");
        }
        for(var center:List.of(new Vec3i(0,0,0),new Vec3i(-17,-63,31),new Vec3i(1228,184,858))){
            var area=new PlacementBounds(center.add(new Vec3i(-102,-102,-102)),center.add(new Vec3i(102,102,102)));
            var plan=make(nested("SectionPlan"),center,area);var seen=new HashSet<Vec3i>();var first=(Vec3i)invoke(plan,"next",new Class<?>[0]);seen.add(first);
            check(first.equals(new Vec3i(center.x()>>4,center.y()>>4,center.z()>>4)),"Planning must start beside the player");
            while((boolean)invoke(plan,"hasNext",new Class<?>[0]))check(seen.add((Vec3i)invoke(plan,"next",new Class<?>[0])),"Section plan must not repeat work");
            for(int y=area.min().y()>>4;y<=area.max().y()>>4;y++)for(int z=area.min().z()>>4;z<=area.max().z()>>4;z++)for(int x=area.min().x()>>4;x<=area.max().x()>>4;x++)check(seen.contains(new Vec3i(x,y,z)),"Long-range plan must cover all candidate sections");
        }
    }
    private static void movementRetention()throws Exception{
        var hud=new NearbyBuildHud(null,null);var tileType=nested("Tile");var resultType=nested("Result");var tiles=map(hud,"tiles");
        var middle=make(tileType,new Vec3i(0,0,0),new PlacementBounds(Vec3i.ZERO,new Vec3i(15,15,15)));
        var boundary=make(tileType,new Vec3i(6,0,0),new PlacementBounds(new Vec3i(96,0,0),new Vec3i(100,15,15)));
        var gone=make(tileType,new Vec3i(-7,0,0),new PlacementBounds(new Vec3i(-102,0,0),new Vec3i(-97,15,15)));
        for(var tile:List.of(middle,boundary,gone)){var result=make(resultType);set(result,"compared",10);set(result,"matched",5);set(tile,"result",result);set(tile,"complete",true);tiles.put(get(tile,"key"),tile);invoke(hud,"enqueue",new Class<?>[]{tileType},tile);}
        set(boundary,"boundary",true);var total=get(hud,"total");set(total,"compared",30);set(total,"matched",15);set(hud,"working",middle);
        var nextArea=new PlacementBounds(new Vec3i(-96,-102,-102),new Vec3i(108,102,102));
        Predicate<PlacementBounds> relevant=b->true,interior=b->b.min().x()<96;
        invoke(hud,"reconcile",new Class<?>[]{PlacementBounds.class,Predicate.class,Predicate.class},nextArea,relevant,interior);
        check(tiles.size()==2&&!tiles.containsKey(new Vec3i(-7,0,0)),"Departed sections must be removed");
        check(get(hud,"working")==middle&&(boolean)get(middle,"complete"),"Walking must retain the in-flight interior work and completed interior result");
        check(get(boundary,"result")==null&&!(boolean)get(boundary,"complete"),"Only changed boundary contributions are invalidated");
        check((int)get(total,"compared")==10&&(int)get(total,"matched")==5,"Old boundary counts cannot linger or cause double-counting");
        check(((Collection<?>)get(hud,"queue")).size()==2,"Removed sections cannot accumulate in the work queue");
        check((int)get(hud,"incomplete")==1,"Only the changed boundary remains incomplete");
        var before=get(middle,"result");invoke(hud,"invalidate",new Class<?>[]{tileType,boolean.class},middle,false);
        check(get(hud,"working")==middle&&get(middle,"result")==before,"A block event must schedule a fresh pass without repeatedly cancelling the active pass");
        hud.clear();check(tiles.isEmpty()&&hud.compared()==0&&((Collection<?>)get(hud,"urgent")).isEmpty(),"World/source changes clear cached results and queued work together");
    }
    private static void coordinateDrafts()throws Exception{
        var drafts=make(Class.forName("dev.betterlitematica.fabric.SubregionScreen$CoordinateDrafts"));var original=new Vec3i(4,-60,8);
        invoke(drafts,"value",new Class<?>[]{String.class,int.class,Vec3i.class},"a",0,original);
        check(!(boolean)invoke(drafts,"changed",new Class<?>[]{String.class,Vec3i.class},"a",original),"Untouched coordinates must not show a discard prompt");
        invoke(drafts,"set",new Class<?>[]{String.class,int.class,String.class},"a",0,"12");
        check((boolean)invoke(drafts,"changed",new Class<?>[]{String.class,Vec3i.class},"a",original)&&((Vec3i)invoke(drafts,"position",new Class<?>[]{String.class},"a")).x()==12,"Legal input remains a draft until applied");
        invoke(drafts,"set",new Class<?>[]{String.class,int.class,String.class},"a",1,"invalid");
        try{invoke(drafts,"position",new Class<?>[]{String.class},"a");throw new AssertionError("Invalid coordinate accepted");}catch(InvocationTargetException expected){check(expected.getCause().getMessage().equals("Y 坐标须为整数"),"Invalid coordinates identify the field in Chinese");}
        try{invoke(drafts,"replaceAfter",new Class<?>[]{String.class,Runnable.class},"a",(Runnable)()->{throw new IllegalStateException("locked");});}catch(InvocationTargetException expected){}
        check((boolean)invoke(drafts,"changed",new Class<?>[]{String.class,Vec3i.class},"a",original),"A failed apply must preserve raw coordinate input");
    }
}
