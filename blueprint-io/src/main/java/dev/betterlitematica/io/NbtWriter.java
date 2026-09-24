package dev.betterlitematica.io;

import dev.betterlitematica.core.Cancellation;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Budgeted NBT output. New blueprint exports never replace an existing file. */
public final class NbtWriter {
    public interface StreamBytes {int length();void write(DataOutputStream out,Cancellation cancel)throws IOException;}
    public interface StreamLongs {int length();void write(DataOutputStream out,Cancellation cancel)throws IOException;}
    private final Cancellation cancel;
    private int nodes;
    private NbtWriter(Cancellation cancel) { this.cancel = cancel; }
    public static void writeNew(Path file, Map<String, Object> root, Cancellation cancel) throws IOException {
        write(file, root, cancel, false);
    }
    public static void writeSettings(Path file, Map<String, Object> root) throws IOException { write(file, root, Cancellation.NEVER, true); }
    private static void write(Path file, Map<String, Object> root, Cancellation cancel, boolean replace) throws IOException {
        Path target = file.toAbsolutePath(); Files.createDirectories(target.getParent());
        if (!replace && Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Path temporary = Files.createTempFile(target.getParent(), "blueprint-", ".part");
        try {
            try (var out = new DataOutputStream(new BudgetOutput(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)),65536)))) {
                out.writeByte(10); out.writeUTF(""); new NbtWriter(cancel).payload(out, root, 0);
            }
            cancel.check();
            if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target); // No REPLACE_EXISTING: even a concurrent writer cannot overwrite a source.
        } finally { Files.deleteIfExists(temporary); }
    }
    private static int type(Object v) throws IOException {
        if (v instanceof Byte) return 1; if (v instanceof Short) return 2; if (v instanceof Integer) return 3; if (v instanceof Long) return 4;
        if (v instanceof Float) return 5; if (v instanceof Double) return 6; if (v instanceof byte[]||v instanceof StreamBytes) return 7; if (v instanceof String) return 8;
        if (v instanceof List<?>) return 9; if (v instanceof Map<?, ?>) return 10; if (v instanceof int[]) return 11; if (v instanceof long[]||v instanceof StreamLongs) return 12;
        throw new IOException("Unsupported NBT value");
    }
    private void payload(DataOutputStream out, Object value, int depth) throws IOException {
        cancel.check(); if (depth > 64 || ++nodes > 4_000_000) throw new IOException("NBT output nesting/node budget exceeded");
        switch (type(value)) {
            case 1 -> out.writeByte((Byte) value); case 2 -> out.writeShort((Short) value); case 3 -> out.writeInt((Integer) value); case 4 -> out.writeLong((Long) value);
            case 5 -> out.writeFloat((Float) value); case 6 -> out.writeDouble((Double) value); case 8 -> out.writeUTF((String) value);
            case 7 -> {if(value instanceof StreamBytes stream){if(stream.length()<0||stream.length()>512L<<20)throw new IOException("Streaming array exceeds output budget");out.writeInt(stream.length());stream.write(out,cancel);}else{byte[] data = (byte[]) value; out.writeInt(data.length); for (int at = 0; at < data.length; at += 65536) { cancel.check(); out.write(data, at, Math.min(65536, data.length - at)); }}}
            case 9 -> {
                List<?> list = (List<?>) value; int elementType = list.isEmpty() ? 0 : type(list.get(0)); out.writeByte(elementType); out.writeInt(list.size());
                for (Object element : list) { if (type(element) != elementType) throw new IOException("Mixed NBT list types"); payload(out, element, depth + 1); }
            }
            case 10 -> { for (var entry : ((Map<?, ?>) value).entrySet()) { if (!(entry.getKey() instanceof String key)) throw new IOException("Non-string NBT key"); out.writeByte(type(entry.getValue())); out.writeUTF(key); payload(out, entry.getValue(), depth + 1); } out.writeByte(0); }
            case 11 -> { int[] data = (int[]) value; out.writeInt(data.length); for (int i = 0; i < data.length; i++) { if ((i & 8191) == 0) cancel.check(); out.writeInt(data[i]); } }
            case 12 -> {if(value instanceof StreamLongs stream){if(stream.length()<0||stream.length()>((512L<<20)/8))throw new IOException("Streaming long array exceeds output budget");out.writeInt(stream.length());stream.write(out,cancel);}else{long[] data = (long[]) value; out.writeInt(data.length); for (int i = 0; i < data.length; i++) { if ((i & 8191) == 0) cancel.check(); out.writeLong(data[i]); }}}
            default -> throw new IOException("Unknown NBT type");
        }
    }
    private static final class BudgetOutput extends FilterOutputStream {
        private long written;
        BudgetOutput(OutputStream out) { super(out); }
        private void account(int n) throws IOException { written += n; if (written > 512L << 20) throw new IOException("NBT output exceeds 512 MiB"); }
        @Override public void write(int b) throws IOException { account(1); out.write(b); }
        @Override public void write(byte[] data, int off, int len) throws IOException { account(len); out.write(data, off, len); }
    }
}
