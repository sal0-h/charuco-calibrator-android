package com.example.charucocalibrator

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.Objdetect

/**
 * Compile-time probe documenting the OpenCV Android APIs available for ChArUco
 * calibration without a custom JNI/C++ bridge.
 *
 * Verified against `org.opencv:opencv:4.13.0` AAR classes:
 * - `org.opencv.objdetect.Objdetect.getPredefinedDictionary`
 * - `org.opencv.objdetect.CharucoBoard`
 * - `org.opencv.objdetect.CharucoDetector.detectBoard`
 * - `org.opencv.objdetect.Board.matchImagePoints`
 * - `org.opencv.calib3d.Calib3d.calibrateCamera`
 */
internal object CharucoApiFeasibility {
    fun probeApisCompile(): Boolean {
        val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_5X5_100)
        val board = CharucoBoard(
            Size(BoardConfig.SQUARES_X.toDouble(), BoardConfig.SQUARES_Y.toDouble()),
            BoardConfig.SQUARE_LENGTH_M,
            BoardConfig.MARKER_LENGTH_M,
            dictionary
        )
        CharucoDetector(board)
        ArucoDetector(dictionary)
        val objPoints = Mat()
        val imgPoints = Mat()
        board.matchImagePoints(emptyList(), Mat(), objPoints, imgPoints)
        Calib3d.calibrateCamera(
            emptyList(),
            emptyList(),
            Size(1.0, 1.0),
            Mat(),
            Mat(),
            mutableListOf(),
            mutableListOf(),
            Calib3d.CALIB_FIX_K3,
            TermCriteria(TermCriteria.COUNT, 1, 1.0)
        )
        objPoints.release()
        imgPoints.release()
        return true
    }
}
