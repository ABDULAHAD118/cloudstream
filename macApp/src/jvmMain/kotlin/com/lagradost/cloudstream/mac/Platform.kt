package com.lagradost.cloudstream.mac

import java.io.File

/** The app is built for macOS and Windows; these are the few places they differ. */
val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Where app data lives: ~/Library/Application Support on macOS, %APPDATA% on Windows. */
val defaultDataDir: String =
    if (isWindows) "${System.getenv("APPDATA") ?: System.getProperty("user.home")}\\CloudStream"
    else "${System.getProperty("user.home")}/Library/Application Support/CloudStream"

/** The user's videos folder: Movies on macOS, Videos on Windows. */
val videosFolderName = if (isWindows) "Videos" else "Movies"

/** Finder on macOS, Explorer on Windows. */
val fileManagerName = if (isWindows) "Explorer" else "Finder"

fun openFolder(dir: File) {
    dir.mkdirs()
    ProcessBuilder(if (isWindows) listOf("explorer.exe", dir.path) else listOf("open", dir.path)).start()
}

/** Opens the file manager with [file] selected. */
fun revealFile(file: File) {
    ProcessBuilder(if (isWindows) listOf("explorer.exe", "/select,${file.path}") else listOf("open", "-R", file.path)).start()
}
