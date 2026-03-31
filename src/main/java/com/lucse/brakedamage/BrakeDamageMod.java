package com.lucse.brakedamage;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(BrakeDamageMod.MOD_ID)
public class BrakeDamageMod {
    public static final String MOD_ID = "brakedamage";
    private static final Logger LOGGER = LogUtils.getLogger();

    public BrakeDamageMod() {
        LOGGER.info("Loading Brake Damage mod");
        MinecraftForge.EVENT_BUS.register(new SuddenStopHandler());
    }
}

