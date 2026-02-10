package com.catsmoker.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        MaterialButton btnGetStarted = findViewById(R.id.btn_get_started);
        CheckBox cbAgreement = findViewById(R.id.cb_agreement);

        // Initially disable the button until agreement is checked
        btnGetStarted.setEnabled(false);

        cbAgreement.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnGetStarted.setEnabled(isChecked);
        });

        btnGetStarted.setOnClickListener(v -> {
            // Mark first run as complete
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            prefs.edit().putBoolean("is_first_run", false).apply();

            // Navigate to Permissions
            startActivity(new Intent(WelcomeActivity.this, PermissionActivity.class));
            finish();
        });
    }
}