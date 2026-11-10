package dev.packetsender;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundDebugSampleSubscriptionPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.debugchart.RemoteDebugSampleType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Реестр поддерживаемых serverbound-пакетов (имена совпадают с id протокола, которые показывает PacketSpy,
 * например move_player_pos). prepare() разбирает поля и возвращает действие отправки; само действие
 * вызывается только из главного потока клиента.
 */
final class PacketFactory {
    interface Builder { Runnable prepare(F f, Minecraft mc, ClientPacketListener c) throws Exception; }

    record Def(String id, String category, JsonObject defaults, Builder builder) {}

    /** Не ошибка в данных, а «сейчас нет игрока/мира» — проверить поля полностью нельзя. */
    static final class NeedsWorld extends RuntimeException {
        NeedsWorld() { super("нужен мир: часть значений берётся у игрока"); }
    }

    static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void reg(String id, String category, String defaults, Builder b) {
        DEFS.put(id, new Def(id, category, JsonParser.parseString(defaults).getAsJsonObject(), b));
    }

    /** minecraft:move_player_pos, serverbound/minecraft:move_player_pos, ServerboundMovePlayerPacket$Pos → move_player_pos */
    static String norm(String raw) {
        String s = raw.trim();
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.substring(s.lastIndexOf(':') + 1);
        s = s.substring(s.lastIndexOf('.') + 1);
        s = s.replace('$', '_').replace('-', '_').replace(' ', '_');
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        s = s.replaceAll("^serverbound_", "").replaceAll("_packet", "").replaceAll("_+", "_");
        return s;
    }

    /** Имена из других маппингов (Yarn) → наш id. Ключ: имя без регистра и разделителей. */
    private static final Map<String, String> ALIAS = Map.of(
            "clickslotc2spacket", "container_click",
            "clickslotc2s", "container_click",
            "clickslot", "container_click");

    static Def find(String raw) {
        if (raw == null) return null;
        String a = ALIAS.get(F.key(raw.substring(raw.lastIndexOf('.') + 1)));
        return DEFS.get(a != null ? a : norm(raw));
    }

    static Runnable prepare(String type, JsonObject fields, Minecraft mc, ClientPacketListener c) throws Exception {
        Def d = find(type);
        if (d == null) throw new IllegalArgumentException("пакет «" + type + "» не поддерживается");
        return d.builder().prepare(new F(fields), mc, c);
    }

    // ---- помощники, зависящие от Minecraft ----
    private static LocalPlayer me(Minecraft mc) {
        if (mc.player == null) throw new NeedsWorld();
        return mc.player;
    }

    private static double[] xyz(F f, Minecraft mc) {
        double[] t = f.triple("pos", "position", "location");
        if (t != null) return t;
        Double x = f.optD("x"), y = f.optD("y"), z = f.optD("z");
        if (x == null && y == null && z == null) {
            LocalPlayer p = me(mc);
            return new double[]{p.getX(), p.getY(), p.getZ()};
        }
        if (x == null || y == null || z == null) throw new IllegalArgumentException("нужны все три: x, y, z");
        return new double[]{x, y, z};
    }

    private static float[] rot(F f, Minecraft mc) {
        float yaw = f.has("yaw", "yRot") ? (float) f.dbl(0, "yaw", "yRot") : me(mc).getYRot();
        float pitch = f.has("pitch", "xRot") ? (float) f.dbl(0, "pitch", "xRot") : me(mc).getXRot();
        return new float[]{yaw, pitch};
    }

    private static BlockPos blockPos(F f) {
        double[] t = f.triple("pos", "blockPos", "position", "block");
        if (t == null) t = new double[]{f.reqD("x"), f.reqD("y"), f.reqD("z")};
        return new BlockPos((int) Math.floor(t[0]), (int) Math.floor(t[1]), (int) Math.floor(t[2]));
    }

