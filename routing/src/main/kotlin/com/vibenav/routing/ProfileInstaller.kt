package com.vibenav.routing

import java.io.File

/** Copies the bundled BRouter profile and lookup table to a directory BRouter can read from. */
object ProfileInstaller {
    private val FILES = listOf(BRouterEngine.PROFILE_FILE, "lookups.dat")

    fun install(targetDir: File): File {
        targetDir.mkdirs()
        for (name in FILES) {
            val bytes = ProfileInstaller::class.java.getResourceAsStream("/brouter/$name")
                ?.use { it.readBytes() }
                ?: error("Missing bundled resource brouter/$name")
            val target = File(targetDir, name)
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        }
        return targetDir
    }
}
