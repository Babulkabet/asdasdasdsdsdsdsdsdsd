package dev.packetsender;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class PacketSenderMod implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("packetsender");
    static Config cfg = new Config();
    private static String url = null;

    @Override
    public void onInitializeClient() {
        cfg = Config.load();

        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);

        for (int p = cfg.port; p < cfg.port + 10; p++) {
            try {
                WebServer web = new WebServer(p, token);
                web.start();
                url = "http://127.0.0.1:" + p + "/#t=" + token;
                LOG.info("Packet Sender: панель запущена, откройте {} (или команда /psend в игре)", url);
                break;
            } catch (IOException e) {
                LOG.warn("Порт {} недоступен: {}", p, e.getMessage());
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register(Jobs::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("psend")
                        .executes(ctx -> { openPanel(ctx.getSource()); return 1; })
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            Jobs.cancelAll();
                            ctx.getSource().sendFeedback(Component.literal("Packet Sender: все отправки остановлены"));
                            return 1;
                        }))));
    }

    private static void openPanel(FabricClientCommandSource src) {
        if (url == null) {
            src.sendFeedback(Component.literal("Packet Sender: не удалось запустить веб-панель, см. latest.log").withStyle(ChatFormatting.RED));
            return;
        }
        src.sendFeedback(Component.literal("Открыть панель Packet Sender").setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA)));
        try { Util.getPlatform().openUri(url); } catch (Throwable ignore) { }
    }
}
