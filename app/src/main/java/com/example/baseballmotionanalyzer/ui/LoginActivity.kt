package com.example.baseballmotionanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.baseballmotionanalyzer.MainActivity
import com.example.baseballmotionanalyzer.R
import com.example.baseballmotionanalyzer.db.SwingDatabaseHelper
import com.example.baseballmotionanalyzer.model.PlayerProfile

class LoginActivity : AppCompatActivity() {

    private lateinit var etJerseyNumber: EditText
    private lateinit var etPlayerName: EditText
    private lateinit var etPlayerHeight: EditText
    private lateinit var rgBattingStance: RadioGroup
    private lateinit var rbRightHanded: RadioButton
    private lateinit var rbLeftHanded: RadioButton
    private lateinit var btnLogin: Button
    private lateinit var btnGuestLogin: Button
    private lateinit var containerSavedPlayers: LinearLayout

    private lateinit var dbHelper: SwingDatabaseHelper

    companion object {
        const val EXTRA_PLAYER_PROFILE = "extra_player_profile"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        dbHelper = SwingDatabaseHelper(this)

        etJerseyNumber = findViewById(R.id.etJerseyNumber)
        etPlayerName = findViewById(R.id.etPlayerName)
        etPlayerHeight = findViewById(R.id.etPlayerHeight)
        rgBattingStance = findViewById(R.id.rgBattingStance)
        rbRightHanded = findViewById(R.id.rbRightHanded)
        rbLeftHanded = findViewById(R.id.rbLeftHanded)
        btnLogin = findViewById(R.id.btnLogin)
        btnGuestLogin = findViewById(R.id.btnGuestLogin)
        containerSavedPlayers = findViewById(R.id.containerSavedPlayers)

        btnLogin.setOnClickListener { handleCustomLogin() }
        btnGuestLogin.setOnClickListener { handleGuestLogin() }

        loadSavedPlayersList()
    }

    private fun handleCustomLogin() {
        val jersey = etJerseyNumber.text.toString().trim()
        val name = etPlayerName.text.toString().trim()
        val heightStr = etPlayerHeight.text.toString().trim()
        val height = heightStr.toFloatOrNull() ?: 170f
        val isRight = rbRightHanded.isChecked

        if (jersey.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "請輸入打者背號與姓名！", Toast.LENGTH_SHORT).show()
            return
        }

        val player = PlayerProfile(
            jerseyNumber = jersey,
            name = name,
            heightCm = height,
            isRightHanded = isRight
        )

        dbHelper.saveOrUpdatePlayer(player)
        navigateToMainActivity(player)
    }

    private fun handleGuestLogin() {
        val guest = PlayerProfile.createGuestProfile()
        dbHelper.saveOrUpdatePlayer(guest)
        navigateToMainActivity(guest)
    }

    private fun loadSavedPlayersList() {
        containerSavedPlayers.removeAllViews()
        val players = dbHelper.getAllPlayers()

        if (players.isEmpty()) {
            val tvEmpty = android.widget.TextView(this).apply {
                text = "尚無歷史打者，請上列輸入新增"
                setTextColor(0xFF64748B.toInt())
                textSize = 12f
                setPadding(0, 10, 0, 10)
            }
            containerSavedPlayers.addView(tvEmpty)
            return
        }

        players.take(5).forEach { player ->
            val btn = Button(this).apply {
                text = player.getDisplayName()
                setBackgroundColor(0xFF1E293B.toInt())
                setTextColor(0xFF00E5FF.toInt())
                textSize = 13f
                setPadding(20, 12, 20, 12)
                setOnClickListener {
                    dbHelper.saveOrUpdatePlayer(player)
                    navigateToMainActivity(player)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            containerSavedPlayers.addView(btn, lp)
        }
    }

    private fun navigateToMainActivity(player: PlayerProfile) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_PLAYER_PROFILE, player)
        }
        startActivity(intent)
        finish()
    }
}
