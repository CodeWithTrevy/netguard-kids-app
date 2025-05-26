package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AppBlockedActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AppBlockedActivity"
    }

    private lateinit var blockedMessageText: TextView
    private lateinit var resetTimeText: TextView
    private lateinit var emergencyCallButton: Button
    private lateinit var homeButton: Button

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked)

        initViews()
        setupClickListeners()
        setupBackPressedHandler()
        loadResetTime()
    }

    private fun initViews() {
        blockedMessageText = findViewById(R.id.blockedMessageText)
        resetTimeText = findViewById(R.id.resetTimeText)
        emergencyCallButton = findViewById(R.id.emergencyCallButton)
        homeButton = findViewById(R.id.homeButton)

        // Remove action bar
        supportActionBar?.hide()

        // Make activity full screen and non-dismissible
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
    }

    private fun setupClickListeners() {
        emergencyCallButton.setOnClickListener {
            makeEmergencyCall()
        }

        homeButton.setOnClickListener {
            goToHomeScreen()
        }
    }

    private fun setupBackPressedHandler() {
        // Handle back button press using the new OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Prevent back button from dismissing the blocking screen
                goToHomeScreen()
            }
        })
    }

    private fun loadResetTime() {
        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        val user = auth.currentUser

        if (user == null) {
            resetTimeText.text = "Reset time not available"
            return
        }

        activityScope.launch {
            try {
                val resetTime = withContext(Dispatchers.IO) {
                    fetchResetTime(db, user.uid)
                }

                if (resetTime.isNotEmpty()) {
                    resetTimeText.text = "Apps will be available again at $resetTime"
                } else {
                    resetTimeText.text = "Reset time not set"
                }
            } catch (e: Exception) {
                resetTimeText.text = "Error loading reset time"
            }
        }
    }

    private suspend fun fetchResetTime(db: FirebaseFirestore, userId: String): String {
        return suspendCancellableCoroutine { continuation ->
            db.collection("ScreenTime")
                .whereEqualTo("user_id", userId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener { documents ->
                    if (!documents.isEmpty) {
                        val resetTime = documents.documents[0].getString("reset_screen_time") ?: ""
                        continuation.resume(resetTime) { }
                    } else {
                        continuation.resume("") { }
                    }
                }
                .addOnFailureListener {
                    continuation.resume("") { }
                }
        }
    }

    private fun makeEmergencyCall() {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:911") // You can change this to local emergency number
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            // If direct call fails, open dialer
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:911")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                // Last resort - open phone app
                val intent = packageManager.getLaunchIntentForPackage("com.android.dialer")
                    ?: packageManager.getLaunchIntentForPackage("com.android.phone")
                    ?: packageManager.getLaunchIntentForPackage("com.samsung.android.dialer")

                intent?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
            }
        }
    }

    private fun goToHomeScreen() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}