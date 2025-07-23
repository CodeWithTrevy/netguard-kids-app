package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.util.Calendar

class AppBlockedActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AppBlockedActivity"
    }

    // --- Views ---
    private lateinit var titleText: TextView
    private lateinit var detailsText: TextView
    private lateinit var emergencyCallButton: Button
    private lateinit var homeButton: Button

    // --- State ---
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var screenTimeListener: ListenerRegistration? = null
    private var isActivityFinishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked)
        Log.d(TAG, "AppBlockedActivity created for Screen Time.")

        initViews()
        displayBlockMessage()
        setupClickListeners()
        setupBackPressedHandler()
        setupScreenTimeListener()
        scheduleFinishAtMidnight()
    }

    private fun initViews() {
        titleText = findViewById(R.id.blockedMessageText)
        detailsText = findViewById(R.id.resetTimeText)
        emergencyCallButton = findViewById(R.id.emergencyCallButton)
        homeButton = findViewById(R.id.homeButton)

        supportActionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun displayBlockMessage() {
        Log.d(TAG, "Displaying screen time block. Reset is at midnight.")
        titleText.text = "Daily Time Limit Reached"
        detailsText.text = "Access will be restored after midnight."
        detailsText.visibility = View.VISIBLE
    }

    private fun setupClickListeners() {
        emergencyCallButton.setOnClickListener { makeEmergencyCall() }
        homeButton.setOnClickListener { goToHomeScreen() }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back button pressed - redirecting to home.")
                goToHomeScreen()
            }
        })
    }

    private fun setupScreenTimeListener() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Log.e(TAG, "No authenticated user found")
            return
        }

        val deviceId = DeviceIdentityManager.getDeviceID(this)
        if (deviceId.isNullOrEmpty()) {
            Log.e(TAG, "Device ID is empty or null")
            return
        }

        Log.d(TAG, "Setting up real-time screen time listener.")

        val docRef = FirebaseFirestore.getInstance().collection("ScreenTime")
            .document(user.uid)
            .collection("devices")
            .document(deviceId)

        screenTimeListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to screen time updates", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val dailyLimit = snapshot.getLong("screen_time_total_minutes") ?: 0L
                val currentUsage = snapshot.getLong("current_usage_minutes") ?: 0L
                val isLimitExceeded = dailyLimit > 0 && currentUsage >= dailyLimit

                Log.d(TAG, "Screen time update: Usage=$currentUsage, Limit=$dailyLimit, Exceeded=$isLimitExceeded")

                // If the limit is no longer exceeded (e.g., parent increased it), finish.
                if (!isLimitExceeded) {
                    Log.i(TAG, "Screen time limit no longer exceeded. Finishing blocking activity.")
                    finishBlockingActivity()
                }
            } else {
                // If the doc is deleted, assume no more blocking
                Log.w(TAG, "Screen time document no longer found, finishing activity.")
                finishBlockingActivity()
            }
        }
    }

    private fun scheduleFinishAtMidnight() {
        activityScope.launch {
            try {
                val now = Calendar.getInstance()
                val midnight = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 100) // Add a small buffer
                }

                val delayMillis = midnight.timeInMillis - now.timeInMillis
                if (delayMillis > 0) {
                    Log.i(TAG, "Scheduling activity to finish in $delayMillis ms (at midnight).")
                    delay(delayMillis)
                    if (!isActivityFinishing && !isDestroyed) {
                        Log.i(TAG, "Midnight reached. Finishing activity.")
                        finishBlockingActivity()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling finish at midnight", e)
            }
        }
    }

    private fun makeEmergencyCall() {
        try {
            Log.d(TAG, "Emergency call button pressed")
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:") // Open dialer without a number
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening dialer", e)
            Toast.makeText(this, "Could not open dialer.", Toast.LENGTH_SHORT).show()
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
        if (isActivityFinishing) return
        isActivityFinishing = true

        Log.i(TAG, "Finishing blocking activity and notifying service.")
        val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
        intent.setPackage(packageName)
        sendBroadcast(intent)

        activityScope.launch {
            delay(100)
            if (!isDestroyed) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppBlockedActivity destroyed.")
        screenTimeListener?.remove()
        activityScope.cancel() // Cancel all coroutines started by this activity

        if (!isActivityFinishing) {
            val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }
}