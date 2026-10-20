package com.lagradost.cloudstream.mac

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.objectweb.asm.*
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** SharedPreferences for extensions, one JSON file per name, like Android's shared_prefs folder. */
object Preferences {
    private val dir = File(Extensions.dataDir, "shared_prefs").apply { mkdirs() }
    private val open = ConcurrentHashMap<String, Store>()

    fun named(name: String): SharedPreferences = open.getOrPut(name) { Store(File(dir, "${name.replace(Regex("[^\\w.-]"), "_")}.json")) }

    private class Store(private val file: File) : SharedPreferences {
        private val values: MutableMap<String, Any?> = ConcurrentHashMap<String, Any?>().apply {
            runCatching { parseJson(file.readText(), Map::class) }.getOrNull()?.forEach { (k, v) -> if (k is String && v != null) put(k, v) }
        }
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        // JSON brings numbers back as Int/Double and sets as lists, so convert on the way out
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?) =
            (values[key] as? Collection<*>)?.map { it.toString() }?.toSet() ?: defValues
        override fun getInt(key: String?, defValue: Int) = (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long) = (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float) = (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = key != null && values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listener?.let(listeners::add) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>() // null means remove
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { if (key != null) changes[key] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: Set<String>?) = put(key, values?.toList())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear() = apply { clear = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                synchronized(file) {
                    if (clear) values.clear()
                    changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                    file.writeText(values.toJson())
                }
                changes.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@Store, key) } }
                return true
            }
        }
    }
}

/** Toast messages from extensions, shown at the bottom of the window. */
object Toasts {
    var current by mutableStateOf<String?>(null)
    fun show(message: String?) {
        println("Toast: $message")
        current = message
    }
}

/**
 * Loads a converted extension. Android classes the app doesn't provide (views, dialogs, fragments: mostly
 * settings screens) are generated on the fly as empty stand-ins with exactly the members the extension uses,
 * so the extension loads and scrapes. Calling into one does nothing and returns null/0.
 */
