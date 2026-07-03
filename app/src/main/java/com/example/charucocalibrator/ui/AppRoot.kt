package com.example.charucocalibrator.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.charucocalibrator.ui.navigation.AppDestination

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    var destination by remember { mutableStateOf(AppDestination.Home) }

    when (destination) {
        AppDestination.Home -> {
            HomeScreen(
                modifier = modifier,
                onOpenCharuco = { destination = AppDestination.CharucoCalibrator }
            )
        }
        AppDestination.CharucoCalibrator -> {
            CharucoCalibratorScreen(
                modifier = modifier,
                onBack = { destination = AppDestination.Home }
            )
        }
    }
}
