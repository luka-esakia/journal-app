package com.journal.app.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.journal.app.R

/**
 * Screen lock for the journal.
 *
 * Deliberately **not** a home-grown PIN. The platform already owns a vetted credential store, so
 * this delegates to [BiometricPrompt] with `DEVICE_CREDENTIAL` as a fallback: fingerprint or face
 * when enrolled, otherwise the phone's own PIN / pattern / password. Nothing secret is stored by
 * this app, and there is no custom lockout logic to get wrong.
 */
object AppLock {

    /** Why the lock cannot be turned on, or null when it can. */
    enum class Availability {
        AVAILABLE,

        /** No fingerprint, face, PIN, pattern or password set up on the device. */
        NOT_ENROLLED,

        /** No suitable hardware, or the platform refused for another reason. */
        UNSUPPORTED
    }

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            // Below API 30 this combination is rejected by canAuthenticate(); device credential
            // is requested through the deprecated builder flag instead (see [buildPromptInfo]).
            BIOMETRIC_WEAK
        }

    fun availability(context: Context): Availability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(authenticators())) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // On older APIs a device PIN alone still satisfies the prompt even though
                // BIOMETRIC_WEAK reports "none enrolled".
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    manager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                ) {
                    Availability.AVAILABLE
                } else {
                    Availability.NOT_ENROLLED
                }
            }

            else -> Availability.UNSUPPORTED
        }
    }

    fun canLock(context: Context): Boolean = availability(context) == Availability.AVAILABLE

    /**
     * Shows the system authentication sheet.
     *
     * @param onSuccess invoked on the main thread once the user is verified.
     * @param onFailure invoked when authentication is cancelled or errors out; the app stays
     *   locked. A [BiometricPrompt] "failed" attempt (wrong finger) does not call this — the
     *   system sheet handles retries itself.
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (CharSequence?) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString)
                }
            }
        )
        prompt.authenticate(buildPromptInfo(activity))
    }

    private fun buildPromptInfo(context: Context): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_title))
            .setSubtitle(context.getString(R.string.lock_subtitle))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        } else {
            // The only way to offer PIN/pattern/password fallback before API 30. Mutually
            // exclusive with setNegativeButtonText(), which is why it is not set here.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }
}
