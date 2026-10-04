package dev.betterlitematica.fabric;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.TreeSet;

/** Compact HUD numbers must never round shortages upwards or overflow long counts. */
public final class HudNumberChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void formatted(long count,String expected){check(HudNumbers.compact(count).equals(expected),count+" should display "+expected+"; got "+HudNumbers.compact(count));}
    public static int run(){checks=0;
        formatted(0,"0");formatted(1,"1");formatted(9999,"9999");formatted(10000,"1万");formatted(10001,"1万");formatted(10999,"1万");formatted(11000,"1.1万");formatted(19999,"1.9万");
        formatted(999999,"99.9万");formatted(1000000,"100万");formatted(1009999,"100万");formatted(99999999,"9999万");
        formatted(100000000,"1亿");formatted(199999999,"1.9亿");formatted(9999999999L,"99.9亿");formatted(10000000000L,"100亿");
        formatted(999999999999L,"9999亿");formatted(1000000000000L,"1兆");formatted(1999999999999L,"1.9兆");
        formatted(9999999999999999L,"9999兆");formatted(10000000000000000L,"1京");formatted(Long.MAX_VALUE,"922京");
        boolean rejected=false;try{HudNumbers.compact(-1);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Negative material counts are rejected instead of unsigned-wrapping");
        rejected=false;try{HudNumbers.compact(Long.MIN_VALUE);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"The minimum long cannot overflow negation into a valid label");

        var counts=new TreeSet<Long>();counts.add(0L);counts.add(1L);counts.add(Long.MAX_VALUE);
        for(long unit:new long[]{10000L,100000000L,1000000000000L,10000000000000000L}){
            for(long multiple:new long[]{1,2,10,99,100,101})if(unit<=Long.MAX_VALUE/multiple){long edge=unit*multiple;counts.add(edge-1);counts.add(edge);if(edge<Long.MAX_VALUE)counts.add(edge+1);}
        }
        BigDecimal previous=BigDecimal.valueOf(-1);
        for(long count:counts){String label=HudNumbers.compact(count);check(label.matches("[0-9]+(?:\\.[0-9])?[万亿兆京]?"),"Compact labels stay short and contain no grouping/exponent notation");
            BigDecimal represented=represented(label);check(represented.compareTo(BigDecimal.valueOf(count))<=0,"Displayed shortage is never rounded upwards at a unit boundary");
            check(represented.compareTo(previous)>=0,"Increasing counts never produce a decreasing displayed value");previous=represented;
            if(count<10000)check(label.equals(Long.toString(count)),"Small counts retain their exact value");
        }
        Locale original=Locale.getDefault();try{Locale.setDefault(Locale.FRANCE);formatted(19999,"1.9万");formatted(Long.MAX_VALUE,"922京");}finally{Locale.setDefault(original);}
        return checks;
    }
    private static BigDecimal represented(String label){
        int last=label.length()-1;long unit=switch(label.charAt(last)){case '万'->10000L;case '亿'->100000000L;case '兆'->1000000000000L;case '京'->10000000000000000L;default->1;};
        return new BigDecimal(unit==1?label:label.substring(0,last)).multiply(BigDecimal.valueOf(unit));
    }
}
