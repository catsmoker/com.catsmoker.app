package com.catsmoker.app;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setTitle("About CatSmoker");

        // Set the about text
        TextView aboutText = findViewById(R.id.about_text);
        String aboutContent = """
                About CatSmoker
                
                CatSmoker is a utility app designed to unlock higher FPS in games.
                It spoofs device models to optimize performance.
                It modifies game files for maximum performance.
                Includes more features to improve gaming.""";
        aboutText.setText(aboutContent);
    }
}