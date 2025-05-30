package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AppBlockedActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AppBlockedActivity"
        private const val SCREEN_TIME_COLLECTION = "ScreenTime"
    }

    private lateinit var blockedMessageText: TextView
    private lateinit var resetTimeText: TextView
    private lateinit var emergencyCallButton: Button
    private lateinit var homeButton: Button

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var screenTimeListener: ListenerRegistration? = null

    // Activity state tracking
    private var isActivityFinishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked)

        Log.d(TAG, "AppBlockedActivity created")

        initViews()
        setupClickListeners()
        setupBackPressedHandler()
        setupScreenTimeListener()

        // Process intent data
        processIntentData()
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

        // Keep screen on to prevent lock screen interference
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set initial message
        blockedMessageText.text = "Screen Time Limit Reached"
        resetTimeText.text = "Loading reset time..."
    }

    private fun processIntentData() {
        val blockedPackage = intent.getStringExtra("blocked_package")
        val blockedAppName = intent.getStringExtra("blocked_app_name")
        val resetTime = intent.getStringExtra("reset_time")

        Log.d(TAG, "Intent data - Package: $blockedPackage, App: $blockedAppName, Reset: $resetTime")

        // Update UI with intent data if available
        if (!blockedAppName.isNullOrEmpty()) {
            blockedMessageText.text = "Access to $blockedAppName is blocked"
        }

        if (!resetTime.isNullOrEmpty()) {
            resetTimeText.text = "Apps will be available again at $resetTime"
        }
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
                Log.d(TAG, "Back button pressed - redirecting to home")
                goToHomeScreen()
            }
        })
    }

    private fun setupScreenTimeListener() {
        val user = auth.currentUser
        if (user == null) {
            Log.e(TAG, "User not authenticated")
            resetTimeText.text = "Reset time not available - Please login"
            return
        }

        Log.d(TAG, "Setting up real-time screen time listener for user: ${user.uid}")

        // Listen to real-time changes in screen time data
        screenTimeListener = firestore.collection(SCREEN_TIME_COLLECTION)
            .whereEqualTo("user_id", user.uid)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to screen time updates", error)
                    resetTimeText.text = "Error loading reset time"
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    val document = snapshots.documents[0]
                    val isBlocked = document.getBoolean("is_blocked") ?: false
                    val resetTime = document.getString("reset_screen_time") ?: ""

                    Log.d(TAG, "Screen time update received - Blocked: $isBlocked, Reset time: $resetTime")

                    if (!isBlocked) {
                        // Screen time blocking has been disabled - finish this activity
                        Log.d(TAG, "Screen time blocking disabled, finishing activity")
                        finishBlockingActivity()
                    } else {
                        // Update UI with latest reset time
                        if (resetTime.isNotEmpty()) {
                            resetTimeText.text = "Apps will be available again at $resetTime"

                            // Check if reset time has already passed
                            checkIfResetTimePassed(resetTime)
                        } else {
                            resetTimeText.text = "Reset time not set"
                        }
                    }
                } else {
                    Log.d(TAG, "No screen time documents found, finishing activity")
                    finishBlockingActivity()
                }
            }
    }

    private fun checkIfResetTimePassed(resetTime: String) {
        activityScope.launch {
            try {
                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                val currentTime = Calendar.getInstance()
                val currentTimeStr = format.format(currentTime.time)
                val resetCalendar = Calendar.getInstance()

                Log.d(TAG, "Checking reset time - Current: $currentTimeStr, Reset: $resetTime")

                // Parse reset time
                val resetDate = format.parse(resetTime) ?: return@launch
                resetCalendar.time = resetDate

                // Set reset time to today
                resetCalendar.set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                resetCalendar.set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                resetCalendar.set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))

                // If reset time has passed, finish activity
                if (currentTime.timeInMillis >= resetCalendar.timeInMillis) {
                    Log.d(TAG, "Reset time has passed, finishing activity")
                    finishBlockingActivity()
                } else {
                    // Calculate remaining time for display
                    val remainingMs = resetCalendar.timeInMillis - currentTime.timeInMillis
                    val remainingMinutes = remainingMs / (1000 * 60)
                    Log.d(TAG, "Time until reset: $remainingMinutes minutes")

                    // Update UI with countdown info
                    val hours = remainingMinutes / 60
                    val minutes = remainingMinutes % 60
                    if (hours > 0) {
                        resetTimeText.text = "Apps will be available in ${hours}h ${minutes}m (at $resetTime)"
                    } else {
                        resetTimeText.text = "Apps will be available in ${minutes}m (at $resetTime)"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking reset time", e)
                resetTimeText.text = "Apps will be available again at $resetTime"
            }
        }
    }

    private fun makeEmergencyCall() {
        try {
            Log.d(TAG, "Emergency call button pressed")
            val emergencyIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:911") // or use local emergency number
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if we have permission to make calls
            if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startActivity(emergencyIntent)
            } else {
                // Fallback to dialer
                val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:911")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialerIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making emergency call", e)
            // Fallback to opening dialer app
            try {
                val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialerIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening dialer", e2)
            }
        }
    }

    private fun goToHomeScreen() {
        try {
            Log.d(TAG, "Going to home screen")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error going to home screen", e)
        }
    }

    private fun finishBlockingActivity() {
        if (isActivityFinishing) {
            Log.d(TAG, "Activity already finishing, skipping")
            return
        }

        isActivityFinishing = true
        Log.d(TAG, "Finishing blocking activity")

        // Notify service that blocking activity is finished
        val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
        intent.setPackage(packageName)
        sendBroadcast(intent)

        // Finish activity
        activityScope.launch {
            delay(100) // Small delay to ensure broadcast is sent
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "AppBlockedActivity resumed")

        // Make sure this activity stays on top
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "AppBlockedActivity paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppBlockedActivity destroyed")

        // Clean up resources
        screenTimeListener?.remove()
        activityScope.cancel()

        // Notify service that activity is finished if not already done
        if (!isActivityFinishing) {
            val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    // Prevent activity from being killed by system
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory called with level: $level")
        // Don't allow system to kill this activity during blocking
    }


    
}