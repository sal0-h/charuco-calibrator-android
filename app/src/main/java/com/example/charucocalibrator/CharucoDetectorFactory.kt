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
            set_cornerRefinementWinSize(7)
            set_cornerRefinementMaxIterations(40)
            set_cornerRefinementMinAccuracy(0.01)
            set_minMarkerPerimeterRate(0.02)
            set_adaptiveThreshWinSizeMin(3)
            set_adaptiveThreshWinSizeMax(53)
        }
        return CharucoDetector(board, CharucoParameters(), detectorParameters)
    }
}
