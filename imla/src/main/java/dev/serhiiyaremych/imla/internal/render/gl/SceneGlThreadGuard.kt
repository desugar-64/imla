package dev.serhiiyaremych.imla.internal.render.gl

internal class SceneGlThreadGuard(
    private val ownerName: String
) {
    @Volatile
    private var boundThread: Thread? = null

    fun bindCurrentThread() {
        val currentThread = Thread.currentThread()
        val previousThread = boundThread
        check(previousThread == null || previousThread == currentThread) {
            "$ownerName GL resources moved from ${previousThread?.name} to ${currentThread.name}"
        }
        boundThread = currentThread
    }

    fun checkCurrentThread() {
        val expectedThread = requireNotNull(boundThread) {
            "$ownerName GL thread is not bound"
        }
        check(Thread.currentThread() == expectedThread) {
            "$ownerName GL resources accessed from ${Thread.currentThread().name}; expected ${expectedThread.name}"
        }
    }

    fun isCurrentThread(): Boolean {
        return boundThread == Thread.currentThread()
    }
}
