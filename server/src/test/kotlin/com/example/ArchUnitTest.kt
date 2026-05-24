package com.example

import com.example.archtest.violation.domain.DomainToRoutingViolation
import com.example.archtest.violation.domain.FakeDomain
import com.example.archtest.violation.infra.FakeInfra
import com.example.archtest.violation.infra.InfraToDomainViolation
import com.example.archtest.violation.routing.RoutingViolation
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArchUnitTest {
    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.example")

    @Test
    fun `domain does not depend on routing`() {
        domainNotOnRouting().check(productionClasses)
    }

    @Test
    fun `routing does not depend on infra`() {
        routingNotOnInfra().check(productionClasses)
    }

    @Test
    fun `infra does not depend on domain`() {
        infraNotOnDomain().check(productionClasses)
    }

    @Test
    fun `ArchUnit catches routing-to-infra violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                RoutingViolation::class.java,
                FakeInfra::class.java,
            )
        assertFailsWith<AssertionError> { routingNotOnInfra().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches infra-to-domain violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                InfraToDomainViolation::class.java,
                FakeDomain::class.java,
            )
        assertFailsWith<AssertionError> { infraNotOnDomain().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches domain-to-routing violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                DomainToRoutingViolation::class.java,
                RoutingViolation::class.java,
                FakeInfra::class.java,
            )
        assertFailsWith<AssertionError> { domainNotOnRouting().check(violationClasses) }
    }

    private fun routingNotOnInfra() =
        noClasses()
            .that()
            .resideInAPackage("..routing..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infra..")

    // The ..infra.. and ..domain.. packages are empty in production code
    // until later waves (infra-sqlite, domain-model) populate them. We allow
    // empty `should` to keep the rules active without false-positive failures.
    // Synthetic-violation probes below prove the rules are still capable of
    // failing once classes exist. Drop allowEmptyShould when the packages
    // gain their first production classes.
    private fun infraNotOnDomain() =
        noClasses()
            .that()
            .resideInAPackage("..infra..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain..")
            .allowEmptyShould(true)

    private fun domainNotOnRouting() =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..routing..")
            .allowEmptyShould(true)
}
