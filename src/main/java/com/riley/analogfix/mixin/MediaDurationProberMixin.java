package com.riley.analogfix.mixin;

import com.riley.analogfix.cache.AudioCache;
import com.palm1.analoglib.client.audio.nativeaudio.pipeline.MediaDurationProber;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = MediaDurationProber.class, remap = false)
public abstract class MediaDurationProberMixin {
    @ModifyVariable(method = "probeDurationSync", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String analogfix$probeCachedFile(String url) {
        if (!AudioCache.shouldCache(url)) {
            return url;
        }
        AudioCache.Result result = AudioCache.awaitFile(url);
        return result.file() != null ? AudioCache.toFileUrl(result.file()) : url;
    }
}
