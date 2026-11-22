package com.example.thirdpersonhands.mixin;

import com.example.thirdpersonhands.ModState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ender dragon renderer does not use the scale attribute, so the dragon stand-in could never be
 * made smaller. This scales the one place the model is set up. The target is given by name so the
 * mod still loads if this class is renamed in a future version (require = 0).
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.EnderDragonRenderer")
public class EnderDragonRendererMixin {

    @WrapOperation(
        method = {"submit", "render"},
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"),
        require = 0
    )
    private void thirdpersonhands$shrinkDragon(PoseStack stack, float x, float y, float z, Operation<Void> original) {
        float s = ModState.dragonScale;
        original.call(stack, x * s, y * s, z * s);
    }
}
