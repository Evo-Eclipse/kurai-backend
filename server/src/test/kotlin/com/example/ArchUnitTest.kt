package com.example

import com.example.archtest.violation.domain.DomainToRoutingViolation
import com.example.archtest.violation.domain.FakeDomain
import com.example.archtest.violation.domain.embedding.FakeEmbedding
import com.example.archtest.violation.domain.inference.FakeInference
import com.example.archtest.violation.domain.profile.ProfileToEmbeddingViolation
import com.example.archtest.violation.infrastructure.FakeInfrastructure
import com.example.archtest.violation.infrastructure.InfrastructureToDomainViolation
import com.example.archtest.violation.infrastructure.onnx.FakeOnnx
import com.example.archtest.violation.routing.RoutingViolation
import com.example.archtest.violation.routing.handlers.HandlersToInferenceViolation
import com.example.archtest.violation.routing.handlers.RankingHandlerOnnxViolation
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
    fun `domain profile does not depend on domain embedding`() {
        profileNotOnEmbedding().check(productionClasses)
    }

    @Test
    fun `routing handlers do not depend on domain inference`() {
        handlersNotOnInference().check(productionClasses)
    }

    @Test
    fun `routing handlers do not depend on infrastructure onnx`() {
        handlersNotOnOnnx().check(productionClasses)
    }

    @Test
    fun `ArchUnit catches handlers-to-inference violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                HandlersToInferenceViolation::class.java,
                FakeInference::class.java,
            )
        assertFailsWith<AssertionError> { handlersNotOnInference().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches handlers-to-onnx violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                RankingHandlerOnnxViolation::class.java,
                FakeOnnx::class.java,
            )
        assertFailsWith<AssertionError> { handlersNotOnOnnx().check(violationClasses) }
    }

    @Test
    fun `ArchUnit catches profile-to-embedding violations`() {
        val violationClasses =
            ClassFileImporter().importClasses(
                ProfileToEmbeddingViolation::class.java,
                FakeEmbedding::class.java,
            )
        assertFailsWith<AssertionError> { profileNotOnEmbedding().check(violationClasses) }
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

    private fun infrastructureNotOnDomain() =
        noClasses()
            .that()
            .resideInAPackage("..infrastructure..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain..")

    private fun handlersNotOnInference() =
        noClasses()
            .that()
            .resideInAPackage("..routing.handlers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain.inference..")

    private fun handlersNotOnOnnx() =
        noClasses()
            .that()
            .resideInAPackage("..routing.handlers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure.onnx..")

    private fun profileNotOnEmbedding() =
        noClasses()
            .that()
            .resideInAPackage("..domain.profile..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..domain.embedding..")

    private fun domainNotOnRouting() =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..routing..")
}
