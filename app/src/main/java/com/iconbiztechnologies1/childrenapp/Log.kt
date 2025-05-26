package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth


class Log : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)


        // Initialize FirebaseAuth
        auth = FirebaseAuth.getInstance()

        // Get UI elements
        val emailAddress = findViewById<EditText>(R.id.emailAddress)
        val password = findViewById<EditText>(R.id.password)
        val loginButton = findViewById<Button>(R.id.loginButton1)

        // Handle Login Button Click
        loginButton.setOnClickListener {
            val email = emailAddress.text.toString().trim()
            val pass = password.text.toString().trim()

            if (email.isNotEmpty() && pass.isNotEmpty()) {
                signIn(email, pass)
            } else {
                Toast.makeText(this, "Email and Password required!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {

                    Toast.makeText(this, "Login Successful", Toast.LENGTH_SHORT).show()

                    // Navigate to WelcomeScreen after login
                    val intent = Intent(this, WelcomeScreen::class.java) // Ensure WelcomeScreen exists
                    startActivity(intent)
                    finish() // Close the Login screen
                } else {
                    Toast.makeText(this, "Authentication Failed", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
