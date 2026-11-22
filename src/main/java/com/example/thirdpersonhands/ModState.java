package com.example.thirdpersonhands;

/**
 * Tiny shared state read by the mixins. Kept in its own class, holding only
 * primitives and Object, so mixins never force the main mod class to load early.
 */
public final class ModState {
    private ModState() {}

    /** Draw your first-person hands and held items while in third person. */
    public static volatile boolean handsInThirdPerson = true;
    /** True while your real player model should be hidden (mob model active). */
    public static volatile boolean hidePlayer = false;
    /** The client-only stand-in mob entity, or null. */
    public static volatile Object dummy = null;
    /** The outline-only ghost mob entity (ghost shape = mob), or null. */
    public static volatile Object ghostMob = null;
    /** True while the stand-in must be skipped by the renderer (first person). */
    public static volatile boolean hideDummy = false;
    /** The stand-in / ghost mob is a swimmer: report "in water" so fish don't lie flat. */
    public static volatile boolean dummyAquatic = false;
    public static volatile boolean ghostAquatic = false;
    /** Extra scale for the ender dragon renderer (it ignores the scale attribute). */
    public static volatile float dragonScale = 1.0f;
    /** Whether the ghost mob should currently glow (the client ignores setGlowingTag, so a mixin reads this). */
    public static volatile boolean ghostGlow = false;
    /** True while the ghost mob must not be drawn at all (first person, if the player turned that off). */
    public static volatile boolean hideGhostMob = false;
    /** Outline colour (RGB) for the ghost mob. */
    public static volatile int ghostOutlineRgb = 0x40C0FF;
}
