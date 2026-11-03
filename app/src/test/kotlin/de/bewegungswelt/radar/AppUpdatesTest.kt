package de.bewegungswelt.radar

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppUpdatesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packages = shadowOf(context.packageManager)
    private val payload = byteArrayOf(1, 7, 2, 9)
    private val release = AppRelease("0.3.0", 3, assetUrl(), payload.size.toLong())
    private val partial get() = File(context.cacheDir, "updates/update.part")
    private val apk get() = File(context.cacheDir, "updates/update.apk")

    @Before
    fun installedApp() {
        val installed = info(2, "0.2.0")
        installed.applicationInfo = context.applicationInfo
        installed.providers = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA).providers
        packages.installPackage(installed)
    }

    @Test
    fun `only a newer stable release from the configured repository is offered`() = runBlocking {
        assertEquals(release, repository(metadata()).check())
        assertNull(repository(metadata(code = 2)).check())
        assertNull(repository(metadata(code = 1)).check())
        assertNull(repository("", status = 404).check())
        for (invalid in listOf(
            metadata(prerelease = true), metadata(draft = true),
            metadata(url = "https://example.org/other.apk"),
            metadata(size = 104_857_601), metadata(size = 0),
            metadata().replace("v0.3.0", "v0.3.0-rc1"),
            metadata().replace("bewegungswelt-radar-3.apk", "other.apk"),
            metadata().replace("\"assets\":[", "\"assets\":[${asset()},"),
            "{}", "not json", " ".repeat(1_048_577),
        )) {
            assertTrue("Accepted invalid release: $invalid", runCatching { repository(invalid).check() }.isFailure)
        }
        for (status in listOf(403, 429, 500)) {
            assertTrue(runCatching { repository("", status).check() }.isFailure)
        }
    }

    @Suppress("DEPRECATION") // Robolectric's intent registration API; not an Android platform call.
    @Test
    fun `download is complete and trusted before the installer gets a scoped content URI`() = runBlocking {
        packages.setPackageArchiveInfo(partial.path, info(3, "0.3.0"))
        val file = repository(payload).download(release)
        assertArrayEquals(payload, file.readBytes())
        assertFalse(partial.exists())
        assertTrue(runCatching { appInstallIntent(context, file) }.isFailure)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(
            android.net.Uri.parse("content://${context.packageName}.updates/updates/update.apk"),
            "application/vnd.android.package-archive",
        )
        fun handler(name: String, system: Boolean) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = name
                this.name = "$name.Installer"
                applicationInfo = ApplicationInfo().apply { flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0 }
            }
        }
        packages.addResolveInfoForIntent(view, handler("untrusted.handler", false))
        assertTrue(runCatching { appInstallIntent(context, file) }.isFailure)
        packages.addResolveInfoForIntent(view, handler("android.installer", true))
        val intent = appInstallIntent(context, file)
        assertEquals("android.installer", intent.component!!.packageName)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertArrayEquals(payload, context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        assertEquals("package:${context.packageName}", appInstallPermissionIntent(context).dataString)
        assertTrue(runCatching { appInstallIntent(context, File(context.cacheDir, "private.txt")) }.isFailure)
    }

    @Test
    fun `wrong key package version and unsigned archives never leave an installable file`() = runBlocking {
        for (archive in listOf(
            info(3, "0.3.0", signer = "ff"), info(3, "0.3.0", packageName = "other.app"),
            info(2, "0.3.0"), info(4, "0.3.0"), info(3, "9.9.9"), info(3, "0.3.0", signer = null),
        )) {
            packages.setPackageArchiveInfo(partial.path, archive)
            assertTrue(runCatching { repository(payload).download(release) }.isFailure)
            assertFalse(apk.exists())
            assertFalse(partial.exists())
        }
    }

    @Test
    fun `truncated oversized invalid and interrupted downloads are removed and retry succeeds`() = runBlocking {
        for (body in listOf(payload.dropLast(1).toByteArray(), payload + 0, byteArrayOf())) {
            assertTrue(runCatching { repository(body).download(release) }.isFailure)
            assertFalse(apk.exists())
            assertFalse(partial.exists())
        }
        assertTrue(runCatching { repository(payload).download(release) }.isFailure) // no APK metadata
        val offline = AppUpdateRepository(context, OkHttpClient.Builder().addInterceptor { throw IOException() }.build())
        assertTrue(runCatching { offline.download(release) }.isFailure)
        assertFalse(apk.exists())
        assertFalse(partial.exists())
        packages.setPackageArchiveInfo(partial.path, info(3, "0.3.0"))
        assertArrayEquals(payload, repository(payload).download(release).readBytes())
    }

    private fun repository(body: String, status: Int = 200) = repository(body.toByteArray(), status)

    private fun repository(body: ByteArray, status: Int = 200) = AppUpdateRepository(context,
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody()).build()
        }.build(),
    )

    @Suppress("DEPRECATION")
    private fun info(code: Int, version: String, signer: String? = "01", packageName: String = context.packageName) =
        PackageInfo().apply {
            this.packageName = packageName
            versionCode = code
            versionName = version
            val certificates = signer?.let { arrayOf(Signature(it)) }.orEmpty()
            signatures = certificates
            if (Build.VERSION.SDK_INT >= 28) signingInfo = SigningInfo().also {
                shadowOf(it).setSignatures(certificates)
            }
        }

    private fun metadata(code: Int = 3, size: Long = 4, url: String = assetUrl(code), prerelease: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"v0.3.0","draft":$draft,"prerelease":$prerelease,"assets":[${asset(code, size, url)}]}"""

    private fun asset(code: Int = 3, size: Long = 4, url: String = assetUrl(code)) =
        """{"name":"bewegungswelt-radar-$code.apk","size":$size,"browser_download_url":"$url"}"""

    private fun assetUrl(code: Int = 3) =
        "https://github.com/tomatitito/bewegungswelt-radar/releases/download/v0.3.0/bewegungswelt-radar-$code.apk"
}
