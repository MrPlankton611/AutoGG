package com.autogg.autogg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public class AutoGGMod implements ModInitializer {
    public static final String MOD_ID = "autogg";
    private static boolean hasLogged = false;
    private static boolean firstTickLogged = false;

    @Override
    public void onInitialize() {
        // Diagnostics on stdout only. Lunar Client filters SLF4J/Log4j; System.out is unfiltered.
        System.out.println("[AutoGG] onInitialize entered");
        try {
            new java.io.File("./config/autogg-loaded-" + System.currentTimeMillis() + ".marker").createNewFile();
        } catch (Exception ignored) {}

        ClientTickEvents.START_WORLD_TICK.register(client -> {
            if (hasLogged) return;

            if (!firstTickLogged) {
                System.out.println("[AutoGG] first tick fired");
                firstTickLogged = true;
            }
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc == null) {
                    System.out.println("[AutoGG] mc == null");
                    return;
                }
                boolean singleplayer = mc.isInSingleplayer();
                System.out.println("[AutoGG] isInSingleplayer=" + singleplayer);
                if (singleplayer) {
                    return;
                }

                Object entry = mc.getCurrentServerEntry();
                System.out.println("[AutoGG] getCurrentServerEntry=" + (entry == null ? "null" : entry.getClass().getName() + " " + entry));
                if (entry == null) {
                    return;
                }

                // Try common Yarn field names for the server address. The field is named
                // 'address' on older Yarn mappings and 'ip' on newer Yarn (1.21.x).
                String address = null;
                try {
                    java.lang.reflect.Field f = entry.getClass().getField("address");
                    address = (String) f.get(entry);
                    System.out.println("[AutoGG] address (via .address)=" + address);
                } catch (NoSuchFieldException ignored1) {
                    try {
                        java.lang.reflect.Field f = entry.getClass().getField("ip");
                        address = (String) f.get(entry);
                        System.out.println("[AutoGG] address (via .ip)=" + address);
                    } catch (NoSuchFieldException ignored2) {
                        System.out.println("[AutoGG] neither 'address' nor 'ip' field found; declaring fields:");
                        for (java.lang.reflect.Field df : entry.getClass().getDeclaredFields()) {
                            System.out.println("[AutoGG]   " + df.getName() + " : " + df.getType().getName());
                        }
                        return;
                    }
                }

                if ("play.chi-us.pvphq.com".equals(address)) {
                    System.out.println("[AutoGG] matched server -> 6767 mango mango");
                    hasLogged = true;
                }
        } catch (Throwable t) {
            System.out.println("[AutoGG] tick handler exception: " + t);
            t.printStackTrace(System.out);
        }
        });
    }
}
