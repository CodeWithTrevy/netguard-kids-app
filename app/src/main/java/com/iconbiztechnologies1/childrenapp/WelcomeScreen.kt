package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class WelcomeScreen : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_welcome_screen)
        val loginButton = findViewById<Button>(R.id.button)

        loginButton.setOnClickListener{
            // Navigate to HomeActivity
            val intent = Intent(this, DeviceName::class.java)
            startActivity(intent)

        }

    }
}