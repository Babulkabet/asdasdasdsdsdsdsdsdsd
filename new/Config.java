package dev.packetsender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/packetsender.json */
final class Config {
    int port = 8890;
    int maxTotalPerJob = 10000; // максимум пакетов за один запуск
    int maxPerTick = 50;        // максимум пакетов за тик (50 = до 1000 пакетов/с)

    static Config load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("packetsender.json");
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(p)) {
                Config c = g.fromJson(Files.readString(p), Config.class);
                if (c != null) { c.clamp(); return c; }
            }
            Config c = new Config();
            Files.writeString(p, g.toJson(c));
            return c;
        } catch (Exception e) {
            return new Config();
        }
    }

    private void clamp() {
        port = Math.max(1024, Math.min(65000, port));
        maxTotalPerJob = Math.max(1, Math.min(1_000_000, maxTotalPerJob));
        maxPerTick = Math.max(1, Math.min(500, maxPerTick));
    }
}
