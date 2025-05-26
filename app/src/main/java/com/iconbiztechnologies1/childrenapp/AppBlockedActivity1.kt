package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity


class AppBlockedActivity1 : AppCompatActivity() {

    private lateinit var blockedAppIcon: ImageView
    private lateinit var blockedAppName: TextView
    private lateinit var blockingMessage: TextView
    private lateinit var goHomeButton: Button

    private var blockedPackage: String? = null
    private var blockedAppNameStr: String? = null

    private val homeHandler = Handler(Looper.getMainLooper())
    private var homeRunnable: Runnable? = null

    companion object {
        private const val TAG = "AppBlockedActivity"
        private const val AUTO_HOME_DELAY = 3000L // 3 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked1)

        // Get blocked app info from intent
        blockedPackage = intent.getStringExtra("blocked_package")
        blockedAppNameStr = intent.getStringExtra("blocked_app_name") ?: "Unknown App"

        Log.d(TAG, "Blocking app: $blockedAppNameStr ($blockedPackage)")

        initViews()
        setupUI()

        // Prevent user from going back to the blocked app
        setupAutoHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Call super first
        super.onBackPressed()
        // Override back button to go home instead of returning to blocked app
        goHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle new intent when activity is already running
        blockedPackage = intent.getStringExtra("blocked_package")
        blockedAppNameStr = intent.getStringExtra("blocked_app_name") ?: "Unknown App"
        setupUI()
    }

    private fun initViews() {
        blockedAppIcon = findViewById(R.id.ivBlockedAppIcon)
        blockedAppName = findViewById(R.id.tvBlockedAppName)
        blockingMessage = findViewById(R.id.tvBlockingMessage)
        goHomeButton = findViewById(R.id.btnGoHome)

        goHomeButton.setOnClickListener {
            goHome()
        }
    }

    private fun setupUI() {
        // Set app name
        blockedAppName.text = blockedAppNameStr

        // Set blocking message
        blockingMessage.text = "NetGuard Kids has blocked this application.\n\nThis app has been restricted by your parent or guardian."

        // Try to load app icon
        blockedPackage?.let { packageName ->
            try {
                val packageManager = packageManager
                val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                val appIcon = packageManager.getApplicationIcon(applicationInfo)
                blockedAppIcon.setImageDrawable(appIcon)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app icon for $packageName", e)
                // Set default blocked app icon
                blockedAppIcon.setImageResource(android.R.drawable.ic_dialog_alert) // Using system drawable as fallback
            }
        }
    }

    private fun setupAutoHome() {
        // Cancel any existing runnable
        homeRunnable?.let { homeHandler.removeCallbacks(it) }

        // Set up auto-home after delay
        homeRunnable = Runnable {
            Log.d(TAG, "Auto-redirecting to home")
            goHome()
        }

        homeHandler.postDelayed(homeRunnable!!, AUTO_HOME_DELAY)
    }

    private fun goHome() {
        try {
            // Go to home screen
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)

            // Finish this activity
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error going to home screen", e)
            // Fallback: just finish this activity
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        homeRunnable?.let { homeHandler.removeCallbacks(it) }
    }

    override fun onPause() {
        super.onPause()
        // When activity is paused, ensure we go home after a short delay
        // This prevents user from switching back to the blocked app
        homeHandler.postDelayed({
            if (!isFinishing) {
                goHome()
            }
        }, 1000)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "AppBlockedActivity resumed")

        // Reset auto-home timer when activity comes back to foreground
        setupAutoHome()
    }
}