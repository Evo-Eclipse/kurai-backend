package com.example.archtest.violation.domain.profile

import com.example.archtest.violation.domain.embedding.FakeEmbedding

// Fixture: imports FakeEmbedding to trigger the profileNotOnEmbedding() rule.
class ProfileToEmbeddingViolation {
    val dep = FakeEmbedding()
}
