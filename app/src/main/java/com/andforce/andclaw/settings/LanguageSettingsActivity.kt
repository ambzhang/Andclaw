package com.andforce.andclaw.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.andforce.andclaw.R
import com.andforce.andclaw.databinding.ActivityLanguageSettingsBinding
import java.util.Locale

class LanguageSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.language_settings)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedLanguage = prefs.getString("language", "system")

        when (savedLanguage) {
            "zh" -> binding.rbChinese.isChecked = true
            "en" -> binding.rbEnglish.isChecked = true
            else -> binding.rbFollowSystem.isChecked = true
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.rbChinese -> "zh"
                R.id.rbEnglish -> "en"
                else -> "system"
            }
            
            prefs.edit().putString("language", language).apply()
            LanguageHelper.setLocale(this, language)
            
            // Restart app to apply changes
            val intent = Intent(this, com.andforce.andclaw.ChatHistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}