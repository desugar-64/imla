package dev.serhiiyaremych.imla.internal.render.gl

/**
 * Marks a declaration that runs on the single Imla GL thread — the thread backing
 * [androidx.graphics.opengl.GLRenderer], guarded by [SceneGlThreadGuard] — as opposed
 * to the main thread or the capture thread.
 *
 * EGL context work, framebuffer/texture lifecycle, HardwareBuffer import, draw/blit
 * passes, and present/swap all execute here. Pair with androidx
 * [androidx.annotation.MainThread] on main-confined entry points,
 * [dev.serhiiyaremych.imla.internal.capture.OnCaptureThread] on capture-thread work, and
 * [androidx.annotation.AnyThread] on lock-guarded functions reachable from several
 * threads.
 *
 * Documentary only: there is no lint enforcement for this thread.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.CLASS
)
internal annotation class OnGlThread
