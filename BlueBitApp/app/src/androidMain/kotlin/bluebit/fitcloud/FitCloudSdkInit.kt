package bluebit.fitcloud

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import bluebit.app.BuildConfig
import com.polidea.rxandroidble3.LogConstants
import com.polidea.rxandroidble3.LogOptions
import com.polidea.rxandroidble3.RxBleClient
import com.topstep.fitcloud.sdk.v2.FcSDK
import com.topstep.fitcloud.sdk.v2.features.FcBuiltInFeatures
import com.topstep.wearkit.base.ProcessLifecycleManager
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import timber.log.Timber
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/** Foreground state tracker driven by Activity lifecycle callbacks. */
private class BlueBitProcessLifecycleManager : ProcessLifecycleManager()

private var sdkInstance: FcSDK? = null
private val sdkLock = Any()

fun initFitCloudSdk(application: Application) {
    // 1. Configure Timber (SDK uses Timber internally)
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    } else {
        Timber.plant(object : Timber.DebugTree() {
            override fun isLoggable(tag: String?, priority: Int): Boolean = priority > Log.DEBUG
        })
    }

    // 2. Configure RxAndroidBle logging so we can see raw GATT ops in logcat
    RxBleClient.updateLogOptions(
        LogOptions.Builder()
            .setShouldLogAttributeValues(true)
            .setShouldLogScannedPeripherals(true)
            .setMacAddressLogSetting(LogConstants.MAC_ADDRESS_FULL)
            .setUuidsLogSetting(LogConstants.UUIDS_FULL)
            .setLogLevel(LogConstants.DEBUG)
            .build()
    )

    // 3. Create FcSDK singleton (Builder requires a ProcessLifecycleObserver)
    val lifecycleManager = BlueBitProcessLifecycleManager()
    val instance = synchronized(sdkLock) {
        sdkInstance ?: FcSDK.Builder(
            application,
            lifecycleManager,
        )
            .setTestStrictMode(BuildConfig.DEBUG)
            .setBuiltInFeatures(FcBuiltInFeatures(autoSetLanguage = true, mediaControl = true))
            .build().also { sdkInstance = it }
    }

    // 4. Foreground/background tracking for SDK reconnection strategy
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        var startCount = 0
        override fun onActivityStarted(activity: Activity) {
            if (startCount == 0) lifecycleManager.setForeground(true)
            startCount++
        }
        override fun onActivityStopped(activity: Activity) {
            startCount--
            if (startCount == 0) lifecycleManager.setForeground(false)
        }
        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    })

    // 5. RxJava error handler to avoid crashes from undeliverable exceptions
    val ignores = HashSet<Class<out Throwable>>()
    ignores.addAll(FcSDK.rxJavaPluginsIgnoreExceptions())
    RxJavaPlugins.setErrorHandler { throwable ->
        val cause = if (throwable is UndeliverableException) throwable.cause else throwable
        if (cause is CompositeException) {
            if (cause.exceptions.all { it == null || ignores.any { cls -> cls.isAssignableFrom(it::class.java) } }) return@setErrorHandler
        }
        if (cause != null && ignores.any { it.isAssignableFrom(cause::class.java) }) return@setErrorHandler
        throw RuntimeException(throwable)
    }
}

/** Access the initialized FitCloud SDK singleton. Call [initFitCloudSdk] first. */
val Context.fcSDK: FcSDK by object : ReadOnlyProperty<Context, FcSDK> {
    override fun getValue(thisRef: Context, property: KProperty<*>): FcSDK {
        return sdkInstance ?: error("FitCloud SDK not initialized. Call initFitCloudSdk() first.")
    }
}
