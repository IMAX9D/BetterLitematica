package dev.betterlitematica.fabric;

import java.util.*;

/** Transition-order regression, with no window, world or game entrypoint. */
public final class InputEntryChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static final class Input {
        final InputBindings bindings=new InputBindings();final List<String> actions=new ArrayList<>();
        final Map<String,String> keys=new LinkedHashMap<>(Map.of("menu","M","materials","M+L","rendering","M+R","hold",""));
        Object world=new Object();boolean active=true;Set<String> down=Set.of();
        Input(){poll();}
        void event(String... keys){down=Set.of(keys);bindings.capture(this.keys,world,active,down::contains);}
        void poll(){bindings.poll(keys,world,()->active,down::contains,actions::add);}
        void empty(){actions.clear();down=Set.of();poll();}
    }
    public static int run(){checks=0;
        var in=new Input();in.event("M");in.event();in.poll();check(in.actions.equals(List.of("menu")),"A tap wholly between ticks opens menu");
        in.poll();check(in.actions.size()==1,"Physical calibration does not duplicate a captured tap");
        in.empty();in.event("M");in.poll();in.poll();check(in.actions.isEmpty(),"Holding prefix defers menu");in.event();in.poll();check(in.actions.equals(List.of("menu")),"Release opens once");
        for(boolean menuFirst:new boolean[]{true,false}){
            in.empty();in.event("M");in.event("M","L");in.event(menuFirst?"L":"M");in.event();in.poll();
            check(in.actions.equals(List.of("materials")),"Fast chord suppresses prefix regardless of release order");
        }
        in.empty();in.event("M");in.event("M","R");in.poll();in.poll();in.event("M");in.event();in.poll();check(in.actions.equals(List.of("rendering")),"Tick and callback do not repeat a chord");
        in.empty();in.event("M");in.event();in.active=false;in.poll();in.active=true;in.poll();check(in.actions.isEmpty(),"Opening screen or losing focus discards queued input");
        in.event("M");in.active=false;in.poll();in.active=true;in.poll();in.event();in.poll();check(in.actions.isEmpty(),"Held key after focus recovery cannot trigger");
        in.event("M");in.event();in.world=new Object();in.poll();check(in.actions.isEmpty(),"Old-world tap is not replayed in new world");
        in.event("M");in.event();in.world=null;in.active=false;in.poll();in.world=new Object();in.active=true;in.poll();check(in.actions.isEmpty(),"Disconnect flushes captured transitions");
        in.keys.put("mouse","M+MOUSE1");in.poll();in.event("M");in.event("M","MOUSE1");in.event("M");in.event();in.poll();check(in.actions.equals(List.of("mouse")),"Mouse chord transitions suppress menu");
        in.empty();in.keys.put("copy","M+L");in.poll();in.event("M");in.event("M","L");in.event();in.poll();check(in.actions.size()==1&&!in.actions.contains("menu"),"Duplicate configured chords fire once");
        in.empty();in.event("M");in.event();in.keys.put("menu","N");in.poll();check(in.actions.isEmpty(),"Rebinding drops stale events");
        in.empty();for(int i=0;i<66;i++)in.event(i%2==0?new String[]{"N"}:new String[0]);in.poll();check(in.actions.isEmpty(),"Overflow drops bounded queue and waits for release");
        in.event("N");in.event();in.poll();check(in.actions.equals(List.of("menu")),"Input recovers after overflow");
        in.empty();in.keys.put("hideProjection","H");in.poll();in.event("H");in.poll();check(in.bindings.held("hideProjection"),"Held action survives callback replay");in.event();in.poll();check(!in.bindings.held("hideProjection"),"Released hold cannot remain latched");
        in.empty();in.event("N");in.event();in.event("N");in.event();
        in.bindings.poll(in.keys,in.world,()->in.active,in.down::contains,a->{in.actions.add(a);in.active=false;});
        check(in.actions.equals(List.of("menu")),"Opening first menu discards later queued taps");
        var pending=new DeferredMenuOpen();Object world=new Object(),connection=new Object(),chat=new Object();var opened=new ArrayList<String>();
        pending.request(world,connection,chat,()->opened.add("materials"));check(opened.isEmpty(),"Command never opens synchronously in ChatScreen.submit");
        pending.tick(world,connection,chat);check(opened.isEmpty(),"Pending command waits until chat closes");pending.tick(world,connection,null);pending.tick(world,connection,null);check(opened.equals(List.of("materials")),"Command opens exactly once after chat close");
        opened.clear();pending.request(world,connection,chat,()->opened.add("old"));pending.request(world,connection,chat,()->opened.add("new"));pending.tick(world,connection,null);check(opened.equals(List.of("new")),"Single-slot latest command bounds pending screens");
        for(int reason=0;reason<4;reason++){
            opened.clear();pending.request(world,connection,chat,()->opened.add("stale"));
            if(reason==0)pending.tick(new Object(),connection,null);if(reason==1)pending.tick(world,new Object(),null);if(reason==2)pending.tick(null,null,null);if(reason==3)pending.tick(world,connection,new Object());
            pending.tick(world,connection,null);check(opened.isEmpty(),"World/connection/disconnect/other-screen invalidates pending command");
        }
        pending.request(world,connection,chat,()->opened.add("expired"));for(int i=0;i<20;i++)pending.tick(world,connection,chat);pending.tick(world,connection,null);check(opened.isEmpty(),"Stuck chat command expires after bounded ticks");
        pending.request(world,connection,chat,()->opened.add("closed"));pending.clear();pending.tick(world,connection,null);check(opened.isEmpty(),"Explicit disconnect cleanup is immediate");
        pending.request(world,connection,chat,()->{throw new IllegalStateException("test");});try{pending.tick(world,connection,null);}catch(IllegalStateException expected){}pending.tick(world,connection,null);check(opened.isEmpty(),"Failed opener does not run twice");
        return checks;
    }
    public static void main(String[] args){System.out.println("InputEntryChecks: "+run()+" checks");}
}
