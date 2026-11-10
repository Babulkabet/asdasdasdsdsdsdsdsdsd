package dev.packetsender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * config/packetsender-presets.json — именованные наборы пакетов («конфиги»).
 * Файл общий для всех браузеров, поэтому очереди не теряются при очистке localStorage.
 */
final class Presets {
    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("packetsender-presets.json");
    private static final int MAX_ITEMS = 5000;
    private static final int MAX_NAME = 64;
    private static final List<Preset> LIST = new ArrayList<>();
    private static boolean loaded;

    static final class Preset {
        String id = "";
        String name = "";
        long updatedAt;
        int repeat = 1;
        int intervalMs;
        JsonArray items = new JsonArray();
    }

    static JsonArray list() {
        synchronized (LOCK) {
            ensure();
            JsonArray a = new JsonArray();
            for (Preset p : LIST) a.add(toJson(p));
            return a;
        }
    }

    /** Создаёт новый конфиг (id == null) или перезаписывает существующий. */
    static JsonObject save(String id, String name, JsonArray items, int repeat, int intervalMs) {
        synchronized (LOCK) {
            ensure();
            Preset p = find(id);
            if (p == null) {
                p = new Preset();
                p.id = UUID.randomUUID().toString();
                LIST.add(p);
            }
            p.name = name(name);
            p.items = clean(items);
            p.repeat = Math.max(1, Math.min(100_000, repeat));
            p.intervalMs = Math.max(0, Math.min(60_000, intervalMs));
            p.updatedAt = System.currentTimeMillis();
            write();
            return toJson(p);
        }
    }

    static JsonObject rename(String id, String name) {
        synchronized (LOCK) {
            ensure();
            Preset p = find(id);
            if (p == null) throw new Api.ApiError(404, "конфиг не найден");
            p.name = name(name);
            p.updatedAt = System.currentTimeMillis();
            write();
            return toJson(p);
        }
    }

    static boolean delete(String id) {
        synchronized (LOCK) {
            ensure();
            Preset p = find(id);
            if (p == null) return false;
            LIST.remove(p);
            write();
            return true;
        }
    }

    private static Preset find(String id) {
        if (id == null || id.isBlank()) return null;
        for (Preset p : LIST) if (p.id.equals(id)) return p;
        return null;
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (n.isEmpty()) n = "Без названия";
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME).strip();
        return n;
    }

    private static JsonArray clean(JsonArray in) {
        JsonArray out = new JsonArray();
        if (in == null) return out;
        for (JsonElement e : in) {
            if (out.size() >= MAX_ITEMS) break;
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonElement type = o.get("type");
            if (type == null || !type.isJsonPrimitive() || type.getAsString().isBlank()) continue;
            JsonElement fields = o.get("fields");
            JsonObject f = fields != null && fields.isJsonObject()
                    ? fields.getAsJsonObject().deepCopy() : new JsonObject();
            JsonObject c = new JsonObject();
            c.addProperty("type", type.getAsString());
            c.add("fields", f);
            c.addProperty("count", clampInt(o, "count", 1, 1, 1_000_000));
            c.addProperty("on", !o.has("on") || o.get("on").isJsonNull() || o.get("on").getAsBoolean());
            out.add(c);
        }
        return out;
    }

    private static int clampInt(JsonObject o, String key, int def, int lo, int hi) {
        int v = def;
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) v = o.get(key).getAsInt();
        } catch (RuntimeException ignore) {
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private static JsonObject toJson(Preset p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("name", p.name);
        o.addProperty("updatedAt", p.updatedAt);
        o.addProperty("repeat", p.repeat);
        o.addProperty("intervalMs", p.intervalMs);
        o.add("items", p.items.deepCopy());
        return o;
    }

    private static void ensure() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(FILE));
            JsonArray a = root.isJsonArray() ? root.getAsJsonArray()
                    : root.isJsonObject() && root.getAsJsonObject().has("presets")
                    ? root.getAsJsonObject().getAsJsonArray("presets") : null;
            if (a == null) return;
            for (JsonElement e : a) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                Preset p = new Preset();
                p.id = str(o, "id", UUID.randomUUID().toString());
                p.name = name(str(o, "name", "Без названия"));
                p.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0L;
                p.repeat = clampInt(o, "repeat", 1, 1, 100_000);
                p.intervalMs = clampInt(o, "intervalMs", 0, 0, 60_000);
                p.items = o.has("items") && o.get("items").isJsonArray()
                        ? clean(o.getAsJsonArray("items")) : new JsonArray();
                LIST.add(p);
            }
        } catch (Exception e) {
            PacketSenderMod.LOG.warn("Не удалось прочитать {}: {}", FILE, e.getMessage());
        }
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static void write() {
        try {
            JsonObject root = new JsonObject();
            JsonArray a = new JsonArray();
            for (Preset p : LIST) a.add(toJson(p));
            root.add("presets", a);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (Exception e) {
            PacketSenderMod.LOG.warn("Не удалось сохранить {}: {}", FILE, e.getMessage());
            throw new Api.ApiError(500, "не удалось записать config/packetsender-presets.json");
        }
    }

    private Presets() {}
}
