package com.example.archtest.violation.routing

import com.example.archtest.violation.infrastructure.FakeInfrastructure

// Fixture: sits in ..routing.. and holds a reference to ..infrastructure..,
// creating an intentional layer violation used by ArchUnitTest.
@Suppress("unused")
class RoutingViolation {
    private val infrastructure = FakeInfrastructure()
}
