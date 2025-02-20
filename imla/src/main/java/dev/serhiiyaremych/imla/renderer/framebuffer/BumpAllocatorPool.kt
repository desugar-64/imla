package dev.serhiiyaremych.imla.renderer.framebuffer

internal class BumpAllocatorPool<T> {
    val items = ArrayList<T>()
    var length: Int = 0

    inline fun acquire(factory: () -> T): T {
        if (length >= items.size) {
            items.add(factory())
        }
        return items[length++]
    }

    fun resetPool() {
        length = 0
    }

    fun onEach(onEach: (T) -> Unit) {
        items.forEach(onEach)
    }
}