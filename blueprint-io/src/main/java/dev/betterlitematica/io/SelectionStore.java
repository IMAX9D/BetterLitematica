package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class SelectionStore {
    private SelectionStore(){}
    public static AreaSelection read(Path file)throws IOException{
        if(!Files.exists(file))return AreaSelection.EMPTY;
        Map<String,Object> root=NbtReader.read(file,new NbtReader.Limits(131072,262144,8,4096),Cancellation.THREAD);
        if(NbtReader.integer(root,"Version")!=1)throw new IOException("Unsupported selection settings");
        if(!(root.get("Boxes") instanceof List<?> list)||list.size()>128)throw new IOException("Invalid selection boxes");
        List<SelectionBox> boxes=new ArrayList<>();
        try{for(Object value:list){var tag=NbtReader.compound(value,"Box");boxes.add(new SelectionBox(NbtReader.string(tag,"Name",""),position(tag,"First"),position(tag,"Second")));}
            return new AreaSelection(boxes,NbtReader.string(root,"Selected",""),position(root,"Origin"),NbtReader.integer(root,"Simple")!=0);
        }catch(IllegalArgumentException e){throw new IOException("Malformed selection",e);}
    }
    public static void write(Path file,AreaSelection selection)throws IOException{
        NbtWriter.writeSettings(file,document(selection));
    }
    public static void writeNew(Path file,AreaSelection selection)throws IOException{NbtWriter.writeNew(file,document(selection),Cancellation.THREAD);}
    private static Map<String,Object> document(AreaSelection selection){
        List<Map<String,Object>> boxes=new ArrayList<>();for(var box:selection.boxes())boxes.add(Map.of("Name",box.name(),"First",xyz(box.first()),"Second",xyz(box.second())));
        return Map.of("Version",1,"Selected",selection.selected(),"Origin",xyz(selection.origin()),"Simple",selection.simple()?1:0,"Boxes",boxes);
    }
    private static int[] xyz(Vec3i p){return new int[]{p.x(),p.y(),p.z()};}
    private static Vec3i position(Map<String,Object> tag,String name)throws IOException{if(!(tag.get(name) instanceof int[] v)||v.length!=3)throw new IOException("Invalid position "+name);return new Vec3i(v[0],v[1],v[2]);}
}
