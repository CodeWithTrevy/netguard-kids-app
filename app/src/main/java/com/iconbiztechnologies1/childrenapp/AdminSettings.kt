package com.iconbiztechnologies1.childrenapp



import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AdminSettings : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_settings)
        val activeButton1 = findViewById<Button>(R.id.button7)
        activeButton1.setOnClickListener {
            val intent = Intent(this, DailyUsageActivity::class.java)
            startActivity(intent)
        }


    }


        }
