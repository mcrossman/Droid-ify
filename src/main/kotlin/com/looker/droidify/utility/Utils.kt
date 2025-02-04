package com.looker.droidify.utility

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.looker.droidify.*
import com.looker.droidify.content.Preferences
import com.looker.droidify.database.entity.Installed
import com.looker.droidify.database.entity.Repository
import com.looker.droidify.entity.InstalledItem
import com.looker.droidify.entity.Product
import com.looker.droidify.entity.ProductItem
import com.looker.droidify.service.Connection
import com.looker.droidify.service.DownloadService
import com.looker.droidify.utility.extension.android.Android
import com.looker.droidify.utility.extension.android.singleSignature
import com.looker.droidify.utility.extension.android.versionCodeCompat
import com.looker.droidify.utility.extension.json.Json
import com.looker.droidify.utility.extension.json.parseDictionary
import com.looker.droidify.utility.extension.json.writeDictionary
import com.looker.droidify.utility.extension.resources.getColorFromAttr
import com.looker.droidify.utility.extension.resources.getDrawableCompat
import com.looker.droidify.utility.extension.text.hex
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.util.*

object Utils {
    private fun createDefaultApplicationIcon(context: Context, tintAttrResId: Int): Drawable {
        return context.getDrawableCompat(R.drawable.ic_application_default).mutate()
            .apply { setTintList(context.getColorFromAttr(tintAttrResId)) }
    }

    fun PackageInfo.toInstalledItem(): InstalledItem {
        val signatureString = singleSignature?.let(Utils::calculateHash).orEmpty()
        return InstalledItem(packageName, versionName.orEmpty(), versionCodeCompat, signatureString)
    }

    fun getDefaultApplicationIcons(context: Context): Pair<Drawable, Drawable> {
        val progressIcon: Drawable =
            createDefaultApplicationIcon(context, android.R.attr.textColorSecondary)
        val defaultIcon: Drawable =
            createDefaultApplicationIcon(context, R.attr.colorAccent)
        return Pair(progressIcon, defaultIcon)
    }

    fun getToolbarIcon(context: Context, resId: Int): Drawable {
        return context.getDrawableCompat(resId).mutate()
    }

    fun calculateHash(signature: Signature): String {
        return MessageDigest.getInstance("MD5").digest(signature.toCharsString().toByteArray())
            .hex()
    }

    fun calculateFingerprint(certificate: Certificate): String {
        val encoded = try {
            certificate.encoded
        } catch (e: CertificateEncodingException) {
            null
        }
        return encoded?.let(::calculateFingerprint).orEmpty()
    }

    fun calculateFingerprint(key: ByteArray): String {
        return if (key.size >= 256) {
            try {
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(key)
                val builder = StringBuilder()
                for (byte in fingerprint) {
                    builder.append("%02X".format(Locale.US, byte.toInt() and 0xff))
                }
                builder.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        } else {
            ""
        }
    }

    val rootInstallerEnabled: Boolean
        get() = Preferences[Preferences.Key.RootPermission] &&
                (Shell.getCachedShell()?.isRoot ?: Shell.getShell().isRoot)

    suspend fun startUpdate(
        packageName: String,
        installed: Installed?,
        products: List<Pair<Product, Repository>>,
        downloadConnection: Connection<DownloadService.Binder, DownloadService>,
    ) {
        val productRepository = Product.findSuggested(products, installed) { it.first }
        val compatibleReleases = productRepository?.first?.selectedReleases.orEmpty()
            .filter { installed == null || installed.signature == it.signature }
        val releaseFlow = MutableStateFlow(compatibleReleases.firstOrNull())
        if (compatibleReleases.size > 1) {
            releaseFlow.emit(
                compatibleReleases
                    .filter { it.platforms.contains(Android.primaryPlatform) }
                    .minByOrNull { it.platforms.size }
                    ?: compatibleReleases.minByOrNull { it.platforms.size }
                    ?: compatibleReleases.firstOrNull()
            )
        }
        val binder = downloadConnection.binder
        releaseFlow.collect {
            if (productRepository != null && it != null && binder != null) {
                binder.enqueue(
                    packageName,
                    productRepository.first.name,
                    productRepository.second,
                    it
                )
            }
        }
    }

    fun Context.setLanguage(): Configuration {
        var setLocalCode = Preferences[Preferences.Key.Language]
        if (setLocalCode == PREFS_LANGUAGE_DEFAULT) {
            setLocalCode = Locale.getDefault().language
        }
        val config = resources.configuration
        val sysLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            config.locale
        }
        if (setLocalCode != sysLocale.language || setLocalCode != "${sysLocale.language}-r${sysLocale.country}") {
            val newLocale = getLocaleOfCode(setLocalCode)
            Locale.setDefault(newLocale)
            config.setLocale(newLocale)
        }
        return config
    }

