package com.riley.analogfix;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AnalogFixConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Download each track in full before it plays, and play it from disk. When off, Analog Audio streams as normal.")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue MAX_CACHE_SIZE_MB = BUILDER
            .comment("Least recently played tracks are deleted when the cache grows past this size.")
            .defineInRange("maxCacheSizeMB", 2048, 64, 1024 * 1024);

    public static final ModConfigSpec.EnumValue<AudioFormat> AUDIO_FORMAT = BUILDER
            .comment("MP3: keep only the audio, converted to mp3 with ffmpeg (video is dropped).",
                    "ORIGINAL: keep the downloaded file as-is (opus/m4a, or mp4 video when YouTube offers no audio-only format).")
            .defineEnum("audioFormat", AudioFormat.MP3);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public enum AudioFormat {
        MP3,
        ORIGINAL
    }

    private AnalogFixConfig() {
    }

    public static AudioFormat audioFormat() {
        try {
            return AUDIO_FORMAT.get();
        } catch (IllegalStateException e) {
            return AUDIO_FORMAT.getDefault();
        }
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (IllegalStateException e) {
            return ENABLED.getDefault();
        }
    }

    public static long maxCacheBytes() {
        try {
            return MAX_CACHE_SIZE_MB.get() * 1024L * 1024L;
        } catch (IllegalStateException e) {
            return MAX_CACHE_SIZE_MB.getDefault() * 1024L * 1024L;
        }
    }
}
