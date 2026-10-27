package com.retrievepanel.printer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class RefillLog {
    public static final Logger LOG=LoggerFactory.getLogger("BackpackPrinter");
    public static void info(String text,Object... args){LOG.info("[BackpackPrinter] "+text,args);}
    public static void warn(String text,Object... args){LOG.warn("[BackpackPrinter] "+text,args);}
}
