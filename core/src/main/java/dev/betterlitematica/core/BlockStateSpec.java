package dev.betterlitematica.core;

import java.util.*;
import java.util.regex.Pattern;

/** Registry-independent identity. Unknown names and properties are never silently discarded. */
public record BlockStateSpec(String name, Map<String, String> properties) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_.-]+");
    public static final BlockStateSpec AIR = new BlockStateSpec("minecraft:air", Map.of());
    public BlockStateSpec {
        Objects.requireNonNull(name); Objects.requireNonNull(properties);
        if (!name.contains(":")) name = "minecraft:" + name;
        if (!NAME.matcher(name).matches() || name.length() > 1024) throw new IllegalArgumentException("Invalid block name: " + name);
        if (properties.size() > 128) throw new IllegalArgumentException("Too many block properties");
        TreeMap<String,String> copy = new TreeMap<>();
        properties.forEach((k,v) -> {
            if (k == null || v == null || !TOKEN.matcher(k).matches() || !TOKEN.matcher(v).matches())
                throw new IllegalArgumentException("Invalid block property");
            copy.put(k,v);
        });
        properties = Collections.unmodifiableMap(copy);
    }
    public static BlockStateSpec parse(String text) {
        Objects.requireNonNull(text);
        int open = text.indexOf('[');
        if (open < 0) return new BlockStateSpec(text, Map.of());
        if (!text.endsWith("]") || text.indexOf('[',open+1) >= 0) throw new IllegalArgumentException("Malformed block state: " + text);
        TreeMap<String,String> p = new TreeMap<>();
        String body = text.substring(open+1,text.length()-1);
        if (!body.isEmpty()) for (String part : body.split(",", -1)) {
            String[] kv = part.split("=", -1);
            if (kv.length != 2 || p.putIfAbsent(kv[0],kv[1]) != null) throw new IllegalArgumentException("Malformed/duplicate property: " + part);
        }
        return new BlockStateSpec(text.substring(0,open),p);
    }
    public boolean isAir() { return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air"); }
    @Override public String toString() {
        if (properties.isEmpty()) return name;
        StringJoiner j = new StringJoiner(",", name+"[", "]");
        properties.forEach((k,v) -> j.add(k+"="+v)); return j.toString();
    }
}
