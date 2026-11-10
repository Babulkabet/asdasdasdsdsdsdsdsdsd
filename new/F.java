package dev.packetsender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Доступ к полям JSON без учёта регистра, «_» и «-» в именах ключей. Без зависимостей от Minecraft. */
final class F {
    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final Pattern INT = Pattern.compile("-?\\d+");
    private final Map<String, JsonElement> m = new HashMap<>();

    F(JsonObject o) {
        for (var e : o.entrySet()) m.put(key(e.getKey()), e.getValue());
    }

    static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    JsonElement get(String... names) {
        for (String n : names) {
            JsonElement e = m.get(key(n));
            if (e != null && !e.isJsonNull()) return e;
        }
        return null;
    }

    boolean has(String... n) { return get(n) != null; }

    static IllegalArgumentException missing(String n) {
        return new IllegalArgumentException("нет поля «" + n + "»");
    }

    static double num(JsonElement e) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            Matcher mt = NUM.matcher(p.getAsString());
            if (mt.find()) return Double.parseDouble(mt.group());
        }
        throw new IllegalArgumentException("ожидалось число, получено: " + e);
    }

    Double optD(String... n) { JsonElement e = get(n); return e == null ? null : num(e); }
    double dbl(double def, String... n) { Double d = optD(n); return d == null ? def : d; }
    double reqD(String... n) { Double d = optD(n); if (d == null) throw missing(n[0]); return d; }
    int integer(int def, String... n) { Double d = optD(n); return d == null ? def : (int) Math.round(d); }
    int reqInt(String... n) { return (int) Math.round(reqD(n)); }

    long lng(long def, String... n) {
        JsonElement e = get(n);
        if (e == null) return def;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsLong();
            Matcher mt = INT.matcher(p.getAsString());
            if (mt.find()) return Long.parseLong(mt.group());
        }
        throw new IllegalArgumentException("ожидалось целое число, получено: " + e);
    }

    boolean bool(boolean def, String... n) {
        JsonElement e = get(n);
        if (e == null) return def;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            String s = p.getAsString().trim().toLowerCase(Locale.ROOT);
            return s.equals("true") || s.equals("1") || s.equals("yes");
        }
        return def;
    }

    String optStr(String def, String... n) {
        JsonElement e = get(n);
        if (e == null) return def;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    String str(String... n) {
        String s = optStr(null, n);
        if (s == null) throw missing(n[0]);
        return s;
    }

    /** Тройка чисел: {"x":..,"y":..,"z":..}, [x,y,z] или строка вида "BlockPos{x=1, y=2, z=3}". null, если поля нет. */
    double[] triple(String... n) {
        JsonElement e = get(n);
        if (e == null) return null;
        if (e.isJsonObject()) {
            F g = new F(e.getAsJsonObject());
            return new double[]{g.reqD("x"), g.reqD("y"), g.reqD("z")};
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() >= 3) return new double[]{num(a.get(0)), num(a.get(1)), num(a.get(2))};
        } else if (e.isJsonPrimitive()) {
            Matcher mt = NUM.matcher(e.getAsString());
            double[] r = new double[3];
            int i = 0;
            while (i < 3 && mt.find()) r[i++] = Double.parseDouble(mt.group());
            if (i == 3) return r;
        }
        throw new IllegalArgumentException("«" + n[0] + "»: ожидалось [x, y, z] или {x, y, z}");
    }

    <E extends Enum<E>> E en(Class<E> c, String def, String... n) {
        String s = optStr(def, n).trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(c, s);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("«" + n[0] + "»: допустимо " + Arrays.toString(c.getEnumConstants()));
        }
    }
}
