package app.spotxero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.AbstractList
import java.util.RandomAccess

class ProtoListsTest {

    interface FakeProtobufList<E> : MutableList<E>, RandomAccess {
        fun mutableCopyWithCapacity(capacity: Int): FakeProtobufList<E>
    }

    class FakeProtobufArrayList<E> private constructor(
        private val elements: ArrayList<E>,
        private val isMutable: Boolean = true
    ) : AbstractList<E>(), FakeProtobufList<E> {

        constructor(items: Collection<E>) : this(ArrayList(items), true)

        override val size: Int
            get() = elements.size

        override fun get(index: Int): E = elements[index]

        override fun add(index: Int, element: E) {
            check(isMutable) { "List is immutable" }
            elements.add(index, element)
        }

        override fun removeAt(index: Int): E {
            check(isMutable) { "List is immutable" }
            return elements.removeAt(index)
        }

        override fun set(index: Int, element: E): E {
            check(isMutable) { "List is immutable" }
            return elements.set(index, element)
        }

        override fun mutableCopyWithCapacity(capacity: Int): FakeProtobufList<E> {
            require(capacity >= size) { "capacity must be >= size(): $capacity < $size" }
            return FakeProtobufArrayList(ArrayList(elements), true)
        }
    }

    interface ObfuscatedProtobufList : List<Any?>, RandomAccess {
        fun h(i: Int): ObfuscatedProtobufList
    }

    class ObfuscatedListImpl(
        private val elements: ArrayList<Any?> = ArrayList()
    ) : AbstractList<Any?>(), ObfuscatedProtobufList, MutableList<Any?> {

        override val size: Int
            get() = elements.size

        override fun get(index: Int): Any? = elements[index]

        override fun add(index: Int, element: Any?) {
            elements.add(index, element)
        }

        override fun removeAt(index: Int): Any? = elements.removeAt(index)

        override fun set(index: Int, element: Any?): Any? = elements.set(index, element)

        override fun h(i: Int): ObfuscatedProtobufList {
            require(i >= size) { "Protobuf capacity requirement failed: $i < $size" }
            return ObfuscatedListImpl(ArrayList(elements))
        }
    }

    @Test
    fun returnsSameInstanceWhenNothingIsFiltered() {
        val original = FakeProtobufArrayList(listOf("a", "b", "c"))
        val result = ProtoLists.filter(original) { true }
        assertSame(original, result)
    }

    @Test
    fun filtersStandardProtobufListPreservingInterfaceTypeAndContents() {
        val original = FakeProtobufArrayList(listOf("item1", "item2", "item3"))
        val result = ProtoLists.filter(original) { it != "item2" }

        assertTrue(result is FakeProtobufList<*>)
        assertEquals(listOf("item1", "item3"), result)
    }

    @Test
    fun filtersObfuscatedSpotifyProtobufListPreservingObfuscatedInterface() {
        val original = ObfuscatedListImpl().apply {
            addAll(listOf("section1", "ad_section", "section2"))
        }

        val result = ProtoLists.filter(original) { it != "ad_section" }

        assertTrue(result is ObfuscatedProtobufList)
        assertEquals(listOf("section1", "section2"), result)
    }

    @Test
    fun handlesEmptyFilterResultPreservingProtobufListType() {
        val original = ObfuscatedListImpl().apply {
            addAll(listOf("ad1", "ad2"))
        }

        val result = ProtoLists.filter(original) { false }

        assertTrue(result is ObfuscatedProtobufList)
        assertEquals(0, result?.size)
    }

    interface UncustomListInterface : List<String>, RandomAccess

    @Test
    fun fallsBackToProxyWhenGrowMethodIsAbsentOnInterface() {
        val originalList = object : AbstractList<String>(), UncustomListInterface {
            private val data = listOf("x", "y", "z")
            override val size: Int get() = data.size
            override fun get(index: Int): String = data[index]
        }

        val result = ProtoLists.filter(originalList) { it != "y" }
        assertTrue(result is UncustomListInterface)
        assertEquals(listOf("x", "z"), result)
    }

    @Test
    fun plainArraylistReturnsArraylist() {
        val original = arrayListOf("one", "two", "three")
        val result = ProtoLists.filter(original) { it != "two" }
        assertTrue(result is ArrayList<*>)
        assertEquals(listOf("one", "three"), result)
    }
}
