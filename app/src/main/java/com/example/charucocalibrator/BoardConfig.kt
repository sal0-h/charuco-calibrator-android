package com.example.charucocalibrator

import org.opencv.core.Size
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.Objdetect

object BoardConfig {
    const val SQUARES_X = 7
    const val SQUARES_Y = 10
    const val SQUARE_LENGTH_M = 0.025f
    const val MARKER_LENGTH_M = 0.018f
    const val DICT_NAME = "DICT_5X5_100"

    fun createBoard(): CharucoBoard =
        CharucoBoard(
            Size(SQUARES_X.toDouble(), SQUARES_Y.toDouble()),
            SQUARE_LENGTH_M,
            MARKER_LENGTH_M,
            Objdetect.getPredefinedDictionary(Objdetect.DICT_5X5_100)
        )
}