    private static InteractionHand hand(F f) {
        return f.optStr("MAIN_HAND", "hand").toUpperCase(Locale.ROOT).contains("OFF")
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** Массив строк из массива или одиночного значения. */
    private static List<String> strings(F f, String... n) {
        JsonElement e = f.get(n);
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.isJsonPrimitive() ? x.getAsString() : x.toString());
        } else {
            out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
        }
        return out;
    }

    private static String[] signLines(F f) {
        List<String> in = strings(f, "lines", "text", "sign");
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = i < in.size() ? in.get(i) : "";
        return out;
    }

    private static UUID uuid(F f, String... n) {
        String s = f.str(n);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("«" + n[0] + "»: ожидался UUID, получено «" + s + "»");
        }
    }

    private static ItemStack stack(F f) {
        String id = f.str("item", "itemId", "id");
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null) throw new IllegalArgumentException("неизвестный предмет «" + id + "» (например minecraft:stone)");
        int count = Math.max(1, Math.min(99, f.integer(1, "count", "amount")));
        return new ItemStack(item, count);
    }

    /** id эффекта как в протоколе; 0 или отсутствие поля = пусто. */
    private static Optional<Holder<MobEffect>> effect(F f, String... n) {
        if (!f.has(n)) return Optional.empty();
        int id = f.integer(0, n);
        if (id <= 0) return Optional.empty();
        Optional<Holder.Reference<MobEffect>> h = BuiltInRegistries.MOB_EFFECT.get(id);
        if (h.isEmpty()) throw new IllegalArgumentException("«" + n[0] + "»: неизвестный id эффекта " + id);
        return Optional.of(h.get());
    }

    static {
        reg("chat", "Чат", "{\"message\":\"Hello\"}", (f, mc, c) -> {
            String msg = f.str("message", "msg", "text", "content");
            return () -> c.sendChat(msg);
        });
        reg("chat_command", "Чат", "{\"command\":\"help\"}", (f, mc, c) -> {
            String cmd = f.str("command", "cmd");
            String s = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            return () -> c.send(new ServerboundChatCommandPacket(s));
        });
        reg("command_suggestion", "Чат", "{\"id\":0,\"command\":\"/gamemode \"}", (f, mc, c) -> {
            int id = f.integer(0, "id", "completionId");
            String cmd = f.str("command", "cmd", "partialCommand");
            return () -> c.send(new ServerboundCommandSuggestionPacket(id, cmd));
        });
        reg("chat_ack", "Чат", "{\"offset\":0}", (f, mc, c) -> {
            int offset = f.integer(0, "offset");
            return () -> c.send(new ServerboundChatAckPacket(offset));
        });

        reg("move_player_pos", "Движение", "{\"onGround\":true}", (f, mc, c) -> {
            double[] p = xyz(f, mc);
            boolean g = f.bool(true, "onGround"), h = f.bool(false, "horizontalCollision");
            return () -> c.send(new ServerboundMovePlayerPacket.Pos(p[0], p[1], p[2], g, h));
        });
        reg("move_player_pos_rot", "Движение", "{\"onGround\":true}", (f, mc, c) -> {
            double[] p = xyz(f, mc);
            float[] r = rot(f, mc);
            boolean g = f.bool(true, "onGround"), h = f.bool(false, "horizontalCollision");
            return () -> c.send(new ServerboundMovePlayerPacket.PosRot(p[0], p[1], p[2], r[0], r[1], g, h));
        });
        reg("move_player_rot", "Движение", "{\"onGround\":true}", (f, mc, c) -> {
            float[] r = rot(f, mc);
            boolean g = f.bool(true, "onGround"), h = f.bool(false, "horizontalCollision");
            return () -> c.send(new ServerboundMovePlayerPacket.Rot(r[0], r[1], g, h));
        });
        reg("move_player_status_only", "Движение", "{\"onGround\":true}", (f, mc, c) -> {
            boolean g = f.bool(true, "onGround"), h = f.bool(false, "horizontalCollision");
            return () -> c.send(new ServerboundMovePlayerPacket.StatusOnly(g, h));
        });
        reg("player_command", "Движение", "{\"action\":\"START_SPRINTING\"}", (f, mc, c) -> {
            var a = f.en(ServerboundPlayerCommandPacket.Action.class, "START_SPRINTING", "action");
            Entity e = me(mc);
            return () -> c.send(new ServerboundPlayerCommandPacket(e, a));
        });
        reg("client_tick_end", "Движение", "{}", (f, mc, c) -> () -> c.send(ServerboundClientTickEndPacket.INSTANCE));
        reg("player_input", "Движение",
                "{\"forward\":false,\"backward\":false,\"left\":false,\"right\":false,\"jump\":false,\"shift\":false,\"sprint\":false}",
                (f, mc, c) -> {
                    Input input = new Input(
                            f.bool(false, "forward"), f.bool(false, "backward"),
                            f.bool(false, "left"), f.bool(false, "right"),
                            f.bool(false, "jump"), f.bool(false, "shift", "sneak"),
                            f.bool(false, "sprint"));
                    return () -> c.send(new ServerboundPlayerInputPacket(input));
                });
        reg("move_vehicle", "Движение", "{\"onGround\":true}", (f, mc, c) -> {
            double[] p = xyz(f, mc);
            float[] r = rot(f, mc);
            boolean g = f.bool(true, "onGround");
            return () -> c.send(new ServerboundMoveVehiclePacket(new Vec3(p[0], p[1], p[2]), r[0], r[1], g));
        });
        reg("paddle_boat", "Движение", "{\"left\":false,\"right\":false}", (f, mc, c) -> {
            boolean l = f.bool(false, "left"), r = f.bool(false, "right");
            return () -> c.send(new ServerboundPaddleBoatPacket(l, r));
        });
        reg("accept_teleportation", "Движение", "{\"teleportId\":0}", (f, mc, c) -> {
            int id = f.integer(0, "teleportId", "id");
            return () -> c.send(new ServerboundAcceptTeleportationPacket(id));
        });
        reg("teleport_to_entity", "Движение", "{\"uuid\":\"00000000-0000-0000-0000-000000000000\"}", (f, mc, c) -> {
            UUID id = uuid(f, "uuid", "id");
            return () -> c.send(new ServerboundTeleportToEntityPacket(id));
        });
        reg("player_loaded", "Движение", "{}", (f, mc, c) -> () -> c.send(new ServerboundPlayerLoadedPacket()));

        reg("swing", "Действия", "{\"hand\":\"MAIN_HAND\"}", (f, mc, c) -> {
            InteractionHand h = hand(f);
            return () -> c.send(new ServerboundSwingPacket(h));
        });
        reg("set_carried_item", "Действия", "{\"slot\":0}", (f, mc, c) -> {
            int slot = f.reqInt("slot", "selectedSlot");
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("«slot» должен быть от 0 до 8");
            return () -> c.send(new ServerboundSetCarriedItemPacket(slot));
        });
        reg("use_item", "Действия", "{\"hand\":\"MAIN_HAND\",\"sequence\":0}", (f, mc, c) -> {
            InteractionHand h = hand(f);
            int seq = f.integer(0, "sequence", "seq");
            float[] r = rot(f, mc);
            return () -> c.send(new ServerboundUseItemPacket(h, seq, r[0], r[1]));
        });
        reg("use_item_on", "Действия",
                "{\"hand\":\"MAIN_HAND\",\"pos\":[0,64,0],\"direction\":\"UP\",\"inside\":false,\"sequence\":0}", (f, mc, c) -> {
                    InteractionHand h = hand(f);
                    BlockPos pos = blockPos(f);
                    Direction dir = f.en(Direction.class, "UP", "direction", "face", "side");
                    double[] l = f.triple("location", "hitVec", "hit");
                    Vec3 loc = l != null ? new Vec3(l[0], l[1], l[2]) : Vec3.atCenterOf(pos);
                    boolean inside = f.bool(false, "inside");
                    int seq = f.integer(0, "sequence", "seq");
                    return () -> c.send(new ServerboundUseItemOnPacket(h, new BlockHitResult(loc, dir, pos, inside), seq));
                });
        reg("player_action", "Действия",
                "{\"action\":\"START_DESTROY_BLOCK\",\"pos\":[0,64,0],\"direction\":\"UP\",\"sequence\":0}", (f, mc, c) -> {
                    var a = f.en(ServerboundPlayerActionPacket.Action.class, "START_DESTROY_BLOCK", "action");
                    BlockPos pos = f.has("pos", "blockPos", "position", "block", "x") ? blockPos(f) : BlockPos.ZERO;
                    Direction dir = f.en(Direction.class, "DOWN", "direction", "face", "side");
                    int seq = f.integer(0, "sequence", "seq");
                    return () -> c.send(new ServerboundPlayerActionPacket(a, pos, dir, seq));
                });
        reg("interact", "Действия", "{\"entityId\":0,\"action\":\"attack\",\"sneaking\":false}", (f, mc, c) -> {
            int id = f.reqInt("entityId", "id", "entity");
            boolean sneak = f.bool(false, "sneaking", "secondary", "usingSecondaryAction");
            boolean attack = f.optStr("attack", "action", "type").toLowerCase(Locale.ROOT).contains("attack");
            InteractionHand h = hand(f);
            if (mc.level == null) throw new NeedsWorld();
            Entity e = mc.level.getEntity(id);
            if (e == null) throw new IllegalArgumentException("сущность " + id + " не найдена (нужна загруженная рядом)");
            Packet<?> pk = attack
                    ? ServerboundInteractPacket.createAttackPacket(e, sneak)
                    : ServerboundInteractPacket.createInteractionPacket(e, sneak, h);
            return () -> c.send(pk);
        });
        reg("player_abilities", "Действия", "{\"flying\":false}", (f, mc, c) -> {
            Abilities a = new Abilities();
            a.flying = f.bool(false, "flying");
            return () -> c.send(new ServerboundPlayerAbilitiesPacket(a));
        });
        reg("pick_item_from_block", "Действия", "{\"pos\":[0,64,0],\"includeData\":false}", (f, mc, c) -> {
            BlockPos pos = blockPos(f);
            boolean data = f.bool(false, "includeData", "includeDataComponents");
            return () -> c.send(new ServerboundPickItemFromBlockPacket(pos, data));
        });
        reg("pick_item_from_entity", "Действия", "{\"entityId\":0,\"includeData\":false}", (f, mc, c) -> {
            int id = f.reqInt("entityId", "id", "entity");
            boolean data = f.bool(false, "includeData", "includeDataComponents");
            return () -> c.send(new ServerboundPickItemFromEntityPacket(id, data));
        });
        reg("select_trade", "Действия", "{\"item\":0}", (f, mc, c) -> {
            int item = f.integer(0, "item", "index", "trade");
            return () -> c.send(new ServerboundSelectTradePacket(item));
        });
        reg("select_bundle_item", "Действия", "{\"slotId\":0,\"selectedItemIndex\":0}", (f, mc, c) -> {
            int slot = f.integer(0, "slotId", "slot");
            int idx = f.integer(0, "selectedItemIndex", "index");
            return () -> c.send(new ServerboundSelectBundleItemPacket(slot, idx));
        });
        reg("rename_item", "Действия", "{\"name\":\"Item\"}", (f, mc, c) -> {
            String name = f.str("name", "text");
            return () -> c.send(new ServerboundRenameItemPacket(name));
        });
        reg("edit_book", "Действия", "{\"slot\":0,\"pages\":[\"Страница 1\"],\"title\":\"Книга\"}", (f, mc, c) -> {
            int slot = f.integer(0, "slot", "slotId");
            List<String> pages = List.copyOf(strings(f, "pages", "page", "text"));
            String title = f.optStr("", "title", "name");
            Optional<String> t = title.isBlank() ? Optional.empty() : Optional.of(title);
            return () -> c.send(new ServerboundEditBookPacket(slot, pages, t));
        });

        reg("container_close", "Инвентарь", "{\"containerId\":0}", (f, mc, c) -> {
            int id = f.integer(0, "containerId", "syncId", "id");
            return () -> c.send(new ServerboundContainerClosePacket(id));
        });
        reg("container_click", "Инвентарь", "{\"slot\":0,\"button\":0,\"clickType\":\"PICKUP\"}", (f, mc, c) -> {
            var menu = me(mc).containerMenu;
            int id = f.integer(menu.containerId, "containerId", "syncId", "id");
            int state = f.integer(menu.getStateId(), "stateId", "revision");
            int slot = f.reqInt("slot", "slotNum", "slotId");
            int btn = f.integer(0, "button", "buttonNum", "buttonId");
            ClickType type = f.en(ClickType.class, "PICKUP", "clickType", "actionType", "type", "mode");
            return () -> c.send(new ServerboundContainerClickPacket(id, state, slot, btn, type, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
        });
        reg("container_button_click", "Инвентарь", "{\"containerId\":0,\"buttonId\":0}", (f, mc, c) -> {
            int id = f.integer(0, "containerId", "syncId", "id");
            int btn = f.integer(0, "buttonId", "button");
            return () -> c.send(new ServerboundContainerButtonClickPacket(id, btn));
        });
        reg("container_slot_state_changed", "Инвентарь", "{\"slotId\":0,\"containerId\":0,\"newState\":true}", (f, mc, c) -> {
            int slot = f.integer(0, "slotId", "slot");
            int cid = f.integer(0, "containerId", "syncId");
            boolean st = f.bool(true, "newState", "state");
            return () -> c.send(new ServerboundContainerSlotStateChangedPacket(slot, cid, st));
        });
        reg("set_creative_mode_slot", "Инвентарь", "{\"slot\":0,\"item\":\"minecraft:stone\",\"count\":1}", (f, mc, c) -> {
            short slot = (short) f.reqInt("slot", "slotNum");
            ItemStack stack = stack(f);
            return () -> c.send(new ServerboundSetCreativeModeSlotPacket(slot, stack));
        });
        reg("recipe_book_change_settings", "Инвентарь", "{\"bookType\":\"CRAFTING\",\"open\":false,\"filtering\":false}", (f, mc, c) -> {
            RecipeBookType type = f.en(RecipeBookType.class, "CRAFTING", "bookType", "type");
            boolean open = f.bool(false, "open");
            boolean filtering = f.bool(false, "filtering", "filter");
            return () -> c.send(new ServerboundRecipeBookChangeSettingsPacket(type, open, filtering));
        });
        reg("recipe_book_seen_recipe", "Инвентарь", "{\"recipeId\":0}", (f, mc, c) -> {
            RecipeDisplayId id = new RecipeDisplayId(f.integer(0, "recipeId", "id"));
            return () -> c.send(new ServerboundRecipeBookSeenRecipePacket(id));
        });
        reg("place_recipe", "Инвентарь", "{\"containerId\":0,\"recipeId\":0,\"useMaxItems\":false}", (f, mc, c) -> {
            int cid = f.integer(0, "containerId", "syncId");
            RecipeDisplayId id = new RecipeDisplayId(f.integer(0, "recipeId", "id"));
            boolean max = f.bool(false, "useMaxItems", "maxItems");
            return () -> c.send(new ServerboundPlaceRecipePacket(cid, id, max));
        });
        reg("set_beacon", "Инвентарь", "{\"primary\":0,\"secondary\":0}", (f, mc, c) -> {
            Optional<Holder<MobEffect>> primary = effect(f, "primary", "primaryEffect");
            Optional<Holder<MobEffect>> secondary = effect(f, "secondary", "secondaryEffect");
            return () -> c.send(new ServerboundSetBeaconPacket(primary, secondary));
        });

        reg("keep_alive", "Служебные", "{\"id\":0}", (f, mc, c) -> {
            long id = f.lng(0, "id");
            return () -> c.send(new ServerboundKeepAlivePacket(id));
        });
        reg("pong", "Служебные", "{\"id\":0}", (f, mc, c) -> {
            int id = f.integer(0, "id", "parameter");
            return () -> c.send(new ServerboundPongPacket(id));
        });
        reg("client_command", "Служебные", "{\"action\":\"PERFORM_RESPAWN\"}", (f, mc, c) -> {
            var a = f.en(ServerboundClientCommandPacket.Action.class, "PERFORM_RESPAWN", "action");
            return () -> c.send(new ServerboundClientCommandPacket(a));
        });

        reg("change_difficulty", "Мир", "{\"difficulty\":\"NORMAL\"}", (f, mc, c) -> {
            Difficulty d = f.en(Difficulty.class, "NORMAL", "difficulty");
            return () -> c.send(new ServerboundChangeDifficultyPacket(d));
        });
        reg("lock_difficulty", "Мир", "{\"locked\":true}", (f, mc, c) -> {
            boolean locked = f.bool(true, "locked");
            return () -> c.send(new ServerboundLockDifficultyPacket(locked));
        });
        reg("entity_tag_query", "Мир", "{\"transactionId\":0,\"entityId\":0}", (f, mc, c) -> {
            int tr = f.integer(0, "transactionId", "id");
            int ent = f.reqInt("entityId", "entity");
            return () -> c.send(new ServerboundEntityTagQueryPacket(tr, ent));
        });
        reg("block_entity_tag_query", "Мир", "{\"transactionId\":0,\"pos\":[0,64,0]}", (f, mc, c) -> {
            int tr = f.integer(0, "transactionId", "id");
            BlockPos pos = blockPos(f);
            return () -> c.send(new ServerboundBlockEntityTagQueryPacket(tr, pos));
        });
        reg("jigsaw_generate", "Мир", "{\"pos\":[0,64,0],\"levels\":1,\"keepJigsaws\":false}", (f, mc, c) -> {
            BlockPos pos = blockPos(f);
            int levels = f.integer(1, "levels", "level");
            boolean keep = f.bool(false, "keepJigsaws", "keep");
            return () -> c.send(new ServerboundJigsawGeneratePacket(pos, levels, keep));
        });
        reg("sign_update", "Мир", "{\"pos\":[0,64,0],\"front\":true,\"lines\":[\"\",\"\",\"\",\"\"]}", (f, mc, c) -> {
            BlockPos pos = blockPos(f);
            boolean front = f.bool(true, "front", "isFrontText", "frontText");
            String[] lines = signLines(f);
            return () -> c.send(new ServerboundSignUpdatePacket(pos, front, lines[0], lines[1], lines[2], lines[3]));
        });
        reg("seen_advancements", "Мир", "{\"action\":\"CLOSED_SCREEN\",\"id\":\"\"}", (f, mc, c) -> {
            var action = f.en(ServerboundSeenAdvancementsPacket.Action.class, "CLOSED_SCREEN", "action");
            String raw = f.optStr("", "id", "tab", "advancement");
            ResourceLocation id = raw.isBlank() ? null : ResourceLocation.tryParse(raw);
            if (!raw.isBlank() && id == null) throw new IllegalArgumentException("«id»: некорректный идентификатор «" + raw + "»");
            return () -> c.send(new ServerboundSeenAdvancementsPacket(action, id));
        });
        reg("set_command_block", "Мир",
                "{\"pos\":[0,64,0],\"command\":\"say hi\",\"mode\":\"REDSTONE\",\"trackOutput\":true,\"conditional\":false,\"automatic\":false}",
                (f, mc, c) -> {
                    BlockPos pos = blockPos(f);
                    String cmd = f.str("command", "cmd");
                    var mode = f.en(CommandBlockEntity.Mode.class, "REDSTONE", "mode");
                    boolean track = f.bool(true, "trackOutput");
                    boolean cond = f.bool(false, "conditional");
                    boolean auto = f.bool(false, "automatic", "auto");
                    return () -> c.send(new ServerboundSetCommandBlockPacket(pos, cmd, mode, track, cond, auto));
                });
        reg("set_command_minecart", "Мир", "{\"entityId\":0,\"command\":\"say hi\",\"trackOutput\":true}", (f, mc, c) -> {
            int id = f.reqInt("entityId", "id", "entity");
            String cmd = f.str("command", "cmd");
            boolean track = f.bool(true, "trackOutput");
            return () -> c.send(new ServerboundSetCommandMinecartPacket(id, cmd, track));
        });

        reg("chunk_batch_received", "Сеть", "{\"desiredChunksPerTick\":64}", (f, mc, c) -> {
            float v = (float) f.dbl(64, "desiredChunksPerTick", "chunksPerTick");
            return () -> c.send(new ServerboundChunkBatchReceivedPacket(v));
        });
        reg("client_information", "Сеть",
                "{\"locale\":\"ru_ru\",\"viewDistance\":12,\"chatVisibility\":\"FULL\",\"chatColors\":true,\"modelCustomisation\":0,\"mainHand\":\"RIGHT\",\"textFiltering\":false,\"allowsListing\":true,\"particleStatus\":\"ALL\"}",
                (f, mc, c) -> {
                    String locale = f.optStr("ru_ru", "locale", "language");
                    if (locale.length() > ClientInformation.MAX_LANGUAGE_LENGTH) locale = locale.substring(0, ClientInformation.MAX_LANGUAGE_LENGTH);
                    int dist = Math.max(2, Math.min(32, f.integer(12, "viewDistance", "distance")));
                    ChatVisiblity vis = f.en(ChatVisiblity.class, "FULL", "chatVisibility", "visibility");
                    boolean colors = f.bool(true, "chatColors", "colors");
                    int model = Math.max(0, Math.min(127, f.integer(0, "modelCustomisation", "modelParts", "model")));
                    HumanoidArm arm = f.en(HumanoidArm.class, "RIGHT", "mainHand", "hand");
                    boolean filtering = f.bool(false, "textFiltering", "textFilteringEnabled");
                    boolean listing = f.bool(true, "allowsListing", "listing");
                    ParticleStatus particles = f.en(ParticleStatus.class, "ALL", "particleStatus", "particles");
                    ClientInformation info = new ClientInformation(locale, dist, vis, colors, model, arm, filtering, listing, particles);
                    return () -> c.send(new ServerboundClientInformationPacket(info));
                });
        reg("resource_pack", "Сеть",
                "{\"uuid\":\"00000000-0000-0000-0000-000000000000\",\"action\":\"SUCCESSFULLY_LOADED\"}",
                (f, mc, c) -> {
                    UUID id = uuid(f, "uuid", "id");
                    var action = f.en(ServerboundResourcePackPacket.Action.class, "SUCCESSFULLY_LOADED", "action");
                    return () -> c.send(new ServerboundResourcePackPacket(id, action));
                });
        reg("configuration_acknowledged", "Сеть", "{}",
                (f, mc, c) -> () -> c.send(ServerboundConfigurationAcknowledgedPacket.INSTANCE));
        reg("debug_sample_subscription", "Сеть", "{\"sampleType\":\"TICK_TIME\"}", (f, mc, c) -> {
            RemoteDebugSampleType type = f.en(RemoteDebugSampleType.class, "TICK_TIME", "sampleType", "type");
            return () -> c.send(new ServerboundDebugSampleSubscriptionPacket(type));
        });
    }

    private PacketFactory() {}
}
