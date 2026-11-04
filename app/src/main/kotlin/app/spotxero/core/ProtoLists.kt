package app.spotxero.core

object ProtoLists {

    fun filter(items: List<*>?, keep: (Any) -> Boolean): List<*>? {
        if (items.isNullOrEmpty()) return items
        val kept = items.filterNotNull().filter(keep)
        if (kept.size == items.size || kept.isEmpty()) return items
        return copyOfType(items, kept) ?: items
    }

    private fun copyOfType(original: List<*>, kept: List<*>): List<*>? = runCatching {
        val grow = original.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.returnType.isInstance(original)
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        val copy = grow.invoke(original, kept.size) as MutableList<Any?>
        copy.addAll(kept)
        copy
    }.getOrNull()
}
