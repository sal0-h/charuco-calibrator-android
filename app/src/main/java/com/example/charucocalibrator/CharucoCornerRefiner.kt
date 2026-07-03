package com.example.charucocalibrator

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc

object CharucoCornerRefiner {
    fun refine(gray: Mat, corners: Mat) {
        if (corners.empty() || corners.rows() == 0) return
        Imgproc.cornerSubPix(
            gray,
            corners,
            Size(11.0, 11.0),
            Size(-1.0, -1.0),
            TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 40, 0.001)
        )
    }
}
