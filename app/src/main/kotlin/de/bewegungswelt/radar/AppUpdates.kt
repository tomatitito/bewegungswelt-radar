package de.bewegungswelt.radar

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AppRelease(val version: String, val code: Long, val url: String, val size: Long)

/** Public, anonymous GitHub reads only. No calendar/provider data enters this path. */
internal class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .callTimeout(2, TimeUnit.MINUTES).build(),
) {
    private val packages = context.packageManager
    private val installed get() = packages.getPackageInfo(context.packageName, signatureFlags)
    val installedVersion: String get() = installed.versionName.orEmpty()

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/tomatitito/bewegungswelt-radar/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        client.newCall(request).execute().use { response ->
            // GitHub returns 404 when no public stable release has been published yet.
            if (response.code == 404) return@withContext null
            check(response.isSuccessful) { "Release check failed" }
            val source = response.body.source()
            check(!source.request(1_048_577))
            val json = JsonParser.parseString(source.readUtf8()).asJsonObject
            check(!json.get("draft").asBoolean && !json.get("prerelease").asBoolean)
            val tag = json.get("tag_name").asString
            check(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag))
            val assets = json.getAsJsonArray("assets").map { it.asJsonObject }.filter {
                Regex("bewegungswelt-radar-[1-9][0-9]*\\.apk").matches(it.get("name").asString)
            }
            check(assets.size == 1)
            val asset = assets.single()
            val name = asset.get("name").asString
            val code = name.removePrefix("bewegungswelt-radar-").removeSuffix(".apk").toLong()
            val size = asset.get("size").asLong
            check(size in 1..MAX_APK_BYTES)
            val url = "https://github.com/tomatitito/bewegungswelt-radar/releases/download/".toHttpUrl()
                .newBuilder().addPathSegment(tag).addPathSegment(name).build().toString()
            check(asset.get("browser_download_url").asString == url)
            if (code <= installed.code()) null else AppRelease(tag.removePrefix("v"), code, url, size)
        }
    }

    suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "update.part")
        val apk = File(directory, "update.apk")
        apk.delete()
        try {
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                check(response.isSuccessful)
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            check(total <= release.size && total <= MAX_APK_BYTES)
                            output.write(buffer, 0, count)
                        }
                        check(total == release.size)
                    }
                }
            }
            validate(partial, release)
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(apk))
            apk
        } finally {
            partial.delete()
        }
    }

    private fun validate(file: File, release: AppRelease) {
        val archive = packages.getPackageArchiveInfo(file.path, signatureFlags)
            ?: throw IOException("Invalid APK")
        val current = installed
        check(archive.packageName == context.packageName && archive.code() == release.code
            && archive.code() > current.code() && archive.versionName == release.version)
        val trusted = current.signers()
        check(trusted.isNotEmpty() && archive.signers() == trusted) { "Signing certificate mismatch" }
    }

    companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
        @Suppress("DEPRECATION")
        private val signatureFlags: Int get() = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signers(): Set<String> =
    (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
        .orEmpty().map { it.toCharsString() }.toSet()

internal data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val checked: Boolean = false,
    val release: AppRelease? = null,
    val apk: File? = null,
    val error: Boolean = false,
)

internal class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    val installedVersion get() = repository.installedVersion

    fun check() {
        if (state.value.checking || state.value.downloading) return
        mutableState.value = AppUpdateState(checking = true)
        viewModelScope.launch {
            try {
                mutableState.value = AppUpdateState(checked = true, release = repository.check())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = AppUpdateState(error = true)
            }
        }
    }

    fun download() {
        val previous = state.value.copy(apk = null)
        val release = previous.release ?: return
        if (previous.checking || previous.downloading) return
        mutableState.value = previous.copy(downloading = true, error = false)
        viewModelScope.launch {
            try {
                mutableState.value = previous.copy(apk = repository.download(release), error = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = previous.copy(error = true)
            }
        }
    }
}

/** The system, not this app, grants install permission and confirms every installation. */
internal fun appInstallIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Never grant the APK to a third-party activity advertising an APK MIME filter.
    val installer = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull {
            it.activityInfo.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }?.activityInfo ?: throw IOException("No system package installer")
    return intent.setComponent(ComponentName(installer.packageName, installer.name))
}

internal fun appInstallPermissionIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"),
)
