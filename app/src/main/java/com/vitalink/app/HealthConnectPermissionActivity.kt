package com.vitalink.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * Opens the official Health Connect permission confirmation page.
 *
 * This is separated from Compose because some Android 13 / vivo devices
 * do not show the permission UI reliably when launched from composition.
 */
class HealthConnectPermissionActivity : ComponentActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    // Only request the smart-band permissions needed by the dashboard sync.
    // This avoids Honor/Health Connect refusing the whole flow because of
    // unrelated BP/weight/sleep permissions.
    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            finish()
        }

        // Launch after Activity is fully created.
        window.decorView.post {
            try {
                if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
                    permissionLauncher.launch(permissions)
                } else {
                    openHealthConnectSettings()
                    finish()
                }
            } catch (_: Throwable) {
                openHealthConnectSettings()
                finish()
            }
        }
    }

    private fun openHealthConnectSettings() {
        val intents = listOfNotNull(
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata")
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // Try next fallback.
            }
        }
    }
}
