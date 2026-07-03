package com.example.charucocalibrator

import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.CharucoParameters
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

object CharucoDetectorFactory {
    fun create(board: CharucoBoard): CharucoDetector {
        val detectorParameters = DetectorParameters().apply {
            set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
            set_cornerRefinementWinSize(5)
            set_cornerRefinementMaxIterations(30)
            set_cornerRefinementMinAccuracy(0.01)
        }
        return CharucoDetector(board, CharucoParameters(), detectorParameters)
    }
}
