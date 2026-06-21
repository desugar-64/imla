package dev.serhiiyaremych.imla.internal.capture

/**
 * Marks a declaration that runs on the single Imla capture thread — the
 * [CaptureThread] backing [CaptureThread.handler] and [CaptureThread.executor] — as
 * opposed to the main thread or the GL thread.
 *
 * Work posted through the capture handler, `ImageReader`/`CanvasBufferedRenderer`
 * completion callbacks, and lease-release bookkeeping all execute here. Pair with
 * androidx [androidx.annotation.MainThread] on main-confined entry points and
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
internal annotation class OnCaptureThread
