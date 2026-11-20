package app.spotxero.core

import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

object ProtoLists {

    fun filter(items: List<*>?, keep: (Any) -> Boolean): List<*>? {
        if (items.isNullOrEmpty()) return items
        val kept = items.filterNotNull().filter(keep)
        if (kept.size == items.size) return items
        val copy = copyOfType(items, kept) ?: createProxyList(items, kept)
        if (copy != null) {
            return copy
        }
        return if (items is ArrayList<*>) ArrayList(kept) else items
    }

    private fun copyOfType(original: List<*>, kept: List<*>): List<*>? = runCatching {
        val grow = original.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(method.returnType) &&
                (method.returnType.isInstance(original) || method.returnType.isAssignableFrom(original.javaClass))
        } ?: original.javaClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(method.returnType)
        }?.apply { isAccessible = true } ?: return null

        val capacity = maxOf(original.size, kept.size, 1)
        @Suppress("UNCHECKED_CAST")
        val copy = grow.invoke(original, capacity) as? MutableList<Any?> ?: return null
        copy.clear()
        copy.addAll(kept)
        copy
    }.getOrNull()

    private fun createProxyList(original: List<*>, kept: List<*>): List<*>? = runCatching {
        if (original is ArrayList<*>) {
            return ArrayList(kept)
        }
        val target = ArrayList(kept)
        val interfaces = generateSequence<Class<*>>(original.javaClass) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .distinct()
            .filter { Modifier.isPublic(it.modifiers) }
            .toList()
        if (interfaces.isEmpty()) return null
        Proxy.newProxyInstance(
            original.javaClass.classLoader ?: kept.javaClass.classLoader,
            interfaces.toTypedArray()
        ) { _, method, args ->
            when (method.name) {
                "equals" -> target == args?.getOrNull(0)
                "hashCode" -> target.hashCode()
                "toString" -> target.toString()
                else -> {
                    if (method.parameterCount == 1 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        List::class.java.isAssignableFrom(method.returnType)
                    ) {
                        createProxyList(original, target) ?: target
                    } else {
                        val methodArgs = args ?: emptyArray()
                        method.invoke(target, *methodArgs)
                    }
                }
            }
        } as List<*>
    }.getOrNull()
}
