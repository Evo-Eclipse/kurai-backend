package com.example

import com.example.archtest.violation.domain.DomainToRoutingViolation
import com.example.archtest.violation.domain.FakeDomain
import com.example.archtest.violation.infrastructure.FakeInfrastructure
import com.example.archtest.violation.infrastructure.InfrastructureToDomainViolation
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
    fun `routing does not depend on infrastructure`() {
        routingNotOnInfrastructure().check(productionClasses)
    }

    @Test
    fun `infrastructure does not depend on domain`() {
        infrastructureNotOnDomain().check(productionClasses)
    }

    @Test
    fun `ArchUnit catches routing-to-infrastructure violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                RoutingViolation::class.java,
                FakeInfrastructure::class.java,
            )
        assertFailsWith<AssertionError> { routingNotOnInfrastructure().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches infrastructure-to-domain violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                InfrastructureToDomainViolation::class.java,
                FakeDomain::class.java,
            )
        assertFailsWith<AssertionError> { infrastructureNotOnDomain().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches domain-to-routing violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                DomainToRoutingViolation::class.java,
                RoutingViolation::class.java,
                FakeInfrastructure::class.java,
            )
        assertFailsWith<AssertionError> { domainNotOnRouting().check(violationClasses) }
    }

    private fun routingNotOnInfrastructure() =
        noClasses()
            .that()
            .resideInAPackage("..routing..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")

    // The ..domain.. package is empty in production code until later waves
    // (domain-model et al.) populate it. We allow empty `should` to keep the
    // rule active without false-positive failures. Synthetic-violation
    // probes below prove the rule is still capable of failing once classes
    // exist. Drop allowEmptyShould when domain-model lands.
    private fun infrastructureNotOnDomain() =
        noClasses()
            .that()
            .resideInAPackage("..infrastructure..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain..")

    private fun domainNotOnRouting() =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..routing..")
            .allowEmptyShould(true)
}
