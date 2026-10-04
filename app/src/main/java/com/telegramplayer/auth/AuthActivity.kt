package com.telegramplayer.auth

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.telegramplayer.R
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.browse.MainActivity
import com.telegramplayer.data.AuthState
import com.telegramplayer.streaming.StreamingProxyService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Authentication Activity for Telegram login.
 * Supports QR code login (primary) and phone number login (fallback).
 * TV-friendly with D-pad navigation.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var qrCodeImageView: ImageView
    private lateinit var scanInstructionText: TextView
    private lateinit var phoneInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var phoneLoginButton: Button
    private lateinit var submitCodeButton: Button
    private lateinit var submitPasswordButton: Button
    private lateinit var loadingProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var phoneLoginContainer: LinearLayout
    private lateinit var codeInputContainer: LinearLayout
    private lateinit var passwordInputContainer: LinearLayout

    private val tdClient get() = TelegramPlayerApp.getTdClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize TDLib BEFORE inflating layout so it can start reading
        // auth state from disk while the UI inflates in parallel.
        // For returning users, TDLib reaches Authorized almost instantly
        // and the observer navigates to MainActivity before auth UI renders.
        // TDLib is now initialized globally in TelegramPlayerApp.kt

        setContentView(R.layout.activity_auth)
        initViews()

        if (tdClient.apiId == 0 || tdClient.apiHash.isEmpty()) {
            showCredentialsError()
            return
        }

        setupListeners()
        observeAuthState()
    }

    private fun initViews() {
        qrCodeImageView = findViewById(R.id.qr_code_image)
        scanInstructionText = findViewById(R.id.scan_instruction)
        phoneInput = findViewById(R.id.phone_input)
        codeInput = findViewById(R.id.code_input)
        passwordInput = findViewById(R.id.password_input)
        phoneLoginButton = findViewById(R.id.phone_login_button)
        submitCodeButton = findViewById(R.id.submit_code_button)
        submitPasswordButton = findViewById(R.id.submit_password_button)
        loadingProgress = findViewById(R.id.loading_progress)
        statusText = findViewById(R.id.status_text)
        phoneLoginContainer = findViewById(R.id.phone_login_container)
        codeInputContainer = findViewById(R.id.code_input_container)
        passwordInputContainer = findViewById(R.id.password_input_container)
    }

    private fun setupListeners() {
        phoneLoginButton.setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            if (phone.isNotEmpty()) {
                tdClient.sendPhoneNumber(phone)
                showLoading(true)
            }
        }

        submitCodeButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.isNotEmpty()) {
                tdClient.sendCode(code)
                showLoading(true)
            }
        }

        submitPasswordButton.setOnClickListener {
            val password = passwordInput.text.toString()
            if (password.isNotEmpty()) {
                tdClient.sendPassword(password)
                showLoading(true)
            }
        }
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            tdClient.authState.collect { state ->
                runOnUiThread {
                    handleAuthState(state)
                }
            }
        }

        lifecycleScope.launch {
            tdClient.qrCodeLink.collect { link ->
                if (link != null) {
                    runOnUiThread {
                        displayQrCode(link)
                    }
                }
            }
        }
    }

    private fun handleAuthState(state: AuthState) {
        showLoading(false)
        Log.d("AuthActivity", "Handling AuthState: $state")
        
        when (state) {
            is AuthState.WaitingForPhoneNumber -> {
                statusText.text = "Ready to login"
                showPhoneLogin()
                Log.d("AuthActivity", "Requesting QR code auth")
                tdClient.requestQrCodeAuth()
            }
            is AuthState.WaitingForQrScan -> {
                statusText.text = getString(R.string.scan_qr_code)
                showQrCode()
            }
            is AuthState.WaitingForCode -> {
                statusText.text = "Enter verification code"
                showCodeInput()
            }
            is AuthState.WaitingForPassword -> {
                statusText.text = "Enter 2FA password"
                showPasswordInput()
            }
            is AuthState.Authorized -> {
                statusText.text = "Login successful!"
                startStreamingService()
                navigateToMain()
            }
            else -> {
                 Log.d("AuthActivity", "Unhandled state or initial state: $state")
            }
        }
    }

    private fun showQrCode() {
        qrCodeImageView.visibility = View.VISIBLE
        scanInstructionText.visibility = View.VISIBLE
        phoneLoginContainer.visibility = View.VISIBLE
        codeInputContainer.visibility = View.GONE
        passwordInputContainer.visibility = View.GONE
    }

    private fun showPhoneLogin() {
        phoneLoginContainer.visibility = View.VISIBLE
        codeInputContainer.visibility = View.GONE
        passwordInputContainer.visibility = View.GONE
        phoneInput.requestFocus()
    }

    private fun showCodeInput() {
        qrCodeImageView.visibility = View.GONE
        scanInstructionText.visibility = View.GONE
        phoneLoginContainer.visibility = View.GONE
        codeInputContainer.visibility = View.VISIBLE
        passwordInputContainer.visibility = View.GONE
        codeInput.requestFocus()
    }

    private fun showPasswordInput() {
        qrCodeImageView.visibility = View.GONE
        scanInstructionText.visibility = View.GONE
        phoneLoginContainer.visibility = View.GONE
        codeInputContainer.visibility = View.GONE
        passwordInputContainer.visibility = View.VISIBLE
        passwordInput.requestFocus()
    }

    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun displayQrCode(link: String) {
        Log.d("AuthActivity", "Displaying QR Code for link: $link")
        try {
            val size = 400
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(link, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

            val foregroundColor = ContextCompat.getColor(this, R.color.qr_foreground)
            val backgroundColor = ContextCompat.getColor(this, R.color.qr_background)

            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) foregroundColor else backgroundColor)
                }
            }

            qrCodeImageView.setImageBitmap(bitmap)
            qrCodeImageView.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCredentialsError() {
        statusText.text = "ERROR: Telegram API credentials not configured.\nPlease copy local.properties.example to local.properties and set your telegram.api_id and telegram.api_hash."
        statusText.setTextColor(Color.RED)
        loadingProgress.visibility = View.GONE
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingProxyService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
