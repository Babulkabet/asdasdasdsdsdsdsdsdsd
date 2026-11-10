package dev.packetsender;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Очередь запусков. Отправка идёт только из главного потока (END_CLIENT_TICK). */
final class Jobs {
    record Item(String type, JsonObject fields, int count) {}

    static final List<Job> LIST = new CopyOnWriteArrayList<>();
    static final AtomicLong totalSent = new AtomicLong();
    static final AtomicLong totalErrors = new AtomicLong();
    private static final AtomicInteger SEQ = new AtomicInteger();

    static volatile boolean inWorld;
    static volatile String playerName = "";

    static final class Job {
        final int id = SEQ.incrementAndGet();
        final List<Item> items;
        final int repeat;
        final long intervalNanos;
        final long total;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        volatile String state = "running"; // running | done | cancelled | error
        volatile String lastError = "";
        private int loop, itemIdx, itemSent;
        private long n; // номер пакета в запуске, переменная {N}
        private long nextAt;
        private final long[] itemSentTotal; // сколько раз каждый пункт уже отправлен, переменная {J}
        private final Map<String, Long> typeSentTotal = new HashMap<>(); // {C} по типу пакета

        Job(List<Item> items, int repeat, long intervalMs) {
            this.items = items;
            this.repeat = repeat;
            this.intervalNanos = intervalMs * 1_000_000L;
            this.itemSentTotal = new long[items.size()];
            long t = 0;
            for (Item i : items) t += i.count();
            this.total = t * repeat;
        }

        boolean running() { return "running".equals(state); }

        void finish(String st, String err) {
            if (err != null) lastError = err;
            state = st;
        }

        private boolean advance() {
            if (++itemSent >= items.get(itemIdx).count()) {
                itemSent = 0;
                if (++itemIdx >= items.size()) {
                    itemIdx = 0;
                    if (++loop >= repeat) return false;
                }
            }
            return true;
        }

        void tick(Minecraft mc, int maxPerTick) {
            ClientPacketListener c = mc.getConnection();
            if (c == null) { finish("error", "Нет подключения к серверу"); return; }
            long now = System.nanoTime();
            if (nextAt == 0) nextAt = now;
            int budget = maxPerTick;
            while (budget-- > 0 && running() && now >= nextAt) {
                Item it = items.get(itemIdx);
                try {
                    Vars.Ctx ctx = new Vars.Ctx(n, itemSent, loop, itemSentTotal[itemIdx],
                            typeSentTotal.getOrDefault(it.type(), 0L));
                    PacketFactory.prepare(it.type(), Vars.resolve(it.fields(), ctx), mc, c).run();
                    sent.incrementAndGet();
                    totalSent.incrementAndGet();
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    totalErrors.incrementAndGet();
                    lastError = it.type() + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                    if (errors.get() >= 3) { finish("error", lastError); return; }
                }
                n++;
                itemSentTotal[itemIdx]++;
                typeSentTotal.merge(it.type(), 1L, Long::sum);
                if (!advance()) { finish("done", null); return; }
                nextAt = intervalNanos == 0 ? now : Math.max(nextAt + intervalNanos, now - 50_000_000L);
            }
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("state", state);
            o.addProperty("sent", sent.get());
            o.addProperty("total", total);
            o.addProperty("errors", errors.get());
            o.addProperty("lastError", lastError);
            return o;
        }
    }

    static Job start(List<Item> items, int repeat, long intervalMs) {
        Job j = new Job(items, repeat, intervalMs);
        LIST.add(j);
        while (LIST.size() > 20) {
            Job old = LIST.get(0);
            if (old.running()) break;
            LIST.remove(old);
        }
        return j;
    }

    static void cancel(int id) {
        for (Job j : LIST) if (j.id == id && j.running()) j.finish("cancelled", null);
    }

    static void cancelAll() {
        for (Job j : LIST) if (j.running()) j.finish("cancelled", null);
    }

    static void tick(Minecraft mc) {
        var p = mc.player;
        inWorld = mc.getConnection() != null && p != null;
        playerName = p != null ? p.getName().getString() : "";
        for (Job j : LIST) if (j.running()) j.tick(mc, PacketSenderMod.cfg.maxPerTick);
    }

    private Jobs() {}
}
