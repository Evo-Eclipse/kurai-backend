package com.example.archtest.violation.infrastructure

import com.example.archtest.violation.domain.FakeDomain

// Fixture: sits in ..infrastructure.. and references ..domain.., creating an
// intentional layer violation used by ArchUnitTest to prove the
// "infrastructure ↛ domain" rule is capable of failing.
@Suppress("unused")
class InfrastructureToDomainViolation {
    private val domain = FakeDomain()
}
