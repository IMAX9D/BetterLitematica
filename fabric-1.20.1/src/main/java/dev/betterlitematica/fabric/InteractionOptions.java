package dev.betterlitematica.fabric;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent, versioned user settings. Unknown keys are retained on save. */
final class InteractionOptions {
    boolean tool=true,easyPlace,hold=true,restriction,pick=true,boxes=true;
    String mode="PLACEMENT";
    String toolItem="minecraft:stick";
    int protectedHotbar;
    boolean followLayer;
    AccuratePlacement.Mode accurate=AccuratePlacement.Mode.AUTO;
    PrinterSettings printer=new PrinterSettings();
    DisplayOptions display=new DisplayOptions();
    CommandSettings commands=new CommandSettings();
    private JsonObject preserved=new JsonObject();
    final Map<String,String> keys=new LinkedHashMap<>();
    InteractionOptions(){keys.put("editUndo","CTRL+Z");keys.put("editRedo","CTRL+Y");keys.put("menu","M");keys.put("wheel","TAB");keys.put("placements","M+P");keys.put("materials","M+L");keys.put("verifier","M+V");keys.put("selection","M+S");keys.put("settings","M+C");keys.put("rendering","M+R");keys.put("tool","M+T");keys.put("easyPlace","M+E");keys.put("layerNext","PAGE_UP");keys.put("layerPrevious","PAGE_DOWN");keys.put("printer","M+B");keys.put("printerWork","CAPS_LOCK");keys.put("printerStop","CTRL+CAPS_LOCK");keys.put("printerMode","");keys.put("information","I");keys.put("layerMode","");keys.put("layerPlayer","");keys.put("pickLast","");for(String key:List.of("easyPlaceHold","restrictionHold","hideProjection","restriction","toolMode","layerModePrevious","layerFollow","selectionFirst","selectionSecond","selectionOrigin","selectionAdd","selectionRemove","selectionMode","placementHere","rotate","reload"))keys.put(key,"");}
    static InteractionOptions read(Path file)throws IOException{
        var settings=new InteractionOptions();if(!Files.exists(file))return settings;if(Files.size(file)>65536)throw new IOException("Settings too large");
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){settings.preserved=JsonParser.parseReader(reader).getAsJsonObject();var root=settings.preserved;
            if(root.has("version")&&root.get("version").getAsInt()!=1)throw new IOException("Unsupported settings version");
            settings.tool=bool(root,"tool",true);settings.easyPlace=bool(root,"easyPlace",false);settings.hold=bool(root,"hold",true);settings.restriction=bool(root,"restriction",false);settings.pick=bool(root,"pick",true);settings.boxes=bool(root,"boxes",true);settings.followLayer=bool(root,"followLayer",false);
            if(root.has("toolItem")){settings.toolItem=root.get("toolItem").getAsString();if(settings.toolItem.length()>128||net.minecraft.util.Identifier.tryParse(settings.toolItem)==null)throw new IOException("Invalid tool item");}if(root.has("protectedHotbar"))settings.protectedHotbar=root.get("protectedHotbar").getAsInt();if(settings.protectedHotbar<0||settings.protectedHotbar>511)throw new IOException("Invalid protected hotbar slots");
            if(root.has("mode")){settings.mode=root.get("mode").getAsString();if(!Set.of("PLACEMENT","SELECTION").contains(settings.mode))throw new IOException("Unsupported tool mode");}
            if(root.has("keys")){var keys=root.getAsJsonObject("keys");for(String key:settings.keys.keySet())if(keys.has(key))settings.keys.put(key,keys.get(key).getAsString());}
            if(root.has("accuratePlacement"))settings.accurate=AccuratePlacement.Mode.valueOf(root.get("accuratePlacement").getAsString());
            settings.printer=PrinterSettings.read(root.get("printer"));if(root.has("display")){settings.display=new Gson().fromJson(root.get("display"),DisplayOptions.class);if(settings.display==null||settings.display.errorStyle==null)throw new IOException("Invalid display settings");}
            if(root.has("commands")){settings.commands=new Gson().fromJson(root.get("commands"),CommandSettings.class);if(settings.commands==null)throw new IOException("Invalid command settings");settings.commands.validate();}
            if(root.has("keys")){var old=root.getAsJsonObject("keys");if(settings.keys.get("information").isBlank()&&old.has("informationDetails"))settings.keys.put("information",old.get("informationDetails").getAsString());}
            settings.keys.replaceAll((key,chord)->InputBindings.normalize(chord));
            ModeWheelInput.bindingCode(settings.keys.get("wheel"));
            return settings;
        }catch(RuntimeException e){throw new IOException("Malformed interaction settings",e);}
    }
    JsonObject snapshot(){var root=preserved.deepCopy();root.addProperty("version",1);root.addProperty("toolItem",toolItem);root.addProperty("protectedHotbar",protectedHotbar);root.addProperty("followLayer",followLayer);root.addProperty("accuratePlacement",accurate.name());root.addProperty("mode",mode);root.addProperty("tool",tool);root.addProperty("easyPlace",easyPlace);root.addProperty("hold",hold);root.addProperty("restriction",restriction);root.addProperty("pick",pick);root.addProperty("boxes",boxes);JsonObject keyValues=new JsonObject();keys.forEach(keyValues::addProperty);root.add("keys",keyValues);root.add("printer",printer.snapshot());root.add("display",new Gson().toJsonTree(display));root.add("commands",new Gson().toJsonTree(commands));return root;}
    static void write(Path file,JsonObject data)throws IOException{Files.createDirectories(file.toAbsolutePath().getParent());Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"settings-",".part");try{Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(data),StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    private static boolean bool(JsonObject root,String key,boolean fallback){return root.has(key)?root.get(key).getAsBoolean():fallback;}
}
