package com.catsmoker.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.catsmoker.app.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.welcome_title, R.string.welcome_header_subtitle, showBackButton = false)

        // Initially disable the button until agreement is checked
        binding.btnGetStarted.isEnabled = false

        binding.cbAgreement.setOnCheckedChangeListener { _, isChecked ->
            binding.btnGetStarted.isEnabled = isChecked
        }

        binding.btnGetStarted.setOnClickListener {
            // Mark first run as complete
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit {
                putBoolean("is_first_run", false)
            }

            // Navigate to Permissions
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
        }
    }

}
