package com.futurehouser.instarecording

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Finds new versions in the GitHub releases and hands the APK to Android's installer. */
object Updater {
    private const val ASSET = "Insta-recording.apk"
    const val ACTION_INSTALL_STATUS = "com.futurehouser.instarecording.INSTALL_STATUS"

    class Release(val version: String, val url: String, val size: Long, val sha256: String?)

    /** The latest release, or null if it isn't newer than this app or has no APK. */
    fun findUpdate(): Release? {
        val body = open(BuildConfig.RELEASES_API).run {
            setRequestProperty("Accept", "application/vnd.github+json")
            inputStream.bufferedReader().use { it.readText() }
        }
        val release = JSONObject(body)
        val version = release.getString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == ASSET) {
                val digest = asset.optString("digest")
                return Release(version, asset.getString("browser_download_url"), asset.getLong("size"),
                    digest.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"))
            }
        }
        return null
    }

    private fun isNewer(version: String, current: String): Boolean {
        val a = version.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    fun download(release: Release, dest: File, onProgress: (Int) -> Unit) {
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        open(release.url).inputStream.use { input ->
            dest.outputStream().use { output ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    output.write(chunk, 0, read)
                    sha.update(chunk, 0, read)
                    done += read
                    onProgress((done * 100 / release.size.coerceAtLeast(1)).toInt())
                }
            }
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (done != release.size || (release.sha256 != null && release.sha256 != hex)) {
            dest.delete()
            error("The download was incomplete or corrupted. Please try again.")
        }
    }

    /** Android asks the user to confirm; the result comes back to MainActivity.onNewIntent. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(status.intentSender)
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "Insta-recording")
        connectTimeout = 15_000
        readTimeout = 30_000
    }
}
