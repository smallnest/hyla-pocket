package io.rpcx.hylapocket.data

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import java.io.File
import java.security.MessageDigest

// JetBrains Mono Nerd Font, which the APK carries in its assets: a terminal's font with its icons
// (the branch marks, the arrows, the powerline glyphs) in one cell each, and no download behind it.

internal data class FontAsset(val name: String, val sha256: String)

/** Installs the family from the APK's assets; an incomplete copy never becomes the active font. */
internal class FontFiles(private val root: File, private val assets: AssetManager, private val files: List<FontAsset> = JETBRAINS) {
    val directory = File(root, "jetbrains-mono-nerd-v1")

    fun available(): Boolean = files.all { matches(File(directory, it.name), it.sha256) }

    fun install(progress: (Float) -> Unit = {}): File {
        if (available()) return directory
        check(root.isDirectory || root.mkdirs()) { "Could not make the font directory" }
        val staging = File(root, "jetbrains-mono-nerd-v1.partial")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not make the font directory" }
        try {
            files.forEachIndexed { index, asset ->
                val target = File(staging, asset.name)
                assets.open(ASSET_DIR + asset.name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                check(matches(target, asset.sha256)) { "Font checksum mismatch" }
                progress((index + 1f) / files.size)
            }
            directory.deleteRecursively()
            check(staging.renameTo(directory)) { "Could not save fonts" }
            return directory
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun matches(file: File, expected: String): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected
    }.getOrDefault(false)

    companion object {
        private const val MAX_BYTES = 8_388_608L
        private const val ASSET_DIR = "fonts/"
        private val JETBRAINS = listOf(
            FontAsset("JetBrainsMonoNerdFontMono-Regular.ttf", "f2a5ea6cfab397445ffab00c0370927b66d61e560a05db5db271b42006381c1a"),
            FontAsset("JetBrainsMonoNerdFontMono-Bold.ttf", "bfcf9a917276ffc058867d87cbc8a5b2f1ab0f4b710e9170dc02763ccb80bd4b"),
            FontAsset("JetBrainsMonoNerdFontMono-Italic.ttf", "31efd6ead98746f5b0afa1ee6dba60267ad48db36428360bee327bec10621f97"),
            FontAsset("JetBrainsMonoNerdFontMono-BoldItalic.ttf", "9dba502e00e35209f6ed2a151c7376c051657b067cdebbc6e52d06cb9002cf31"),
        )
    }
}

/** The installed family, with the system's for what it lacks; a phone before Android 10 gets the regular face. */
internal fun downloadedTypeface(files: File): Typeface {
    val regular = File(files, "JetBrainsMonoNerdFontMono-Regular.ttf")
    if (Build.VERSION.SDK_INT < 29) return Typeface.createFromFile(regular)
    val family = FontFamily.Builder(Font.Builder(regular).build())
    for ((name, weight, slant) in listOf(
        Triple("JetBrainsMonoNerdFontMono-Bold.ttf", 700, FontStyle.FONT_SLANT_UPRIGHT),
        Triple("JetBrainsMonoNerdFontMono-Italic.ttf", 400, FontStyle.FONT_SLANT_ITALIC),
        Triple("JetBrainsMonoNerdFontMono-BoldItalic.ttf", 700, FontStyle.FONT_SLANT_ITALIC),
    )) family.addFont(Font.Builder(File(files, name)).setWeight(weight).setSlant(slant).build())
    return Typeface.CustomFallbackBuilder(family.build()).setSystemFallback("sans-serif").build()
}

internal data class TerminalFontState(
    val selected: Boolean = false,
    val available: Boolean = false,
    val initializing: Boolean = true,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val failed: Boolean = false,
)