    val languagesList: List<String>
        get() {
            val entryVals = arrayOfNulls<String>(1)
            entryVals[0] = PREFS_LANGUAGE_DEFAULT
            return entryVals.plus(BuildConfig.DETECTED_LOCALES.sorted()).filterNotNull()
        }

    fun translateLocale(locale: Locale): String {
        val country = locale.getDisplayCountry(locale)
        val language = locale.getDisplayLanguage(locale)
        return (language.replaceFirstChar { it.uppercase(Locale.getDefault()) }
                + (if (country.isNotEmpty() && country.compareTo(language, true) != 0)
            "($country)" else ""))
    }

    fun Context.getLocaleOfCode(localeCode: String): Locale = when {
        localeCode.isEmpty() -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            resources.configuration.locale
        }
        localeCode.contains("-r") -> Locale(
            localeCode.substring(0, 2),
            localeCode.substring(4)
        )
        else -> Locale(localeCode)
    }

    /**
     * Checks if app is currently considered to be in the foreground by Android.
     */
    fun inForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        val importance = appProcessInfo.importance
        return ((importance == IMPORTANCE_FOREGROUND) or (importance == IMPORTANCE_VISIBLE))
    }

}

fun Cursor.getProduct(): Product = getBlob(getColumnIndex(ROW_DATA))
    .jsonParse {
        Product.deserialize(it).apply {
            this.repositoryId = getLong(getColumnIndex(ROW_REPOSITORY_ID))
            this.description = getString(getColumnIndex(ROW_DESCRIPTION))
        }
    }


fun Cursor.getProductItem(): ProductItem = getBlob(getColumnIndex(ROW_DATA_ITEM))
    .jsonParse {
        ProductItem.deserialize(it).apply {
            this.repositoryId = getLong(getColumnIndex(ROW_REPOSITORY_ID))
            this.packageName = getString(getColumnIndex(ROW_PACKAGE_NAME))
            this.name = getString(getColumnIndex(ROW_NAME))
            this.summary = getString(getColumnIndex(ROW_SUMMARY))
            this.installedVersion = getString(getColumnIndex(ROW_VERSION))
                .orEmpty()
            this.compatible = getInt(getColumnIndex(ROW_COMPATIBLE)) != 0
            this.canUpdate = getInt(getColumnIndex(ROW_CAN_UPDATE)) != 0
            this.matchRank = getInt(getColumnIndex(ROW_MATCH_RANK))
        }
    }

fun Cursor.getRepository(): Repository = getBlob(getColumnIndex(ROW_DATA))
    .jsonParse {
        Repository.deserialize(it).apply {
            this.id = getLong(getColumnIndex(ROW_ID))
        }
    }

fun Cursor.getInstalledItem(): InstalledItem = InstalledItem(
    getString(getColumnIndex(ROW_PACKAGE_NAME)),
    getString(getColumnIndex(ROW_VERSION)),
    getLong(getColumnIndex(ROW_VERSION_CODE)),
    getString(getColumnIndex(ROW_SIGNATURE))
)

fun <T> ByteArray.jsonParse(callback: (JsonParser) -> T): T {
    return Json.factory.createParser(this).use { it.parseDictionary(callback) }
}

fun jsonGenerate(callback: (JsonGenerator) -> Unit): ByteArray {
    val outputStream = ByteArrayOutputStream()
    Json.factory.createGenerator(outputStream).use { it.writeDictionary(callback) }
    return outputStream.toByteArray()
}

val PRODUCT_ASYNC_DIFFER_CONFIG
    get() = AsyncDifferConfig.Builder(object :
        DiffUtil.ItemCallback<com.looker.droidify.database.entity.Product>() {
        override fun areItemsTheSame(
            oldItem: com.looker.droidify.database.entity.Product,
            newItem: com.looker.droidify.database.entity.Product
        ): Boolean {
            return oldItem.repository_id == newItem.repository_id
                    && oldItem.package_name == newItem.package_name
        }

        override fun areContentsTheSame(
            oldItem: com.looker.droidify.database.entity.Product,
            newItem: com.looker.droidify.database.entity.Product
        ): Boolean {
            return oldItem.data_item == newItem.data_item
        }
    }).build()