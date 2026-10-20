package com.lagradost.cloudstream.mac

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExtensionRuntimeTest {
    /** A jar with one class shaped like an extension's settings code: it extends a missing fragment class,
     *  casts the host context to AppCompatActivity and reads preferences through it. */
    private fun fakeExtensionJar(): File {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, "ext/Settings", null, "androidx/fragment/app/DialogFragment", null)
        cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "androidx/fragment/app/DialogFragment", "<init>", "()V", false)
            visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(ACC_PUBLIC, "prefs", "(Landroid/content/Context;)Ljava/lang/Object;", null, null).apply {
            visitVarInsn(ALOAD, 1); visitTypeInsn(CHECKCAST, "androidx/appcompat/app/AppCompatActivity")
            visitLdcInsn("p"); visitInsn(ICONST_0)
            visitMethodInsn(INVOKEVIRTUAL, "androidx/appcompat/app/AppCompatActivity", "getSharedPreferences", "(Ljava/lang/String;I)Landroid/content/SharedPreferences;", false)
            visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(ACC_PUBLIC, "ui", "()Ljava/lang/Object;", null, null).apply {
            // A call on an unrelated missing UI class does nothing and returns null
            visitInsn(ACONST_NULL); visitMethodInsn(INVOKESTATIC, "android/widget/Toast", "makeText", "(Ljava/lang/Object;)Landroid/widget/Toast;", false)
            visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(ACC_PRIVATE, "restart", "(Landroid/content/ComponentName;)V", null, null).apply {
            // ComponentName only passes through signatures, as in CNC Verse's CineVood restartApp
            visitVarInsn(ALOAD, 1)
            visitMethodInsn(INVOKESTATIC, "android/content/Intent", "makeRestartActivityTask", "(Landroid/content/ComponentName;)Landroid/content/Intent;", false)
            visitInsn(POP); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return File.createTempFile("ext", ".jar").apply {
            deleteOnExit()
            JarOutputStream(outputStream()).use { it.putNextEntry(JarEntry("ext/Settings.class")); it.write(cw.toByteArray()) }
        }
    }

    @Test
    fun missingAndroidClassesAreStubbedAndActivityIsAContext() {
        val loader = ExtensionClassLoader(fakeExtensionJar(), javaClass.classLoader)
        val host = loader.hostContext()
        assertEquals("androidx.appcompat.app.AppCompatActivity", host.javaClass.name)
        val settings = loader.loadClass("ext.Settings").getDeclaredConstructor().newInstance()
        // Context's real getSharedPreferences, not an empty stub
        assertIs<SharedPreferences>(settings.javaClass.getMethod("prefs", Context::class.java).invoke(settings, host))
        assertNull(settings.javaClass.getMethod("ui").invoke(settings))
        // Reflection (as when the plugin class is created) loads every signature type, ComponentName included
        assertEquals(3, settings.javaClass.declaredMethods.size)
    }

    @Test
    fun base64IsLenientLikeAndroid() {
        assertEquals("hello?", String(Base64.decode("aGVsbG8_\n", Base64.DEFAULT)))
        assertEquals("aGVsbG8_", Base64.encodeToString("hello?".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        assertEquals("aGk=\n", Base64.encodeToString("hi".toByteArray(), Base64.DEFAULT))
    }

    @Test
    fun uriParts() {
        val uri = Uri.parse("https://host.tv:8080/a/b/c.m3u8?id=4%202&x=1#t")
        assertEquals("host.tv", uri.getHost())
        assertEquals("c.m3u8", uri.getLastPathSegment())
        assertEquals("4 2", uri.getQueryParameter("id"))
        assertEquals(8080, uri.getPort())
    }
}
