package dev.betterlitematica.fabric;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Offline polyphonic search. Finite prefix states avoid expanding exponential reading combinations. */
final class SearchText {
    private static final Map<Integer,String[]> PINYIN=load();
    private static final Map<String,List<String[]>> CACHE=new LinkedHashMap<>(256,.75f,true);
    private static final Map<String,Pattern> PATTERNS=new LinkedHashMap<>(32,.75f,true);
    private record Pattern(String text,int[] prefix) {
        Pattern(String text){this(text,new int[text.length()]);for(int i=1,k=0;i<text.length();i++){while(k>0&&text.charAt(i)!=text.charAt(k))k=prefix[k-1];if(text.charAt(i)==text.charAt(k))k++;prefix[i]=k;}}
    }
    private static Map<Integer,String[]> load(){
        var result=new HashMap<Integer,String[]>();
        try(var input=SearchText.class.getResourceAsStream("/assets/betterlitematica/pinyin.tsv")){
            if(input==null)throw new IOException("Missing pinyin data");
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){int tab=line.indexOf('\t');if(tab>0)result.put(Integer.parseInt(line.substring(0,tab),16),line.substring(tab+1).split(","));}}
        }catch(IOException|NumberFormatException e){BetterLitematicaClient.LOGGER.warn("Could not load pinyin search data",e);}
        return Map.copyOf(result);
    }
    private static String normalize(String text){return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replace("u\u0308","v").replaceAll("\\p{M}+","");}
    private static List<String[]> tokens(String text){
        var found=CACHE.get(text);if(found!=null)return found;
        var result=new ArrayList<String[]>();text.toLowerCase(Locale.ROOT).codePoints().forEach(c->result.add(PINYIN.getOrDefault(c,new String[]{new String(Character.toChars(c))})));
        if(CACHE.size()>=8192)CACHE.remove(CACHE.keySet().iterator().next());var value=List.copyOf(result);CACHE.put(text,value);return value;
    }
    private static Pattern pattern(String query){
        var found=PATTERNS.get(query);if(found!=null)return found;
        if(PATTERNS.size()>=128)PATTERNS.remove(PATTERNS.keySet().iterator().next());var p=new Pattern(query);PATTERNS.put(query,p);return p;
    }
    private static boolean contains(List<String[]> tokens,Pattern pattern,boolean initials){
        String q=pattern.text();if(q.isEmpty())return true;var active=new BitSet(q.length());active.set(0);
        for(var choices:tokens){var next=new BitSet(q.length());for(String reading:choices){String part=initials?reading.substring(0,1):reading;
            for(int state=active.nextSetBit(0);state>=0;state=active.nextSetBit(state+1)){int k=state;for(int i=0;i<part.length();i++){char c=part.charAt(i);while(k>0&&q.charAt(k)!=c)k=pattern.prefix()[k-1];if(q.charAt(k)==c)k++;if(k==q.length())return true;}next.set(k);}
        }active=next;}return false;
    }
    private static boolean anchored(List<String[]> tokens,String q,boolean initials,boolean prefix){
        var active=new BitSet(q.length()+1);active.set(0);
        for(var choices:tokens){var next=new BitSet(q.length()+1);for(String reading:choices){String part=initials?reading.substring(0,1):reading;
            for(int offset=active.nextSetBit(0);offset>=0;offset=active.nextSetBit(offset+1)){
                if(prefix&&part.startsWith(q.substring(offset)))return true;
                if(q.startsWith(part,offset))next.set(offset+part.length());
            }
        }active=next;if(active.isEmpty())return false;}return active.get(q.length());
    }
    static boolean matches(String text,String query){
        String value=normalize(text);for(String term:normalize(query).strip().split("\\s+")){
            if(value.contains(term))continue;
            if(!term.matches("[a-z]+"))return false;
            var p=pattern(term);var parts=tokens(text);if(!contains(parts,p,false)&&!contains(parts,p,true))return false;
        }return true;
    }
    static int rank(String name,String id,String query){
        String q=normalize(query).strip();if(q.isEmpty())return 0;String value=normalize(name);
        if(value.equals(q))return 0;
        if(q.matches("[a-z]+")){var parts=tokens(name);if(anchored(parts,q,false,false)||anchored(parts,q,true,false))return 0;if(anchored(parts,q,false,true)||anchored(parts,q,true,true))return 1;}
        if(value.startsWith(q))return 1;return matches(name,q)?2:3;
    }
}
