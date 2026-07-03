package com.example.charucocalibrator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.charucocalibrator.ui.AppRoot
import com.example.charucocalibrator.ui.theme.CharucoCalibratorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CharucoCalibratorTheme {
                AppRoot(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
