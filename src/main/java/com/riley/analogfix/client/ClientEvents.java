package com.riley.analogfix.client;

import com.riley.analogfix.AnalogFix;
import com.riley.analogfix.AnalogFixConfig;
import com.riley.analogfix.cache.AudioCache;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = AnalogFix.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private static final int BUTTON_WIDTH = 120;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen screen)) {
            return;
        }
        AudioCache.Stats stats = AudioCache.stats();
        Button button = Button.builder(
                        Component.translatable("gui.analogfix.clear_cache", AudioCache.formatSize(stats.bytes())),
                        b -> openConfirm(screen))
                .bounds(screen.width - BUTTON_WIDTH - 5, 6, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal(stats.files() + " cached tracks in config/analogfix/cache")))
                .build();
        button.active = stats.files() > 0;
        event.addListener(button);
    }

    private static void openConfirm(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        AudioCache.Stats stats = AudioCache.stats();
        mc.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                AudioCache.clear().thenAccept(result -> mc.execute(() -> {
                    SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                            Component.translatable("gui.analogfix.clear_cache.done.title"),
                            doneMessage(result));
                    if (mc.screen == parent) {
                        mc.setScreen(parent);
                    }
                }));
            }
            mc.setScreen(parent);
        }, Component.translatable("gui.analogfix.clear_cache.confirm.title"),
                Component.translatable("gui.analogfix.clear_cache.confirm.message",
                        stats.files(), AudioCache.formatSize(stats.bytes()))));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("analogfix")
                .then(Commands.literal("cache")
                        .executes(ClientEvents::cacheInfo)
                        .then(Commands.literal("clear").executes(ClientEvents::cacheClear))));
    }

    private static int cacheInfo(CommandContext<CommandSourceStack> ctx) {
        AudioCache.Stats stats = AudioCache.stats();
        ctx.getSource().sendSuccess(() -> Component.translatable("command.analogfix.cache.info",
                stats.files(), AudioCache.formatSize(stats.bytes()),
                AudioCache.formatSize(AnalogFixConfig.maxCacheBytes()), AudioCache.directory().toString()), false);
        return stats.files();
    }

    private static int cacheClear(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        AudioCache.clear().thenAccept(result -> mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(doneMessage(result), false);
            }
        }));
        return 1;
    }

    private static Component doneMessage(AudioCache.ClearResult result) {
        Component message = Component.translatable("gui.analogfix.clear_cache.done.message",
                result.deleted(), AudioCache.formatSize(result.freedBytes()));
        return result.locked() == 0 ? message
                : Component.translatable("gui.analogfix.clear_cache.done.locked", message, result.locked());
    }
}
