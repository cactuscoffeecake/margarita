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
    /** Outline colour (RGB) for the ghost mob. */
    public static volatile int ghostOutlineRgb = 0x40C0FF;
}
