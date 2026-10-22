package app.opentv.launcher.apps

/** A reversible in-memory edit. Disk order is changed only by explicit confirmation. */
internal class ReorderSession(val key: String, initial: List<String>) {
    private var order = initial.toList()
    val keys: List<String> get() = order

    fun moveTo(index: Int) {
        val from = order.indexOf(key)
        if (from < 0 || index !in order.indices || index == from) return
        order = order.toMutableList().apply { add(index, removeAt(from)) }
    }

    /** Keep arrivals/removals stable while retaining the preview's relative order. */
    fun reconcile(current: List<String>): List<String> {
        val available = current.toSet()
        order = order.filter { it in available } + current.filter { it !in order }
        return order
    }
}

/** Replace visible slots without discarding hidden or temporarily missing applications. */
internal fun mergeVisibleOrder(stored: List<String>, visible: List<String>): List<String> {
    val wanted = visible.distinct()
    val visibleSet = wanted.toSet()
    val next = wanted.iterator()
    val merged = stored.map { if (it in visibleSet && next.hasNext()) next.next() else it }.toMutableList()
    while (next.hasNext()) merged += next.next()
    return merged.distinct()
}
