package com.example.archtest.violation.infra

import com.example.archtest.violation.domain.FakeDomain

// Fixture: sits in ..infra.. and references ..domain.., creating an
// intentional layer violation used by ArchUnitTest to prove the
// "infra ↛ domain" rule is capable of failing.
@Suppress("unused")
class InfraToDomainViolation {
    private val domain = FakeDomain()
}
