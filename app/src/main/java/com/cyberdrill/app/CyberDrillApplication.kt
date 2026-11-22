package com.cyberdrill.app

import android.app.Application
import android.util.Log
import com.cyberdrill.app.ai.HybridAiClient
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App entry point.
 *
 * App Check is mandatory for Firebase AI Logic: every AI request must carry
 * an App Check token. Debug builds install the DEBUG provider — on first run
 * it prints a debug token to Logcat (tag "DebugAppCheckProvider"); register
 * that token in Firebase console > App Check > Apps > Manage debug tokens.
 * Release builds install Play Integrity instead.
 */
class CyberDrillApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            } else {
                appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        } catch (t: Throwable) {
            // With the placeholder google-services.json this can fail; the app
            // then simply runs in seed mode (see HybridAiClient). Catch is
            // Throwable, not Exception: a linkage/static-init Error from the
            // provider stack must never kill the process at startup.
            Log.i("CyberDrillApp", "App Check install skipped: ${t.message}")
        }
        // Give the AI client the application context (used only to read the
        // App Check debug token from the app's own SharedPreferences so it
        // can be shown on screen in debug builds). This does NOT initialize
        // any model: building the Firebase AI models during
        // Application.onCreate killed the process on a real device (bisect:
        // variant A without init opens, variant B with init does not). The
        // client initializes lazily on first AI use — see HybridAiClient.
        try {
            HybridAiClient.attachContext(applicationContext)
        } catch (t: Throwable) {
            Log.i("CyberDrillApp", "AI client context attach skipped: ${t.message}")
        }
    }
}
