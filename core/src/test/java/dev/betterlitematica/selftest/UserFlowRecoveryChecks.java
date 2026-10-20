package dev.betterlitematica.selftest;

import dev.betterlitematica.core.ShortageTracker;
import dev.betterlitematica.runtime.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Regressions for player-visible recovery, using real files and the real ordered IO worker. */
final class UserFlowRecoveryChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static int run(Path parent)throws Exception{
        checks=0;Path root=Files.createDirectories(parent),local=Files.createDirectories(root.resolve("schematics")),external=Files.createDirectories(root.resolve("external"));
        Files.writeString(local.resolve("existing.litematic"),"directory fixture");Path settings=root.resolve("folders.properties");
        byte[] broken="invalid-mount-id=C:/external\n".getBytes(StandardCharsets.UTF_8);Files.write(settings,broken);
        var folders=new SchematicFolders(local,settings);folders.loadSafely();
        check(!folders.problem().isBlank(),"Broken mount settings must have a visible recovery reason");
        check(Arrays.equals(Files.readAllBytes(settings),broken),"Failure must preserve the original config");
        try(var io=new SessionIo()){
            check(folders.list(io,"","").get(5,TimeUnit.SECONDS).entries().size()==1,"Local schematics remain accessible with broken mounts");
            try{folders.mount(external);throw new AssertionError("Broken config overwritten without recovery");}catch(java.io.IOException expected){checks++;}
            folders.resetPreservingOriginal();check(folders.problem().isEmpty(),"Explicit reset restores writable mount settings");
            try(var files=Files.list(root)){var backups=files.filter(p->p.getFileName().toString().startsWith("folders.properties.backup-")).toList();check(backups.size()==1&&Arrays.equals(Files.readAllBytes(backups.get(0)),broken),"Recovery preserves exact original bytes in a separate backup");}
            var mount=folders.mount(external);var externalReal=external.toRealPath();var restored=new SchematicFolders(local,settings);restored.load();check(restored.folders().stream().anyMatch(f->f.id().equals(mount.id())&&f.path().equals(externalReal)),"Repaired mounts survive reopening with stable identities");
            Path saved=root.resolve("settings.txt");Files.writeString(saved,"old");
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var blocker=io.submit(()->{entered.countDown();release.await();return null;});
            try{
                check(entered.await(5,TimeUnit.SECONDS),"Hold the real IO worker before saving");
                var persistent=io.persist(()->{Files.writeString(saved,"new");return null;});
                var transientJob=io.submit(()->{Files.writeString(root.resolve("cancelled.txt"),"should not run");return null;});
                check(!persistent.cancel(true),"Cancel construction must not cancel an accepted settings write");
                check(transientJob.cancel(true),"Ordinary queued tasks remain cancellable");release.countDown();
                persistent.get(5,TimeUnit.SECONDS);blocker.get(5,TimeUnit.SECONDS);io.submit(()->null).get(5,TimeUnit.SECONDS);
                check(Files.readString(saved).equals("new"),"Saved values reach disk after cancellation");check(!Files.exists(root.resolve("cancelled.txt")),"Cancelled ordinary work never writes");
            }finally{release.countDown();}
        }
        var shortages=new ShortageTracker<String>(2);shortages.failed("glass");
        shortages.refresh(k->false,k->false,false);shortages.refresh(k->false,k->false,false);
        check(shortages.items().equals(List.of("glass")),"Incomplete spatial scanning cannot dismiss a genuine shortage");
        shortages.refresh(k->true,k->true,false);check(!shortages.isEmpty(),"One stock observation does not flicker the warning");
        shortages.refresh(k->true,k->true,false);check(shortages.isEmpty(),"Replenishment clears warnings without any completed spatial scan");
        shortages.failed("glass");shortages.refresh(k->true,k->true,false);shortages.failed("glass");shortages.refresh(k->true,k->true,false);check(!shortages.isEmpty(),"A fresh failure resets the recovery streak");
        shortages.refresh(k->false,k->false,true);check(shortages.isEmpty(),"Two recovery observations retire a no-longer-needed material");
        shortages.failed("glass");shortages.failed("stone");shortages.failed("wood");check(shortages.items().equals(List.of("stone","wood")),"Failure records remain bounded and ordered");
        return checks;
    }
}
