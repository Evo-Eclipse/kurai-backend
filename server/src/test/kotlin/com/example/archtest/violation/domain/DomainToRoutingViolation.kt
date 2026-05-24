package com.example.archtest.violation.domain

import com.example.archtest.violation.routing.RoutingViolation

// Fixture: sits in ..domain.. and references ..routing.., creating an
// intentional layer violation used by ArchUnitTest to prove the
// "domain ↛ routing" rule is capable of failing.
@Suppress("unused")
class DomainToRoutingViolation {
    private val routing = RoutingViolation()
}
