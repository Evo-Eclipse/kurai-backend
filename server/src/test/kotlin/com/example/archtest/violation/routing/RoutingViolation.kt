package com.example.archtest.violation.routing

import com.example.archtest.violation.infra.FakeInfra

// Fixture: sits in ..routing.. and holds a reference to ..infra..,
// creating an intentional layer violation used by ArchUnitTest.
@Suppress("unused")
class RoutingViolation {
    private val infra = FakeInfra()
}
