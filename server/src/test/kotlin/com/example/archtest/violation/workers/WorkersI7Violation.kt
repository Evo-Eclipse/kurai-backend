package com.example.archtest.violation.workers

import com.example.infrastructure.sqlite.ProfileRepository

// Probe: a class outside com.example.workers that calls ProfileRepository.upsert.
// Used by ArchUnitTest to verify the I-7 rule actually fires.
class WorkersI7Violation(
    private val repo: ProfileRepository,
) {
    fun callUpsert() {
        repo.upsert(1L, "v1", 0L)
    }
}
