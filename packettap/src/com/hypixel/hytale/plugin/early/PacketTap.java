package com.hypixel.hytale.plugin.early;

import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class PacketTap {
    private static final Path ROOT =
            Paths.get(System.getProperty("hytale.tap.dir", System.getenv().getOrDefault("HYTALE_TAP_DIR", "/tmp/srvtap")));
    private static final AtomicLong SEQ = new AtomicLong();
    private static final long T0 = System.currentTimeMillis();
    private static volatile OutputStream INDEX;
    private static final Object LOCK = new Object();

    private static final int MAX_DEPTH = 60;
    private static final long MAX_NODES = 4_000_000L;
    private static final int MAX_DECOMPRESSED = 256 * 1024 * 1024;

    private PacketTap() {}

    private static OutputStream index() {
        OutputStream i = INDEX;
        if (i != null) return i;
        synchronized (LOCK) {
            if (INDEX == null) {
                try {
                    Files.createDirectories(ROOT);
                    INDEX = Files.newOutputStream(ROOT.resolve("index.ndjson"),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println("[PacketTap] writing to " + ROOT.toAbsolutePath() + " (decoded JSON per packet)");
                } catch (Throwable t) {
                    System.err.println("[PacketTap] init failed: " + t);
                }
            }
            return INDEX;
        }
    }

    public static void onSend(Object packet) {
        try {
            if (packet == null) return;
            int id = (int) packet.getClass().getMethod("getId").invoke(packet);
            ClassLoader cl = packet.getClass().getClassLoader();
            Class<?> bbCls = Class.forName("io.netty.buffer.ByteBuf", false, cl);
            Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled", false, cl);
            Object buf = unpooled.getMethod("buffer").invoke(null);
            packet.getClass().getMethod("serialize", bbCls).invoke(packet, buf);
            byte[] payload = readable(buf, bbCls);
            record("S2C", id, packet.getClass().getSimpleName(), payload, packet, null);
        } catch (Throwable t) {
            // never break the server
        }
    }

    public static void onRecv(Object info, Object inBuf, int payloadLength) {
        try {
            if (info == null || inBuf == null || payloadLength < 0) return;
            int id = (int) info.getClass().getMethod("id").invoke(info);
            String name = String.valueOf(info.getClass().getMethod("name").invoke(info));
            boolean compressed;
            try {
                compressed = (boolean) info.getClass().getMethod("compressed").invoke(info);
            } catch (Throwable ignored) {
                compressed = false;
            }

            Class<?> bbCls = inBuf.getClass();
            int ri = (int) method(bbCls, "readerIndex").invoke(inBuf);
            byte[] raw = new byte[payloadLength];
            method(bbCls, "getBytes", int.class, byte[].class).invoke(inBuf, ri, raw);

            byte[] payload = raw;
            String decompressError = null;
            if (compressed && payloadLength > 0) {
                try {
                    ClassLoader cl = inBuf.getClass().getClassLoader();
                    Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd", false, cl);
                    Method getSize = zstd.getMethod("getFrameContentSize", byte[].class);
                    long size = ((Number) getSize.invoke(null, (Object) raw)).longValue();
                    if (size > 0 && size <= MAX_DECOMPRESSED) {
                        Method decomp = zstd.getMethod("decompress", byte[].class, int.class);
                        payload = (byte[]) decomp.invoke(null, raw, (int) size);
                    } else {
                        decompressError = "invalid frame size " + size;
                    }
                } catch (Throwable t) {
                    decompressError = String.valueOf(t);
                }
            }

            Object decoded = null;
            String decodeError = null;
            try {
                ClassLoader cl = inBuf.getClass().getClassLoader();
                Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled", false, cl);
                Object copy = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) payload);
                Object de = info.getClass().getMethod("deserialize").invoke(info);
                Class<?> dfCls = Class.forName("com.hypixel.hytale.protocol.PacketRegistry$DeserializeFunc", false, cl);
                Method m = dfCls.getMethod("deserialize", Object.class, int.class);
                decoded = m.invoke(de, copy, Integer.valueOf(0));
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                decodeError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            }

            record("C2S", id, name, payload, decoded, decompressError != null ? decompressError : decodeError);
        } catch (Throwable t) {
            // never break the server
        }
    }

    private static byte[] readable(Object buf, Class<?> bbCls) throws Exception {
        int n = (int) method(bbCls, "readableBytes").invoke(buf);
        int ri = (int) method(bbCls, "readerIndex").invoke(buf);
        byte[] out = new byte[n];
        method(bbCls, "getBytes", int.class, byte[].class).invoke(buf, ri, out);
        return out;
    }

    private static Method method(Class<?> c, String n, Class<?>... p) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getMethod(n, p);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(c + "#" + n);
    }

    private static void record(String dir, int id, String name, byte[] payload, Object obj, String err) {
        long seq = SEQ.getAndIncrement();
        long t = System.currentTimeMillis() - T0;
        String stem = dir + "/" + id + "/" + String.format("%06d_%db", seq, payload.length);
        boolean decoded = false;

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"seq\":").append(seq);
        json.append(",\"t_ms\":").append(t);
        json.append(",\"dir\":\"").append(dir).append('"');
        json.append(",\"id\":").append(id);
        json.append(",\"name\":\"").append(esc(name)).append('"');
        json.append(",\"bytes\":").append(payload.length);
        json.append(",\"payload_hex\":\"");
        for (byte b : payload) {
            json.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        json.append('"');
        if (obj != null) {
            try {
                StringBuilder body = new StringBuilder(2048);
                toJson(obj, body, 0, new IdentityHashMap<Object, Boolean>(), new long[]{0});
                json.append(",\"decoded\":").append(body);
                decoded = true;
            } catch (Throwable t2) {
                json.append(",\"decoded\":null,\"decode_error\":\"").append(esc(String.valueOf(t2))).append('"');
            }
        } else {
            json.append(",\"decoded\":null");
            if (err != null) {
                json.append(",\"decode_error\":\"").append(esc(err)).append('"');
            }
        }
        json.append("}\n");

        try {
            Path out = ROOT.resolve(stem + ".json");
            Files.createDirectories(out.getParent());
            Files.write(out, json.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {}

        String line = "{\"seq\":" + seq + ",\"t_ms\":" + t + ",\"dir\":\"" + dir
                + "\",\"id\":" + id + ",\"name\":\"" + esc(name) + "\",\"bytes\":" + payload.length
                + ",\"decoded\":" + decoded + ",\"file\":\"" + stem + ".json\"}\n";
        OutputStream i = index();
        if (i == null) return;
        try {
            synchronized (LOCK) {
                i.write(line.getBytes("UTF-8"));
                i.flush();
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void toJson(Object o, StringBuilder sb, int depth, Map<Object, Boolean> seen, long[] budget) {
        if (++budget[0] > MAX_NODES) { sb.append("\"<budget-exceeded>\""); return; }
        if (o == null) { sb.append("null"); return; }
        Class<?> c = o.getClass();

        if (o instanceof Boolean || o instanceof Number) { sb.append(o.toString()); return; }
        if (o instanceof Character || o instanceof CharSequence) { str(o.toString(), sb); return; }
        if (c.isEnum()) { str(((Enum<?>) o).name(), sb); return; }

        if (c.isArray()) {
            if (c.getComponentType() == byte.class) {
                byte[] b = (byte[]) o;
                sb.append("{\"$bytes\":").append(b.length).append(",\"hex\":\"");
                for (byte x : b) { sb.append(HEX[(x >> 4) & 0xf]).append(HEX[x & 0xf]); }
                sb.append("\"}");
                return;
            }
            if (depth >= MAX_DEPTH) { sb.append("\"<max-depth>\""); return; }
            int n = Array.getLength(o);
            sb.append('[');
            for (int k = 0; k < n; k++) {
                if (k > 0) sb.append(',');
                toJson(Array.get(o, k), sb, depth + 1, seen, budget);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            if (depth >= MAX_DEPTH) { sb.append("\"<max-depth>\""); return; }
            sb.append('[');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(','); first = false;
                sb.append("{\"k\":");
                toJson(e.getKey(), sb, depth + 1, seen, budget);
                sb.append(",\"v\":");
                toJson(e.getValue(), sb, depth + 1, seen, budget);
                sb.append('}');
            }
            sb.append(']');
            return;
        }
        if (o instanceof Iterable) {
            if (depth >= MAX_DEPTH) { sb.append("\"<max-depth>\""); return; }
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) o) {
                if (!first) sb.append(','); first = false;
                toJson(e, sb, depth + 1, seen, budget);
            }
            sb.append(']');
            return;
        }

        if (depth >= MAX_DEPTH) { str("<max-depth:" + c.getSimpleName() + ">", sb); return; }
        if (seen.put(o, Boolean.TRUE) != null) { str("<cycle:" + c.getSimpleName() + ">", sb); return; }
        sb.append("{\"$type\":");
        str(c.getSimpleName(), sb);
        if (c.isRecord()) {
            for (java.lang.reflect.RecordComponent rc : c.getRecordComponents()) {
                Object v;
                try { v = rc.getAccessor().invoke(o); } catch (Throwable t) { v = "<err>"; }
                sb.append(',');
                str(rc.getName(), sb);
                sb.append(':');
                toJson(v, sb, depth + 1, seen, budget);
            }
        } else {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || f.isSynthetic()) continue;
                    Object v;
                    try { f.setAccessible(true); v = f.get(o); } catch (Throwable t) { continue; }
                    sb.append(',');
                    str(f.getName(), sb);
                    sb.append(':');
                    toJson(v, sb, depth + 1, seen, budget);
                }
            }
        }
        sb.append('}');
        seen.remove(o);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void str(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
