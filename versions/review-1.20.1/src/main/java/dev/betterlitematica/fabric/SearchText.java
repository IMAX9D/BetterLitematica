package dev.betterlitematica.fabric;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline name/ID/full-pinyin/initial search; bounded cache shared by all pickers. */
final class SearchText {
    private static final Map<Integer,String> PINYIN=load();
    private static final Map<String,String> CACHE=new LinkedHashMap<>(256,.75f,true);
    private static Map<Integer,String> load(){
        var result=new HashMap<Integer,String>();
        try(var input=SearchText.class.getResourceAsStream("/assets/betterlitematica/pinyin.tsv")){
            if(input==null)return Map.of();
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){int tab=line.indexOf('\t');if(tab>0)result.put(Integer.parseInt(line.substring(0,tab),16),line.substring(tab+1));}}
        }catch(IOException|NumberFormatException e){BetterLitematicaClient.LOGGER.warn("Could not load pinyin search data",e);}
        return Map.copyOf(result);
    }
    static String index(String text){
        String cached=CACHE.get(text);if(cached!=null)return cached;
        var full=new StringBuilder();var initials=new StringBuilder();
        text.codePoints().forEach(c->{String syllable=PINYIN.get(c);if(syllable==null){full.appendCodePoint(c);initials.appendCodePoint(c);}else{full.append(syllable);initials.append(syllable.charAt(0));}});
        String value=(text+" "+full+" "+initials).toLowerCase(Locale.ROOT);
        if(CACHE.size()>=8192)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(text,value);return value;
    }
    static boolean matches(String text,String query){String value=index(text);for(String term:query.toLowerCase(Locale.ROOT).strip().split("\\s+"))if(!value.contains(term))return false;return true;}
    static int rank(String name,String id,String query){
        String q=query.toLowerCase(Locale.ROOT).strip();if(q.isEmpty())return 0;
        String value=index(name);for(String part:value.split("\\s+"))if(part.equals(q))return 0;
        for(String part:value.split("\\s+"))if(part.startsWith(q))return 1;
        return matches(name,q)?2:3;
    }
}
