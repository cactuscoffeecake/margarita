package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

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

    /**
     * Swimming mobs (fish, squid...) lie flat when the game thinks they are out of water.
     * The stand-in is never in water, so say it is.
     */
    @Inject(method = "isInWater", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$fakeWater(CallbackInfoReturnable<Boolean> cir) {
        if (((Object) this == ModState.dummy && ModState.dummyAquatic)
                || ((Object) this == ModState.ghostMob && ModState.ghostAquatic)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * On the client, setGlowingTag() does nothing (the game re-reads the same flag it just wrote),
     * which is why the ghost mob never glowed. Answer the question directly instead.
     */
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$ghostGlow(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.ghostMob) cir.setReturnValue(ModState.ghostGlow);
    }

    /**
     * Makes the ghost mob render faded (like the player ghost) instead of outline-only,
     * which for many mobs ended up showing nothing at all.
     */
    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$ghostFaded(Player player, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == ModState.ghostMob) cir.setReturnValue(false);
    }
}
