package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class VerificationSpatialChecks {
 private static int checks;
 public static int run()throws Exception{
  checks=0;var key=new VerificationReport.Key(Comparison.MISSING,"stone","air");
  var ignored=new VerificationSection.Builder(k->true);for(int i=0;i<4096;i++)ignored.add(new Vec3i(i&15,i>>>8,(i>>>4)&15),Comparison.MISSING,"stone","air");
  require(ignored.build().samples().isEmpty()&&ignored.build().positions().size()==4096,"ignored data retained for restoration");
  var unknown=new VerificationSection.Builder();unknown.add(Vec3i.ZERO,Comparison.UNKNOWN,"stone","");require(unknown.build().positions().isEmpty(),"unknown never spatial error");
  var report=new VerificationReport();var ledger=new VerificationLedger();var view=new VerificationHighlights(ledger,report);
  try{
   for(int section=0;section<65;section++){var contribution=section(10000+section*16);var replaced=ledger.replace(new SectionKey(0,section,0,0),contribution).get(5,TimeUnit.SECONDS);report.replace(replaced.before(),replaced.after());}
   require(report.samples().size()==2048,"global list sample limit retained");
   var late=new SectionKey(1,0,0,0);var lateSection=section(0);var added=ledger.replace(late,lateSection).get(5,TimeUnit.SECONDS);report.replace(added.before(),added.after());
   require(report.samples().stream().noneMatch(s->s.position().x()<16),"nearby section deliberately omitted from full global sample");
   require(lateSection.samples().size()==32&&lateSection.positions().size()==4096,"full per-section positions are separate from list sample");
   var found=ledger.nearby(Vec3i.ZERO,128,32768).get(5,TimeUnit.SECONDS);require(found.keys().equals(List.of(late))&&found.total()==1,"spatial query locates late nearby section");
   var race=new VerificationHighlights(ledger,report);
   try{race.update(Vec3i.ZERO);ledger.knownSections().get(5,TimeUnit.SECONDS);race.chunkChanged(0,0);
    for(int i=0;i<8;i++){race.update(Vec3i.ZERO,(x,z)->false);ledger.knownSections().get(5,TimeUnit.SECONDS);}
    require(race.boxes().isEmpty(),"unload before initial query completion cannot admit stale ledger errors");
    race.committed(late,added.revision());pump(race,ledger,Vec3i.ZERO,8);require(volume(race.boxes())==4096,"loaded and revalidated section may resume after admission rejection");
   }finally{race.close();}
   var restored=ledger.spatial(List.of(new SectionKey(0,0,0,0)),Set.of()).get(5,TimeUnit.SECONDS);
   require(volume(restored.values().iterator().next().boxes())==4096,"evicted ledger section decodes every position from disk");
   pump(view,ledger,Vec3i.ZERO,8);require(volume(view.boxes())==4096,"all nearby errors rendered despite 2048/32 old samples");
   view.chunkChanged(0,0);require(view.boxes().isEmpty(),"chunk unload immediately clears its cached spatial geometry");
   view.committed(late,added.revision());pump(view,ledger,Vec3i.ZERO,8);require(volume(view.boxes())==4096,"confirmed chunk revalidation restores geometry");
   report.ignore(key);view.ignoredChanged();pump(view,ledger,Vec3i.ZERO,8);require(view.boxes().isEmpty(),"ignore removes whole spatial group");
   report.restore(key);view.ignoredChanged();pump(view,ledger,Vec3i.ZERO,8);require(volume(view.boxes())==4096,"restore recovers all coordinates without rescan");
   view.pending(late);require(view.boxes().isEmpty(),"world update immediately removes stale section snapshot");
   var repaired=ledger.replace(late,new VerificationSection(Map.of(),4096,List.of())).get(5,TimeUnit.SECONDS);view.committed(late,repaired.revision());report.replace(repaired.before(),repaired.after());pump(view,ledger,Vec3i.ZERO,25);require(view.boxes().isEmpty(),"repaired contribution cannot reappear from stale spatial read");
   var replacement=ledger.replace(late,lateSection).get(5,TimeUnit.SECONDS);view.committed(late,replacement.revision());pump(view,ledger,Vec3i.ZERO,25);require(volume(view.boxes())==4096,"later mismatch contribution resumes spatial display");
   var peer=new SectionKey(1,1,0,0);ledger.replace(peer,section(16)).get(5,TimeUnit.SECONDS);pump(view,ledger,Vec3i.ZERO,25);require(view.boxes().size()==1&&volume(view.boxes())==8192,"adjacent sections merge without expanding to world cells");
   pump(view,ledger,new Vec3i(10000,0,0),10);require(view.boxes().stream().allMatch(b->b.min().x()>=10000),"camera move does not retain old neighborhood");
   view.close();require(view.boxes().isEmpty(),"closed view releases cache");
  }finally{view.close();ledger.close();ledger.closedFuture().get(5,TimeUnit.SECONDS);}
  return checks;
 }
 private static VerificationSection section(int x){var builder=new VerificationSection.Builder();for(int i=0;i<4096;i++)builder.add(new Vec3i(x+(i&15),i>>>8,(i>>>4)&15),Comparison.MISSING,"stone","air");return builder.build();}
 private static long volume(List<HighlightCuboids.Box> boxes){long total=0;for(var b:boxes)total+=(long)(b.max().x()-b.min().x()+1)*(b.max().y()-b.min().y()+1)*(b.max().z()-b.min().z()+1);return total;}
 private static void pump(VerificationHighlights view,VerificationLedger ledger,Vec3i camera,int times)throws Exception{for(int i=0;i<times;i++){view.update(camera);ledger.knownSections().get(5,TimeUnit.SECONDS);}}
 private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{System.out.println("Verification spatial checks passed: "+run());}
}
