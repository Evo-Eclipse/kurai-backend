package com.example.domain.inference

typealias PreprocessPort = (imageBytes: ByteArray) -> FloatArray // 3x224x224 CHW

typealias InferPort = (tensor: FloatArray) -> FloatArray // raw 768-dim output
