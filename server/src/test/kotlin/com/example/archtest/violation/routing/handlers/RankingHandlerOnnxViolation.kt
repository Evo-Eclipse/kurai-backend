package com.example.archtest.violation.routing.handlers

import com.example.archtest.violation.infrastructure.onnx.FakeOnnx

// Fixture: imports FakeOnnx to trigger the handlersNotOnOnnx() rule.
class RankingHandlerOnnxViolation {
    val dep = FakeOnnx()
}
