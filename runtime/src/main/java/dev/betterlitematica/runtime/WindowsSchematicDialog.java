package dev.betterlitematica.runtime;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.lang.ref.Reference;

/** Explorer's common item dialog. Call on a dedicated STA worker, never the render thread. */
public final class WindowsSchematicDialog {
    private static final GUID CLASS_ID=new GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}");
    private static final GUID DIALOG_ID=new GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}");
    private static final GUID ITEM_ID=new GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
    private WindowsSchematicDialog(){}
    private interface Shell extends StdCallLibrary {
        Shell INSTANCE=Native.load("shell32",Shell.class,W32APIOptions.UNICODE_OPTIONS);
        HRESULT SHCreateItemFromParsingName(WString path,Pointer context,GUID iid,PointerByReference result);
    }
    @Structure.FieldOrder({"name","pattern"})
    public static final class Filter extends Structure {
        public WString name=new WString("投影文件");
        public WString pattern=new WString("*.litematic;*.schem;*.schematic;*.nbt");
    }
    private static final class Item extends Unknown {
        Item(Pointer pointer){super(pointer);}
        int call(int slot,Object... arguments){Object[] values=new Object[arguments.length+1];values[0]=getPointer();System.arraycopy(arguments,0,values,1,arguments.length);return _invokeNativeInt(slot,values);}
    }
    private static void check(int result)throws IOException{if(result<0)throw new IOException("无法打开系统选择窗口，请重试");}
    public static String choose(long owner,boolean folder,String initial)throws IOException {
        check(Ole32.INSTANCE.CoInitializeEx(null,Ole32.COINIT_APARTMENTTHREADED|Ole32.COINIT_DISABLE_OLE1DDE).intValue());
        Item dialog=null,initialItem=null,selected=null;Pointer name=null;
        try{
            var pointer=new PointerByReference();check(Ole32.INSTANCE.CoCreateInstance(CLASS_ID,null,1,DIALOG_ID,pointer).intValue());dialog=new Item(pointer.getValue());
            // IFileDialog slots follow IUnknown(0..2), IModalWindow.Show(3), then the SDK vtable.
            var options=new IntByReference();check(dialog.call(10,options));
            check(dialog.call(9,options.getValue()|0x8|0x40|0x800|0x1000|(folder?0x20:0)));
            check(dialog.call(17,new WString(folder?"加载投影文件夹":"加载投影文件")));
            check(dialog.call(18,new WString(folder?"加载文件夹":"加载文件")));
            Filter filter=null;
            if(!folder){filter=new Filter();filter.write();check(dialog.call(4,1,filter.getPointer()));}
            var start=new PointerByReference();
            if(initial!=null&&!initial.isBlank()&&Shell.INSTANCE.SHCreateItemFromParsingName(new WString(initial),null,ITEM_ID,start).intValue()>=0){
                initialItem=new Item(start.getValue());check(dialog.call(12,initialItem.getPointer()));
            }
            int result=dialog.call(3,owner==0?null:new Pointer(owner));Reference.reachabilityFence(filter);
            if(result==0x800704c7)return null; // HRESULT_FROM_WIN32(ERROR_CANCELLED)
            check(result);var item=new PointerByReference();check(dialog.call(20,item));selected=new Item(item.getValue());
            var display=new PointerByReference();check(selected.call(5,0x80058000,display));name=display.getValue();return name.getWideString(0);
        }finally{
            if(name!=null)Ole32.INSTANCE.CoTaskMemFree(name);
            if(selected!=null)selected.Release();if(initialItem!=null)initialItem.Release();if(dialog!=null)dialog.Release();
            Ole32.INSTANCE.CoUninitialize();
        }
    }
}
