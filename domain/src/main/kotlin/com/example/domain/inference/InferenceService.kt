package com.example.domain.inference

import com.example.domain.profile.Scoring

class InferenceService(
    private val preprocess: PreprocessPort,
    private val infer: InferPort,
) {
    fun embed(imageBytes: ByteArray): FloatArray = Scoring.l2Normalize(infer(preprocess(imageBytes)))
}
