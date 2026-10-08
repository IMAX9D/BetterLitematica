package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.SessionIo;
import dev.betterlitematica.core.PlacementSession;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real-directory regressions: filtering is not restricted to the old listing prefix. */
public final class DirectorySearchChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(Path parent)throws Exception {
        checks=0;Path root=Files.createTempDirectory(parent,"directory-search-");
        Path many=Files.createDirectory(root.resolve("many"));
        for(int i=0;i<11050;i++)Files.createFile(many.resolve(String.format(Locale.ROOT,"projection-%05d.litematic",i)));
        String late=null;int index=0;
        try(var files=Files.newDirectoryStream(many)){for(Path file:files)if(index++>=10500){late=file.getFileName().toString();break;}}
        check(late!=null,"Fixture has matches past both old directory limits");
        try(var io=new SessionIo()){
            var prefix=io.list(root,"many").get(10,TimeUnit.SECONDS);
            check(prefix.entries().size()==1000&&prefix.truncated(),"Broad queries retain only bounded results and explicitly report overflow");
            String target=late;check(prefix.entries().stream().noneMatch(e->e.name().equals(target)),"Chosen target really is absent from the displayed prefix");
            var found=io.list(root,"many",target.toUpperCase(Locale.ROOT)).get(10,TimeUnit.SECONDS);
            check(found.entries().size()==1&&found.entries().get(0).path().equals("many/"+target)&&!found.truncated(),"A case-insensitive search traverses beyond the old 10000-entry cutoff");
            var none=io.list(root,"many","not-present").get(10,TimeUnit.SECONDS);check(none.entries().isEmpty()&&!none.truncated(),"An exhausted large query returns a proven empty result rather than a clipped prefix");
            var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var settingsJob=io.submit(()->{blocked.countDown();release.await();return null;});
            try{check(blocked.await(5,TimeUnit.SECONDS),"Settings worker is held for isolation test");found=io.list(root,"many",target).get(10,TimeUnit.SECONDS);check(found.entries().size()==1,"Directory search does not wait behind settings writes");}finally{release.countDown();}
            settingsJob.get(5,TimeUnit.SECONDS);
            // Hold the actual executor so cancellation cannot race a very fast directory completion.
            var browserField=SessionIo.class.getDeclaredField("browser");browserField.setAccessible(true);var browser=(Executor)browserField.get(io);
            var browserStarted=new CountDownLatch(1);var browserRelease=new CountDownLatch(1);
            browser.execute(()->{browserStarted.countDown();try{browserRelease.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            CompletableFuture<SessionIo.Listing> cancelled;
            try{check(browserStarted.await(5,TimeUnit.SECONDS),"Browser executor barrier is active");cancelled=io.list(root,"many","not-present");check(cancelled.cancel(true),"A queued query can be cancelled");}finally{browserRelease.countDown();}
            found=io.list(root,"many",target).get(10,TimeUnit.SECONDS);check(found.entries().size()==1&&cancelled.isCancelled(),"A new query completes after cancellation without stale completion");
            Path session=root.resolve("session.blps");io.save(session,PlacementSession.EMPTY);check(io.read(session).get(5,TimeUnit.SECONDS).equals(PlacementSession.EMPTY),"Save-before-read ordering remains intact on the separate settings worker");
            Path exact=Files.createDirectory(root.resolve("exact"));for(int i=0;i<1000;i++)Files.createFile(exact.resolve(i+".schem"));
            var limit=io.list(root,"exact").get(10,TimeUnit.SECONDS);check(limit.entries().size()==1000&&!limit.truncated(),"Exactly 1000 results are complete, not falsely labelled truncated");
            Files.createFile(exact.resolve("extra.schem"));check(io.list(root,"exact").get(10,TimeUnit.SECONDS).truncated(),"An actual extra result establishes overflow");
            Path mixed=Files.createDirectory(root.resolve("mixed"));Files.createDirectory(mixed.resolve("NeedleFolder"));for(String suffix:List.of(".litematic",".schem",".schematic",".nbt",".txt"))Files.createFile(mixed.resolve("Needle"+suffix));
            var formats=io.list(root,"mixed","needle").get(5,TimeUnit.SECONDS);check(formats.entries().size()==5&&formats.entries().get(0).directory(),"Matching directories and four supported formats remain sorted folders first");
            check(formats.entries().stream().noneMatch(e->e.name().endsWith(".txt")),"Other file formats cannot consume the result limit");
            Files.createFile(mixed.resolve("New.litematic"));check(io.list(root,"mixed","new").get(5,TimeUnit.SECONDS).entries().size()==1,"A refreshed directory observes newly created files");
            Files.delete(mixed.resolve("New.litematic"));check(io.list(root,"mixed","new").get(5,TimeUnit.SECONDS).entries().isEmpty(),"A new query does not reuse deleted entries");
            try{io.list(root,"..","anything").get(5,TimeUnit.SECONDS);throw new AssertionError("Escape accepted");}catch(ExecutionException expected){checks++;}
            try{io.list(root,"","x".repeat(121)).get(5,TimeUnit.SECONDS);throw new AssertionError("Oversize query accepted");}catch(ExecutionException expected){checks++;}
            check(io.takeError()==null,"Browsing failures do not masquerade as settings-save failure");
        }
        var closed=new SessionIo();var cancelled=closed.list(root,"many","absent");closed.close();check(cancelled.isDone(),"Closing settles a live directory query");
        try{closed.list(root,"many","none").get(5,TimeUnit.SECONDS);throw new AssertionError("Closed browser accepted query");}catch(ExecutionException expected){checks++;}
        return checks;
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("betterlitematica-directory-check-");
        try{System.out.println("Directory search checks passed: "+run(root));}
        finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
