package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

/**
 * Stops the game from drawing your real player model while the mob model is
 * active. If the target method is not found in this game version the mod
 * still loads (require = 0), but you would see both models.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void thirdpersonhands$hideRealPlayer(Entity entity, Frustum frustum, double x, double y, double z,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (ModState.hidePlayer && entity == Minecraft.getInstance().player) {
            cir.setReturnValue(false);
        }
    }
}