class ExtensionClassLoader(private val jar: File, parent: ClassLoader) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
    private class Stub(var isInterface: Boolean = false) {
        val methods = mutableSetOf<Triple<String, String, Boolean>>() // name, descriptor, static
        val fields = mutableSetOf<Triple<String, String, Boolean>>()
    }

    private val stubbable = listOf("android/", "androidx/", "com/google/android/", "org/xmlpull/")
    private val stubs: Map<String, Stub> by lazy { scan() }

    /** Android's Context subclasses, most general first. Stubs of these extend each other and our real Context. */
    private val contextChain = listOf(
        "android/content/ContextWrapper", "android/view/ContextThemeWrapper", "android/app/Activity",
        "androidx/core/app/ComponentActivity", "androidx/activity/ComponentActivity",
        "androidx/fragment/app/FragmentActivity", "androidx/appcompat/app/AppCompatActivity",
    )
    private val contextMethods by lazy {
        android.content.Context::class.java.methods.map { it.name + Type.getMethodDescriptor(it) }.toSet()
    }

    /**
     * The Context handed to `Plugin.load`. On Android that is the app's AppCompatActivity, and extensions cast
     * it, so give them the most specific Activity class they reference.
     */
    fun hostContext(): android.content.Context =
        contextChain.lastOrNull { it in stubs }?.let { loadClass(it.replace('/', '.')).getDeclaredConstructor().newInstance() as android.content.Context }
            ?: android.content.Context()

    override fun findClass(name: String): Class<*> = try {
        super.findClass(name)
    } catch (e: ClassNotFoundException) {
        val stub = stubs[name.replace('.', '/')] ?: throw e
        val bytes = generate(name.replace('.', '/'), stub)
        defineClass(name, bytes, 0, bytes.size)
    }

    private fun exists(internalName: String) =
        runCatching { Class.forName(internalName.replace('/', '.'), false, parent) }.isSuccess

    private fun scan(): Map<String, Stub> {
        data class Own(val superName: String?, val members: MutableSet<String> = mutableSetOf())
        val own = mutableMapOf<String, Own>()
        val uses = mutableListOf<(Map<String, Own>) -> Unit>()
        val stubs = mutableMapOf<String, Stub>()
        fun stub(owner: String): Stub? =
            if (stubbable.any(owner::startsWith) && owner !in own && !exists(owner)) stubs.getOrPut(owner) { Stub() } else null

        // Types that only pass through signatures (say a ComponentName handed from one Android call to another)
        // still have to load, so they get empty stand-ins too
        fun signature(desc: String) {
            val types = if (desc.startsWith("(")) Type.getArgumentTypes(desc) + Type.getReturnType(desc) else arrayOf(Type.getType(desc))
            types.map { if (it.sort == Type.ARRAY) it.elementType else it }.filter { it.sort == Type.OBJECT }
                .forEach { t -> uses += { stub(t.internalName) } }
        }

        // Members used through an extension class but declared by a missing superclass belong on that stub
        fun owning(owner: String, member: String, classes: Map<String, Own>): String? {
            var current: String? = owner
            while (current != null) {
                val info = classes[current] ?: return current
                if (member in info.members) return null
                current = info.superName
            }
            return null
        }

        ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                ClassReader(zip.getInputStream(entry).readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
                    lateinit var info: Own
                    override fun visit(v: Int, a: Int, name: String, sig: String?, superName: String?, ifs: Array<out String>?) {
                        info = Own(superName).also { own[name] = it }
                        uses += { superName?.let(::stub) }
                        ifs?.forEach { i -> uses += { stub(i)?.isInterface = true } }
                    }

                    override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? { info.members += n; signature(d); return null }

                    override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<out String>?): MethodVisitor {
                        info.members += n + d
                        signature(d)
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                                if (owner.startsWith("[")) return
                                signature(desc)
                                uses += { classes ->
                                    owning(owner, name + desc, classes)?.let(::stub)?.let {
                                        if (itf) it.isInterface = true
                                        it.methods += Triple(name, desc, op == Opcodes.INVOKESTATIC)
                                    }
                                }
                            }

                            override fun visitFieldInsn(op: Int, owner: String, name: String, desc: String) {
                                signature(desc)
                                uses += { classes ->
                                    owning(owner, name, classes)?.let(::stub)?.fields
                                        ?.add(Triple(name, desc, op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC))
                                }
                            }

                            override fun visitTypeInsn(op: Int, type: String) {
                                if (!type.startsWith("[")) uses += { stub(type) }
                            }
                        }
                    }
                }, ClassReader.SKIP_DEBUG)
            }
        }
        uses.forEach { it(own) }
        // An Activity stub must be a Context, so the whole chain beneath it has to exist too
        contextChain.indexOfLast { it in stubs }.takeIf { it >= 0 }?.let { last ->
            contextChain.take(last + 1).forEach { if (!exists(it)) stubs.getOrPut(it) { Stub() } }
        }
        return stubs
    }

    private fun generate(name: String, stub: Stub): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val access = Opcodes.ACC_PUBLIC or if (stub.isInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else 0
        val chainIndex = contextChain.indexOf(name)
        val superName = when {
            chainIndex < 0 -> "java/lang/Object"
            chainIndex == 0 -> "android/content/Context"
            else -> contextChain[chainIndex - 1]
        }
        cw.visit(Opcodes.V1_8, access, name, null, superName, null)
        stub.fields.forEach { (n, d, static) ->
            cw.visitField(Opcodes.ACC_PUBLIC or if (static) Opcodes.ACC_STATIC else 0, n, d, null, null).visitEnd()
        }
        // Context's real methods (getSharedPreferences, ...) must not be shadowed by empty ones
        val methods = stub.methods.filterTo(mutableSetOf()) { chainIndex < 0 || (it.first + it.second) !in contextMethods }
        if (!stub.isInterface) methods += Triple("<init>", "()V", false) // lets extension classes subclass it
        methods.forEach { (n, d, static) ->
            if (stub.isInterface && !static) {
                cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, n, d, null, null).visitEnd()
                return@forEach
            }
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or if (static) Opcodes.ACC_STATIC else 0, n, d, null, null)
            mv.visitCode()
            if (n == "<init>") {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
                mv.visitInsn(Opcodes.RETURN)
            } else {
                val ret = Type.getReturnType(d)
                when (ret.sort) {
                    Type.VOID -> mv.visitInsn(Opcodes.RETURN)
                    Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN) }
                    Type.LONG -> { mv.visitInsn(Opcodes.LCONST_0); mv.visitInsn(Opcodes.LRETURN) }
                    Type.FLOAT -> { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN) }
                    Type.DOUBLE -> { mv.visitInsn(Opcodes.DCONST_0); mv.visitInsn(Opcodes.DRETURN) }
                    else -> {
                        // Builder-style calls (setX(...) returning the same type) keep working when chained
                        if (!static && ret.internalName == name) mv.visitVarInsn(Opcodes.ALOAD, 0) else mv.visitInsn(Opcodes.ACONST_NULL)
                        mv.visitInsn(Opcodes.ARETURN)
                    }
                }
            }
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
