package com.example.domain.inference

typealias PreprocessPort = suspend (imageBytes: ByteArray) -> FloatArray // 3x224x224 CHW

typealias InferPort = suspend (tensor: FloatArray) -> FloatArray // raw 768-dim output
