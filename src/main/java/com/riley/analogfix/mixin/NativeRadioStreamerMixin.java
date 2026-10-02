package com.riley.analogfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.riley.analogfix.AnalogFix;
import com.riley.analogfix.cache.AudioCache;
import com.palm1.analoglib.client.audio.nativeaudio.NativeRadioStreamer;
import com.palm1.analoglib.client.audio.nativeaudio.pipeline.AudioProcessPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(value = NativeRadioStreamer.class, remap = false)
public abstract class NativeRadioStreamerMixin {
    @Shadow private volatile boolean playing;
    @Shadow private String currentTrackUrl;
    @Shadow private long currentOffsetMs;
    @Shadow private long currentTrueDuration;
    @Shadow private boolean looping;
    @Shadow private Runnable trackEndCallback;

    @WrapOperation(method = "lambda$playTrack$2", at = @At(value = "INVOKE",
            target = "Lcom/palm1/analoglib/client/audio/nativeaudio/pipeline/AudioProcessPipeline;start(Ljava/lang/String;JLjava/util/function/Consumer;)Lcom/palm1/analoglib/client/audio/nativeaudio/pipeline/AudioProcessPipeline$ActivePipeline;"))
    private AudioProcessPipeline.ActivePipeline analogfix$playFromCache(AudioProcessPipeline pipeline, String url,
            long offsetMs, Consumer<String> errorHandler, Operation<AudioProcessPipeline.ActivePipeline> original) {
        if (!AudioCache.shouldCache(url)) {
            return original.call(pipeline, url, offsetMs, errorHandler);
        }

        long waitStart = System.currentTimeMillis();
        AudioCache.Result result = AudioCache.awaitFile(url);

        if (!this.playing || !url.equals(this.currentTrackUrl) || this.currentOffsetMs != offsetMs) {
            return null;
        }
        if (result.isLivestream()) {
            return original.call(pipeline, url, offsetMs, errorHandler);
        }
        Path file = result.file();
        if (file == null) {
            errorHandler.accept("AnalogFix: could not download track " + url);
            return null;
        }

        long offset = offsetMs + (System.currentTimeMillis() - waitStart);
        long fileDuration = AudioCache.durationMs(file);
        long duration = fileDuration > 0 ? fileDuration : this.currentTrueDuration;
        if (fileDuration > 0 && this.currentTrueDuration > 0 && Math.abs(fileDuration - this.currentTrueDuration) > 2000) {
            AnalogFix.LOGGER.warn("[AnalogFix] Tape says {} is {} ms but the audio is {} ms; using the audio length",
                    url, this.currentTrueDuration, fileDuration);
        }
        if (duration > 0 && offset >= duration) {
            if (this.looping) {
                offset %= duration;
            } else {
                Runnable onEnd = this.trackEndCallback;
                if (onEnd != null) {
                    onEnd.run();
                }
                return null;
            }
        }
        this.currentOffsetMs = offset;
        return original.call(pipeline, AudioCache.toFileUrl(file), offset, errorHandler);
    }
}
