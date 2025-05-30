package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class UrlBlockedActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UrlBlockedActivity"
        const val EXTRA_BLOCKED_URL = "blocked_url"
        const val EXTRA_BLOCKED_APP_NAME = "blocked_app_name" // Optional, if you want to show which app
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // If you use this
        setContentView(R.layout.activity_url_blocked)

        val blockedUrl = intent.getStringExtra(EXTRA_BLOCKED_URL) ?: "Unknown URL"
        // val appName = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME) // Optional

        val textViewBlockedUrl: TextView = findViewById(R.id.textViewBlockedUrl)
        val buttonGoHome: Button = findViewById(R.id.buttonGoHome)
        // val textViewBlockedMessage: TextView = findViewById(R.id.textViewBlockedMessage) // If you want to customize the message

        textViewBlockedUrl.text = blockedUrl
        // Example: textViewBlockedMessage.text = "Access to the website in $appName has been restricted."

        Log.d(TAG, "Displaying URL blocked: $blockedUrl")

        buttonGoHome.setOnClickListener {
            goToHomeScreen()
            finishAndNotifyService()
        }
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
    }

    private fun finishAndNotifyService() {
        // Notify the service that this blocking UI is finished
        val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
        intent.setPackage(packageName) // Important for targeted broadcast
        sendBroadcast(intent)
        Log.d(TAG, "Sent BLOCKING_ACTIVITY_FINISHED broadcast")
        finish() // Close this activity
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Override back press to ensure user goes to home and this activity finishes
        super.onBackPressed() // Call super if you want default behavior, or remove to strictly control
        goToHomeScreen()
        finishAndNotifyService()
    }

    override fun onPause() {
        super.onPause()
        // Consider going to home screen if activity is paused by other means
        // This can be aggressive, test thoroughly if you enable it.
        // if (!isFinishing) {
        //     goToHomeScreen()
        //     finishAndNotifyService()
        // }
    }
}