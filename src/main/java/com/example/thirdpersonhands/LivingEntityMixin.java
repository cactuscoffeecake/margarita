package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.LivingEntity;

/** Same as EntityMixin, for the versions of these methods that LivingEntity overrides. */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$notPickable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.dummy || (Object) this == ModState.ghostMob) cir.setReturnValue(false);
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$ghostGlow(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.ghostMob) cir.setReturnValue(ModState.ghostGlow);
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$notPushable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.dummy || (Object) this == ModState.ghostMob) cir.setReturnValue(false);
    }
}
