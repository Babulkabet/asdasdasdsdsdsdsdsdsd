package dev.packetsender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подставляет переменные в строковые значения JSON: "{N}", "{N+1}", "{100+N*2}".
 * Если значение целиком одна переменная, получается число, иначе подстановка в текст ("say {N}").
 *
 * Доступные переменные:
 * N — номер пакета в запуске с 0, K — он же с 1,
 * I — номер повтора текущего пункта очереди с 0, L — номер круга очереди с 0,
 * J — сколько раз текущий пункт уже отправлен за запуск с 0,
 * C — сколько раз пакет этого типа уже отправлен за запуск с 0.
 */
final class Vars {
    private static final Pattern ANY = Pattern.compile("\\{([^{}]+)\\}");
    private static final Pattern WHOLE = Pattern.compile("^\\s*\\{([^{}]+)\\}\\s*$");

    /** Значения счётчиков на момент отправки текущего пакета (все с 0). */
    record Ctx(long n, long i, long loop, long itemSent, long typeSent) {}

    static JsonObject resolve(JsonObject o, long n, long i) {
        return resolve(o, new Ctx(n, i, 0, i, 0));
    }

    static JsonObject resolve(JsonObject o, Ctx c) {
        return walk(o, c).getAsJsonObject();
    }

    private static JsonElement walk(JsonElement e, Ctx c) {
        if (e.isJsonObject()) {
            JsonObject r = new JsonObject();
            for (var en : e.getAsJsonObject().entrySet()) r.add(en.getKey(), walk(en.getValue(), c));
            return r;
        }
        if (e.isJsonArray()) {
            JsonArray r = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) r.add(walk(x, c));
            return r;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            Matcher w = WHOLE.matcher(s);
            if (w.matches()) return new JsonPrimitive(Expr.eval(w.group(1), c));
            Matcher m = ANY.matcher(s);
            if (!m.find()) return e;
            m.reset();
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(Expr.eval(m.group(1), c))));
            m.appendTail(sb);
            return new JsonPrimitive(sb.toString());
        }
        return e;
    }

    private Vars() {}
}
