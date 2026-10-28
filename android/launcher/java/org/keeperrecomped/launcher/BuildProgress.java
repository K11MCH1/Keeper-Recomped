package org.keeperrecomped.launcher;

/**
 * Progress sink handed to the engine build hook
 * {@code org.keeperrecomped.game.EngineBuild.build(Context, File, File, BuildProgress)}.
 */
public interface BuildProgress {
    /** @param percent 0-100 within the build step; @param message one short line for the UI */
    void progress(int percent, String message);

    /** True once the player cancelled; long builds should check it and throw. */
    boolean cancelled();
}
