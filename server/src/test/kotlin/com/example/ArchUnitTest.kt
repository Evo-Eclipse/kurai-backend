package com.example

import com.example.archtest.violation.infra.FakeInfra
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
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..routing..")
            .check(productionClasses)
    }

    @Test
    fun `routing does not depend on infra`() {
        noClasses()
            .that()
            .resideInAPackage("..routing..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infra..")
            .check(productionClasses)
    }

    @Test
    fun `infra does not depend on domain`() {
        noClasses()
            .that()
            .resideInAPackage("..infra..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain..")
            .check(productionClasses)
    }

    @Test
    fun `ArchUnit catches routing-to-infra violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                RoutingViolation::class.java,
                FakeInfra::class.java,
            )
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..routing..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infra..")

        assertFailsWith<AssertionError> { rule.check(violationClasses) }
    }
}
