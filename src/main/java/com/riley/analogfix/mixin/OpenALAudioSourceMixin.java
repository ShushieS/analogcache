package com.riley.analogfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.palm1.analoglib.client.audio.openal.OpenALAudioSource;
import com.palm1.analoglib.client.audio.api.StereoMode;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OpenALAudioSource.class, remap = false)
public abstract class OpenALAudioSourceMixin {
    private static final long RETRY_DELAY_MS = 2000;

    @Shadow public int sourceId;

    @Unique private long analogfix$nextAttempt;

    // Drain stale errors so an unrelated earlier AL error is not read as a failed alGenSources, and
    // stop an emitter that just failed from retrying (and logging) every pump.
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void analogfix$throttleInit(StereoMode mode, float width, CallbackInfo ci) {
        if (this.sourceId != -1) {
            return;
        }
        if (System.currentTimeMillis() < this.analogfix$nextAttempt) {
            ci.cancel();
            return;
        }
        AL10.alGetError();
    }

    // init treats any non-zero error as a failure and drops sourceId without deleting the source
    // alGenSources just returned, so each failed attempt leaks one.
    @WrapOperation(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL10;alGetError()I"))
    private int analogfix$releaseOnError(Operation<Integer> original) {
        int error = original.call();
        if (error != 0 && this.sourceId != -1 && this.sourceId != 0) {
            AL10.alDeleteSources(this.sourceId);
            AL10.alGetError();
        }
        return error;
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void analogfix$markFailure(StereoMode mode, float width, CallbackInfo ci) {
        if (this.sourceId == -1) {
            this.analogfix$nextAttempt = System.currentTimeMillis() + RETRY_DELAY_MS;
        }
    }
}
