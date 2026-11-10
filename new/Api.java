package dev.packetsender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** JSON API веб-панели. Все ответы содержат ok:true|false. */
final class Api {
    record Resp(int code, String body) {}

    static final class ApiError extends RuntimeException {
        final int code;
        ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    static Resp handle(String method, String route, String body) {
        try {
            JsonObject req = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
            JsonObject res = switch (route) {
                case "state" -> state();
                case "library" -> library();
                case "presets" -> presets();
                case "preset.save" -> presetSave(req);
                case "preset.rename" -> presetRename(req);
                case "preset.delete" -> presetDelete(req);
                case "import" -> doImport(req);
                case "validate" -> doValidate(req);
                case "send" -> doSend(req);
                case "cancel" -> doCancel(req);
                default -> null;
            };
            if (res == null) return error(404, "нет такого метода");
            res.addProperty("ok", true);
            return new Resp(200, res.toString());
        } catch (ApiError e) {
            return error(e.code, e.getMessage());
        } catch (Exception e) {
            return error(400, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static Resp error(int code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return new Resp(code, o.toString());
    }

    /** Выполнить на главном потоке игры и дождаться результата. */
    static <T> T onMain(Supplier<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ApiError(504, "игра не отвечает");
        }
    }

    private static JsonElement need(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) throw new ApiError(400, "нет параметра " + k);
        return e;
    }

    /** {error, warn}; запускается на главном потоке. */
    private static String[] validate(String type, JsonObject fields) {
        Minecraft mc = Minecraft.getInstance();
        try {
            PacketFactory.prepare(type, Vars.resolve(fields, 0, 0), mc, mc.getConnection());
            return new String[]{"", ""};
        } catch (PacketFactory.NeedsWorld w) {
            return new String[]{"", w.getMessage()};
        } catch (Exception e) {
            return new String[]{e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), ""};
        }
    }

    private static JsonObject state() {
        JsonObject o = new JsonObject();
        o.addProperty("inWorld", Jobs.inWorld);
        o.addProperty("player", Jobs.playerName);
        o.addProperty("totalSent", Jobs.totalSent.get());
        o.addProperty("totalErrors", Jobs.totalErrors.get());
        o.addProperty("maxTotal", PacketSenderMod.cfg.maxTotalPerJob);
        JsonArray jobs = new JsonArray();
        var list = new ArrayList<>(Jobs.LIST);
        for (int i = list.size() - 1; i >= 0 && jobs.size() < 6; i--) jobs.add(list.get(i).toJson());
        o.add("jobs", jobs);
        return o;
    }

    private static JsonObject library() {
        JsonArray a = new JsonArray();
        for (PacketFactory.Def d : PacketFactory.DEFS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("category", d.category());
            o.add("defaults", d.defaults());
            a.add(o);
        }
        JsonObject res = new JsonObject();
        res.add("types", a);
        return res;
    }

    private static JsonObject presets() {
        JsonObject res = new JsonObject();
        res.add("presets", Presets.list());
        return res;
    }

    private static JsonObject presetSave(JsonObject req) {
        String id = req.has("id") && !req.get("id").isJsonNull() ? req.get("id").getAsString() : null;
        String name = req.has("name") && !req.get("name").isJsonNull() ? req.get("name").getAsString() : "";
        JsonArray items = need(req, "items").getAsJsonArray();
        int repeat = req.has("repeat") ? req.get("repeat").getAsInt() : 1;
        int intervalMs = req.has("intervalMs") ? req.get("intervalMs").getAsInt() : 0;
        return Presets.save(id, name, items, repeat, intervalMs);
    }

    private static JsonObject presetRename(JsonObject req) {
        return Presets.rename(need(req, "id").getAsString(), need(req, "name").getAsString());
    }

    private static JsonObject presetDelete(JsonObject req) {
        boolean deleted = Presets.delete(need(req, "id").getAsString());
        if (!deleted) throw new ApiError(404, "конфиг не найден");
        JsonObject res = new JsonObject();
        res.addProperty("deleted", true);
        return res;
    }

    private static JsonObject doImport(JsonObject req) {
        final List<SpyJson.Entry> es = parseSpy(need(req, "text").getAsString());
        if (es.size() > 20000) throw new ApiError(400, "слишком много записей (максимум 20000)");
        JsonArray arr = onMain(() -> {
            JsonArray a = new JsonArray();
            for (SpyJson.Entry e : es) {
                JsonObject o = new JsonObject();
                PacketFactory.Def d = PacketFactory.find(e.rawType());
                String type = d != null ? d.id() : (e.rawType() == null ? "?" : e.rawType());
                String err = "", warn = "";
                if (e.rawType() == null) err = "в записи нет названия пакета";
                else if (e.dir().equals("RX")) err = "входящий пакет (RX) отправить нельзя";
                else {
                    String[] v = validate(type, e.fields());
                    err = v[0];
                    warn = v[1];
                }
                o.addProperty("type", type);
                o.addProperty("dir", e.dir());
                o.add("fields", e.fields());
                o.addProperty("error", err);
                o.addProperty("warn", warn);
                a.add(o);
            }
            return a;
        });
        JsonObject res = new JsonObject();
        res.add("entries", arr);
        return res;
    }

    private static List<SpyJson.Entry> parseSpy(String text) {
        try {
            return SpyJson.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    private static JsonObject doValidate(JsonObject req) {
        String type = need(req, "type").getAsString();
        JsonObject fields = need(req, "fields").getAsJsonObject();
        String[] v = onMain(() -> validate(type, fields));
        JsonObject res = new JsonObject();
        res.addProperty("error", v[0]);
        res.addProperty("warn", v[1]);
        return res;
    }

    private static JsonObject doSend(JsonObject req) {
        if (!Jobs.inWorld) throw new ApiError(409, "Зайдите в мир или на сервер");
        JsonArray arr = need(req, "items").getAsJsonArray();
        int repeat = Math.max(1, req.has("repeat") ? req.get("repeat").getAsInt() : 1);
        long interval = Math.max(0, Math.min(60000, req.has("intervalMs") ? req.get("intervalMs").getAsLong() : 0));
        List<Jobs.Item> items = new ArrayList<>();
        long total = 0;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            int count = Math.max(1, o.has("count") ? o.get("count").getAsInt() : 1);
            PacketFactory.Def d = PacketFactory.find(o.get("type").getAsString());
            if (d == null) throw new ApiError(400, "пакет «" + o.get("type").getAsString() + "» не поддерживается");
            items.add(new Jobs.Item(d.id(), o.get("fields").getAsJsonObject(), count));
            total += count;
        }
        if (items.isEmpty()) throw new ApiError(400, "очередь пуста");
        total *= repeat;
        int max = PacketSenderMod.cfg.maxTotalPerJob;
        if (total > max) throw new ApiError(400, "лимит за запуск: " + max + " пакетов (config/packetsender.json), сейчас " + total);
        for (Jobs.Item it : items) {
            String[] v = onMain(() -> validate(it.type(), it.fields()));
            if (!v[0].isEmpty()) throw new ApiError(400, it.type() + ": " + v[0]);
        }
        Jobs.Job j = Jobs.start(items, repeat, interval);
        JsonObject res = new JsonObject();
        res.addProperty("jobId", j.id);
        res.addProperty("total", j.total);
        return res;
    }

    private static JsonObject doCancel(JsonObject req) {
        if (req.has("id")) Jobs.cancel(req.get("id").getAsInt());
        else Jobs.cancelAll();
        return new JsonObject();
    }

    private Api() {}
}
