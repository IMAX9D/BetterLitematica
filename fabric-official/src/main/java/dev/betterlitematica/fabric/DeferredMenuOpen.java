package dev.betterlitematica.fabric;

/** One bounded pending command, dispatched after the submitting chat closes. */
final class DeferredMenuOpen {
    private Object world,connection,origin;
    private Runnable pending;
    private int remaining;
    void request(Object world,Object connection,Object origin,Runnable action){
        clear();if(world==null||connection==null)return;
        this.world=world;this.connection=connection;this.origin=origin;pending=action;remaining=20;
    }
    void clear(){pending=null;world=connection=origin=null;remaining=0;}
    void tick(Object world,Object connection,Object screen){
        if(pending==null)return;
        if(this.world!=world||this.connection!=connection||world==null||connection==null){clear();return;}
        if(screen!=null){if(screen!=origin||--remaining<=0)clear();return;}
        Runnable action=pending;clear();action.run();
    }
}
