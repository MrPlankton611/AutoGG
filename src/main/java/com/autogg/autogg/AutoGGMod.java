package com.autogg.autogg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoGGMod implements ModInitializer {
    public static final String MOD_ID = "autogg";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static boolean hasLogged = false;

    @Override
    public void onInitialize() {
        // Triple-redundant diagnostic so we can tell whether onInitialize actually runs
        // even when a launcher (e.g. Lunar Client) filters or reroutes SLF4J/Log4j output.
        System.out.println("[AutoGG] onInitialize entered (stdout)");
        LOGGER.error("[AutoGG] initialized! (ERROR level)");
        try {
            new java.io.File("./config/autogg-loaded-" + System.currentTimeMillis() + ".marker").createNewFile();
        } catch (Exception ignored) {}

        ClientTickEvents.START_WORLD_TICK.register(client -> {
            if (hasLogged) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && !mc.isInSingleplayer() && mc.getCurrentServerEntry() != null) {
                String address = mc.getCurrentServerEntry().address;
                if ("play.chi-us.pvphq.com".equals(address)) {
                    System.out.println("[AutoGG] 6767 mango mango (stdout)");
                    LOGGER.error("[AutoGG] 6767 mango mango (ERROR level)");
                    hasLogged = true;
                }
            }
        });
    }
}
