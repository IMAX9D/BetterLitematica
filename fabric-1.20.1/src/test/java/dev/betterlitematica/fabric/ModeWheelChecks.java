package dev.betterlitematica.fabric;

/** Exercises the production input state machine without a window or world. */
public final class ModeWheelChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Input {
        final ModeWheelInput.Lifecycle state=new ModeWheelInput.Lifecycle();
        Object world=new Object(),connection=new Object(),screen;
        boolean focused=true,player=true;
        Input(){update(false,false);}
        ModeWheelInput.Lifecycle.Decision update(boolean down,boolean press){return state.update(world,connection,focused,player,screen,down,press);}
        ModeWheelInput.Lifecycle.Decision update(boolean down,boolean press,boolean retained){return state.update(world,connection,focused,player,screen,down,press,retained);}
        void open(){var result=update(true,true);check(result.open()&&result.consume(),"Physical rising press opens and consumes");screen=new Object();state.bind(screen);}
        void release(){var previous=screen;var result=update(false,false);check(result.close()==previous&&result.consume(),"Release closes exactly the owned screen");screen=null;check(!state.claimed()&&state.owned()==null,"Release clears ownership");}
    }
    public static int run(){checks=0;
        var in=new Input();in.open();for(int i=0;i<100;i++){var result=in.update(true,i%2==0);check(!result.open()&&result.consume()&&result.close()==null,"Held/repeated Tab cannot open again");}in.release();
        in.open();in.release(); // A tap entirely between ticks still has exact ownership.
        in=new Input();check(!in.update(true,false).open(),"Polling a held key is not a new press");check(!in.update(true,true).open(),"A late repeat cannot open a missed press");in.update(false,false);in.open();
        in.screen=null;check(!in.update(true,false).open(),"Esc dismisses while Tab remains held");check(!in.update(true,true).open()&&in.state.claimed(),"Esc requires physical release before reopening");in.update(false,false);in.open();in.release();
        in=new Input();in.open();Object other=new Object();in.screen=other;var result=in.update(false,false);check(result.close()==null&&!result.open(),"Release cannot close a replacement screen");check(!in.state.claimed(),"Replacement release clears the claim");
        in=new Input();in.screen=new Object();result=in.update(true,true);check(!result.consume()&&!result.open(),"Tab in a foreign GUI remains available to that GUI");in.screen=null;check(!in.update(true,true).open(),"Closing a GUI with Tab held cannot open wheel");in.update(false,false);in.open();in.release();
        in=new Input();in.open();Object owned=in.screen;in.focused=false;result=in.update(true,false);check(result.close()==owned&&!result.open(),"Lost focus closes owned wheel");in.screen=null;in.focused=true;check(!in.update(true,true).open(),"Focus regain while held does not reopen");in.update(false,false);in.open();in.release();
        for(int change=0;change<3;change++){
            in=new Input();in.open();owned=in.screen;if(change==0)in.world=new Object();else if(change==1)in.connection=new Object();else{in.world=null;in.connection=null;in.player=false;}
            result=in.update(true,false);check(result.close()==owned&&!result.open(),"World/connection/disconnect closes old owned screen");in.screen=null;if(change==2){in.world=new Object();in.connection=new Object();in.player=true;in.update(true,false);}
            check(!in.update(true,true).open(),"Old physical hold cannot cross connection generation");in.update(false,false);in.open();in.release();
        }
        in=new Input();in.player=false;check(!in.update(true,true).open(),"No player cannot open wheel");in.player=true;check(!in.update(true,true).open(),"Player becoming ready does not replay old press");in.update(false,false);in.open();in.state.reset();in.screen=null;check(!in.update(true,true).open(),"Lifecycle reset does not resurrect a held Tab");in.update(false,false);in.open();in.release();
        in=new Input();in.open();owned=in.screen;
        for(int i=0;i<30;i++){
            result=in.update(false,false,true);
            check(result.consume()&&!result.open()&&result.close()==null&&in.state.owned()==owned&&in.state.claimed(),"Editing retains screen and world-input barrier after release");
        }
        result=in.update(true,true,true);check(result.close()==owned&&result.consume()&&!result.open(),"Fresh wheel key closes retained editor");in.screen=null;
        for(int i=0;i<10;i++)check(!in.update(true,i%2==0,true).open(),"Closing retained editor cannot reopen during same hold");
        in.update(false,false);in.open();in.release();
        for(int change=0;change<6;change++){
            in=new Input();in.open();owned=in.screen;in.update(false,false,true);
            if(change==0)in.focused=false;
            else if(change==1)in.world=new Object();
            else if(change==2)in.connection=new Object();
            else if(change==3){in.world=null;in.connection=null;in.player=false;}
            else if(change==4)in.screen=null;
            else in.screen=new Object();
            result=in.update(false,false,true);
            check(result.close()==(change<4?owned:null)&&!result.open()&&in.state.owned()==null,"Retained editor respects focus, session, Esc and external-screen ownership");
            in.screen=null;in.focused=true;in.world=new Object();in.connection=new Object();in.player=true;in.update(false,false);
            check(!in.update(false,false,true).open(),"Retained editor never resurrects without a new press");in.open();in.release();
        }
        in=new Input();in.open();in.update(false,false,true);in.state.reset();in.screen=null;
        check(!in.state.claimed()&&in.state.owned()==null&&!in.update(false,false,true).open(),"Binding reset clears retained ownership");in.open();in.release();
        for(int bits=0;bits<16;bits++)for(var mode:WheelModes.values()){
            var original=new PrinterSettings();original.print=(bits&1)!=0;original.breakWrong=original.breakExtra=original.breakState=(bits&2)!=0;original.fluid=(bits&4)!=0;original.fill=(bits&8)!=0;
            original.highlightRange=31;original.highlightLimit=731;original.placeColor=0x11223344;original.interval=7;original.cooldown=13;original.skip.add("minecraft:diamond_block");
            var json=original.snapshot();json.addProperty("futurePreference",83);original=PrinterSettings.read(json);var before=original.snapshot();var next=WheelModes.toggled(original,mode);
            check(before.equals(original.snapshot()),"Mode switching cannot mutate settings source");check(next!=original&&next.skip!=original.skip,"Mode draft owns its mutable lists");
            for(var otherMode:WheelModes.values())check(otherMode.enabled(next)==(otherMode==mode?!otherMode.enabled(original):otherMode.enabled(original)),"Each mode switches independently of every other mode");
            check(next.breakWrong==next.breakExtra&&next.breakExtra==next.breakState,"Aggregate mining toggles all flags together");
            check(next.highlightRange==31&&next.highlightLimit==731&&next.placeColor==0x11223344&&next.interval==7&&next.cooldown==13&&next.skip.equals(original.skip),"Unrelated preferences survive mode changes");
            check(next.snapshot().get("futurePreference").getAsInt()==83,"Unknown future preferences survive mode changes");
            check(WheelModes.toggled(next,mode).snapshot().equals(before),"Toggling a uniform mode twice restores the complete settings");
        }
        for(int bits=1;bits<7;bits++){
            var source=new PrinterSettings();source.breakWrong=(bits&1)!=0;source.breakExtra=(bits&2)!=0;source.breakState=(bits&4)!=0;
            check(WheelModes.MINE.enabled(source)&&WheelModes.MINE.mixed(source),"Partial mining flags remain visible as mixed");
            var off=WheelModes.toggled(source,WheelModes.MINE);check(!off.breakWrong&&!off.breakExtra&&!off.breakState&&!WheelModes.MINE.mixed(off),"Clicking partially enabled mining disables all three");
            var on=WheelModes.toggled(off,WheelModes.MINE);check(on.breakWrong&&on.breakExtra&&on.breakState&&!WheelModes.MINE.mixed(on),"Enabling mining restores all three independent break categories");
        }
        var bindings=new InputBindings();var actions=new java.util.ArrayList<String>();var keys=java.util.Map.of("menu","M","materials","M+L");Object session=new Object();
        bindings.poll(keys,session,()->true,k->false,actions::add);bindings.capture(keys,session,true,k->k.equals("M"));bindings.suspend();bindings.capture(keys,session,true,k->false);bindings.poll(keys,session,()->true,k->false,actions::add);
        check(actions.isEmpty(),"A wheel tap between ticks discards the earlier queued M press");
        bindings.capture(keys,session,true,k->k.equals("M"));bindings.capture(keys,session,true,k->false);bindings.poll(keys,session,()->true,k->false,actions::add);check(actions.equals(java.util.List.of("menu")),"A fresh M tap works after the wheel release barrier");
        check(ModeWheelInput.bindingCode("TAB")==org.lwjgl.glfw.GLFW.GLFW_KEY_TAB,"Default wheel key");
        check(ModeWheelInput.bindingCode("F8")==org.lwjgl.glfw.GLFW.GLFW_KEY_F8,"Wheel remap key");
        check(ModeWheelInput.bindingCode("")==org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN,"Wheel can be unbound");
        for(String invalid:java.util.List.of("CTRL+K","MOUSE4","SHIFT","ESCAPE")){boolean rejected=false;try{ModeWheelInput.bindingCode(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid wheel binding rejected");}
        check(InputBindings.conflict(java.util.Map.of("wheel","M"),"materials","M+L").equals("wheel"),"Wheel cannot swallow a configured chord");
        check(InputBindings.conflict(java.util.Map.of("materials","M+L"),"wheel","M").equals("materials"),"Wheel subset conflict in reverse direction");
        var remapped=new InputBindings();actions.clear();var changed=java.util.Map.of("menu","F7","wheel","F8");
        remapped.poll(changed,session,()->true,k->false,actions::add);
        remapped.capture(changed,session,true,k->k.equals("F8"));remapped.capture(changed,session,true,k->false);remapped.poll(changed,session,()->true,k->false,actions::add);check(actions.isEmpty(),"Wheel has no duplicate dispatcher action");
        remapped.capture(changed,session,true,k->k.equals("F7"));remapped.capture(changed,session,true,k->false);remapped.poll(changed,session,()->true,k->false,actions::add);check(actions.equals(java.util.List.of("menu")),"Remapped menu fires exactly once");
        return checks;
    }
    public static void main(String[] args){System.out.println("Mode wheel checks passed: "+run());}
}
