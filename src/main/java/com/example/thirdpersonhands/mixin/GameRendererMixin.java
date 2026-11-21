package com.example.thirdpersonhands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla only draws the first-person hands/held items when the camera is in
 * first person. This makes that check pass in third person too, so the hands
 * are rendered on top of the view in every camera mode.
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
    private boolean thirdpersonhands$alwaysShowHands(CameraType instance, Operation<Boolean> original) {
        return true;
    }
}
