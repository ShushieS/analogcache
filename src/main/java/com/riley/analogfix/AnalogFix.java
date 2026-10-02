package com.riley.analogfix;

import com.riley.analogfix.cache.AudioCache;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

@Mod(value = AnalogFix.MOD_ID, dist = Dist.CLIENT)
public class AnalogFix {
    public static final String MOD_ID = "analogfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnalogFix(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AnalogFixConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        AudioCache.init();
    }
}
