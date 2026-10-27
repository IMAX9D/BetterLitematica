package com.retrievepanel.printer;
public final class InventoryOwnership {
    public static boolean blocksAutoRefill(boolean enabled,boolean coordinate,boolean player,boolean screenClosed,
                                          boolean printing,boolean refill,boolean pendingSwap,boolean pendingTrash){
        return enabled&&coordinate&&player&&screenClosed&&(printing||refill||pendingSwap||pendingTrash);
    }
}
