package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla only draws the first-person hands/held items when the camera is in
 * first person. When the "hands in third person" setting is on, this makes
 * that check pass in every camera mode.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @WrapOperation(
        method = "renderItemInHand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"
        )
    )
    private boolean thirdpersonhands$showHands(CameraType instance, Operation<Boolean> original) {
        if (ModState.handsInThirdPerson) return true;
        return original.call(instance);
    }
}
