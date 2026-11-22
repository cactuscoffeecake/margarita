package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;

/**
 * Keeps the stand-in mob from being targeted by your crosshair or pushing you.
 * Without this, right-click abilities could be eaten by the stand-in.
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$notPickable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.dummy || (Object) this == ModState.ghostMob) cir.setReturnValue(false);
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$notPushable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.dummy || (Object) this == ModState.ghostMob) cir.setReturnValue(false);
    }

    /** Gives the ghost mob its outline colour (glowing entities use their team colour). */
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$ghostOutlineColor(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == ModState.ghostMob) cir.setReturnValue(ModState.ghostOutlineRgb);
    }
}
