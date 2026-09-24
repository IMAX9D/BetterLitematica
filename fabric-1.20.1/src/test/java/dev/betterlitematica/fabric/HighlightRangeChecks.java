package dev.betterlitematica.fabric;
import com.google.gson.JsonObject;
public final class HighlightRangeChecks {
 public static int run(){int checks=0;
  var defaults=new PrinterSettings();if(defaults.highlightRange!=8)throw new AssertionError("default range");checks++;
  var old=new JsonObject();old.addProperty("futureValue",17);if(PrinterSettings.read(old).highlightRange!=8)throw new AssertionError("legacy default");checks++;
  for(int range=1;range<=32;range++){var settings=PrinterSettings.read(old);settings.highlightRange=range;var json=settings.snapshot();var restored=PrinterSettings.read(json);if(restored.highlightRange!=range||json.get("futureValue").getAsInt()!=17)throw new AssertionError("range round trip");checks++;}
  for(int range:new int[]{Integer.MIN_VALUE,-1,0,33,Integer.MAX_VALUE}){var value=new JsonObject();value.addProperty("highlightRange",range);boolean rejected=false;try{PrinterSettings.read(value);}catch(IllegalArgumentException e){rejected=e.getMessage().startsWith("高亮范围");}if(!rejected)throw new AssertionError("out of range persisted config accepted");checks++;}
  var modified=new PrinterSettings();modified.highlightRange=33;boolean rejected=false;try{modified.snapshot();}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError("invalid draft saved");checks++;
  if(new PrinterSettings().highlightLimit!=0||PrinterSettings.read(old).highlightLimit!=0)throw new AssertionError("unlimited highlight default");checks++;
  for(int limit:new int[]{0,1,2048,50000,1000000}){var settings=new PrinterSettings();settings.highlightLimit=limit;if(PrinterSettings.read(settings.snapshot()).highlightLimit!=limit)throw new AssertionError("highlight limit persistence");checks++;}
  for(int limit:new int[]{-1,1000001,Integer.MAX_VALUE}){var value=new JsonObject();value.addProperty("highlightLimit",limit);rejected=false;try{PrinterSettings.read(value);}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError("invalid highlight limit accepted");checks++;}
  return checks;
 }
}
