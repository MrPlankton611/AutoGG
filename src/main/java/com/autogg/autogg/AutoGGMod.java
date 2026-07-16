package com.autogg.autogg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

public class AutoGGMod implements ModInitializer {
    public static final String MOD_ID = "autogg";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("AutoGG initialized!");
    }
}
