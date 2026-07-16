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
        LOGGER.info("AutoGG initialized!");

        ClientTickEvents.START_WORLD_TICK.register(client -> {
            if (hasLogged) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && !mc.isInSingleplayer() && mc.getCurrentServerEntry() != null) {
                String address = mc.getCurrentServerEntry().address;
                if ("play.chi-us.pvphq.com".equals(address)) {
                    LOGGER.info("6767 mango mango");
                    hasLogged = true;
                }
            }
        });
    }
}
