package dev.klxsh.speakersinheads.mixin;

import dev.klxsh.speakersinheads.audio.SprAcoustics;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "com.sonicether.soundphysics.SoundPhysics", remap = false)
abstract class SprEnvironmentMixin {
    @ModifyArgs(method = "processSound(IDDDLnet/minecraft/sounds/SoundSource;Lnet/minecraft/resources/Identifier;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lcom/sonicether/soundphysics/SoundPhysics;evaluateEnvironment(IDDDLnet/minecraft/sounds/SoundSource;Lnet/minecraft/resources/Identifier;F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static void sih$preserveReverbForSpeakers(Args args) {
        SoundSource category = args.get(4);
        SoundSource mapped = SprAcoustics.environmentCategory(category, SprAcoustics.ownsDirectSignal(args.get(0)));
        if (category != mapped) args.set(4, mapped);
    }

    @ModifyArgs(method = "evaluateEnvironment(IDDDLnet/minecraft/sounds/SoundSource;Lnet/minecraft/resources/Identifier;F)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lcom/sonicether/soundphysics/SoundPhysics;setEnvironment(IFFFFFFFFFF)V"),
            require = 0)
    private static void sih$keepLocalDirectFilters(Args args) {
        if (!SprAcoustics.ownsDirectSignal(args.<Integer>get(0))) return;
        args.set(9, 1f); // Direct gainHF
        args.set(10, 1f); // Direct gain
    }

    @ModifyArgs(method = "setEnvironment(IFFFFFFFFFF)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL11;alSourcef(IIF)V"), require = 0)
    private static void sih$keepLocalDistance(Args args) {
        if (args.<Integer>get(1) == 0x20007 && SprAcoustics.ownsDirectSignal(args.<Integer>get(0))) {
            args.set(2, 0f); // AL_AIR_ABSORPTION_FACTOR
        }
    }
}
