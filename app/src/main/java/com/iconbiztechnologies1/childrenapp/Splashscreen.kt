package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class Splashscreen : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splashscreen)

        // Navigate to Onboarding Screen after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, Log ::class.java)
            startActivity(intent)
            finish() // Close SplashActivity
        }, 3000)

    }
}