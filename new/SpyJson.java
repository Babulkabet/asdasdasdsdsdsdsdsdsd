package dev.packetsender;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор JSON-дампов PacketSpy. Точный формат экспорта не документирован, поэтому разбор терпимый:
 * массив / объект с массивом packets|entries|log|data / по одному объекту на строку; имя пакета берётся
 * из packet|name|type|class|id, направление из direction|dir|side, поля из data|fields|payload|params
 * (объект, JSON-строка или текст «key=value, key=value») либо из самого объекта.
 */
final class SpyJson {
    record Entry(String rawType, String dir, JsonObject fields) {}

    private static final List<String> NAME_KEYS = List.of("packet", "packetname", "name", "type", "packettype", "class", "classname", "id");
    private static final List<String> DIR_KEYS = List.of("direction", "dir", "side", "flow", "bound");
    private static final List<String> FIELD_KEYS = List.of("data", "fields", "payload", "params", "args", "content", "body", "values");
    private static final List<String> LIST_KEYS = List.of("packets", "entries", "log", "logs", "data", "items");
    private static final Pattern KV = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*[=:]\\s*([^,;\\]\\)\\}]+)");

    static List<Entry> parse(String text) {
        text = text.strip();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.isEmpty()) throw new IllegalArgumentException("пустой ввод");
        List<JsonObject> objs = new ArrayList<>();
        try {
            flatten(JsonParser.parseString(text), objs);
        } catch (JsonParseException ex) {
            for (String line : text.split("\\R")) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try { flatten(JsonParser.parseString(line), objs); } catch (JsonParseException ignore) { }
            }
            if (objs.isEmpty()) throw new IllegalArgumentException("не удалось разобрать JSON");
        }
        List<Entry> out = new ArrayList<>();
        for (JsonObject o : objs) out.add(toEntry(o));
        return out;
    }

    private static void flatten(JsonElement e, List<JsonObject> out) {
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) flatten(x, out);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            Map<String, String> keys = keyMap(o);
            for (String k : LIST_KEYS) {
                String real = keys.get(k);
                if (real != null && o.get(real).isJsonArray()) {
                    var arr = o.get(real).getAsJsonArray();
                    if (arr.size() > 0 && arr.get(0).isJsonObject()) { flatten(arr, out); return; }
                }
            }
            out.add(o);
        }
    }

    private static Map<String, String> keyMap(JsonObject o) {
        Map<String, String> m = new HashMap<>();
        for (String k : o.keySet()) m.put(F.key(k), k);
        return m;
    }

    private static Entry toEntry(JsonObject o) {
        Map<String, String> keys = keyMap(o);
        String name = pick(o, keys, NAME_KEYS);
        String dir = pick(o, keys, DIR_KEYS);
        JsonObject fields = null;
        for (String k : FIELD_KEYS) {
            String real = keys.get(k);
            if (real == null) continue;
            fields = asFields(o.get(real));
            if (fields != null) break;
        }
        if (fields == null) fields = o.deepCopy();
        return new Entry(name, direction(dir, name), fields);
    }

    private static String pick(JsonObject o, Map<String, String> keys, List<String> names) {
        for (String k : names) {
            String real = keys.get(k);
            if (real != null && o.get(real).isJsonPrimitive()) return o.get(real).getAsString();
        }
        return null;
    }

    private static JsonObject asFields(JsonElement e) {
        if (e.isJsonObject()) return e.getAsJsonObject();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString().strip();
            if (s.startsWith("{")) {
                try { return JsonParser.parseString(s).getAsJsonObject(); } catch (RuntimeException ignore) { }
            }
            JsonObject o = new JsonObject();
            Matcher m = KV.matcher(s);
            while (m.find()) o.addProperty(m.group(1), m.group(2).trim());
            return o.size() > 0 ? o : null;
        }
        return null;
    }

    static String direction(String d, String name) {
        String x = d == null ? "" : d.toUpperCase(Locale.ROOT);
        if (x.contains("TX") || x.contains("SERVERBOUND") || x.contains("C2S") || x.contains("OUT") || x.contains("SEND")) return "TX";
        if (x.contains("RX") || x.contains("CLIENTBOUND") || x.contains("S2C") || x.equals("IN") || x.contains("INBOUND") || x.contains("RECEIV")) return "RX";
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (n.contains("CLIENTBOUND") || n.contains("S2C")) return "RX";
        return "TX";
    }
}
