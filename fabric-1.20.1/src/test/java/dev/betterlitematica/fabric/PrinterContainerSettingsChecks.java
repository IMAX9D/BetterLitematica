package dev.betterlitematica.fabric;

import com.google.gson.JsonObject;

/** Container filling defaults and draft persistence, without starting a game. */
public final class PrinterContainerSettingsChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static int run(){
        checks=0;
        check(new PrinterSettings().containerFill,"New settings enable container filling");
        check(PrinterSettings.read(null).containerFill,"Missing printer settings enable container filling");
        var old=new JsonObject();old.addProperty("interval",7);old.addProperty("futureSetting","preserved");
        var migrated=PrinterSettings.read(old);
        check(migrated.containerFill,"Old configurations without the field enable container filling");
        check(!old.has("containerFill"),"Reading old settings does not mutate input JSON");
        check(migrated.interval==7,"Migration preserves existing timing");
        check(migrated.snapshot().get("containerFill").getAsBoolean(),"Snapshot writes the enabled default explicitly");
        for(boolean enabled:new boolean[]{false,true}){
            var json=old.deepCopy();json.addProperty("containerFill",enabled);
            var settings=PrinterSettings.read(json);
            check(settings.containerFill==enabled,"Explicit container setting is respected");
            var draft=PrinterSettings.read(settings.snapshot());draft.containerFill=!enabled;
            check(settings.containerFill==enabled,"Editing a screen-style draft leaves saved settings unchanged");
            var saved=PrinterSettings.read(draft.snapshot());
            check(saved.containerFill==!enabled,"Changed draft survives save and reopen");
            check(saved.interval==7,"Saving container setting preserves timing");
            check(saved.snapshot().get("futureSetting").getAsString().equals("preserved"),"Saving preserves unknown settings");
        }
        return checks;
    }
}
