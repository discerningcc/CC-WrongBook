package com.example.demo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String KEY_NATURAL_DAY = "natural_day_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch switchNaturalDay = findViewById(R.id.switch_natural_day);
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);

        boolean enabled = sp.getBoolean(KEY_NATURAL_DAY, true);
        switchNaturalDay.setChecked(enabled);
        updateSwitchColor(switchNaturalDay, enabled);

        // 这里修复了 👇
        switchNaturalDay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(KEY_NATURAL_DAY, isChecked).apply();
            updateSwitchColor(switchNaturalDay, isChecked);
        });
    }

    private void updateSwitchColor(Switch switchView, boolean isChecked) {
        if (isChecked) {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track));
        } else {
            switchView.setThumbTintList(getColorStateList(R.color.switch_thumb_disabled));
            switchView.setTrackTintList(getColorStateList(R.color.switch_track_disabled));
        }
    }
}