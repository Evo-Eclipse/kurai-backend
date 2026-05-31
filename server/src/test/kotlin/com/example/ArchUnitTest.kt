package com.example

import com.example.infrastructure.sqlite.ProfileRepository
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import kotlin.test.Test

/**
 * Codifies the hexagonal layering and bounded-context isolation
 * established in Wave 1 and refined in Wave 6.
 *
 * Layer ladder (outer → inner allowed only):
 *   :server (com.example.{auth,profile,acquisition,ingestion,health} + top-level wiring)
 *     ↓
 *   :application (com.example.application.*)
 *     ↓
 *   :infrastructure (com.example.infrastructure.*)  /  :domain (com.example.domain.*)
 *
 * Each bounded context is a leaf folder; sibling folders may not
 * depend on each other — the wiring layer (Application.kt) is the
 * single composition root.
 */
class ArchUnitTest {
    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.example")

    @Test
    fun `domain depends on nothing else inside com_example`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.example.application..",
                "com.example.infrastructure..",
                "com.example.auth..",
                "com.example.profile..",
                "com.example.acquisition..",
                "com.example.ingestion..",
                "com.example.health..",
            ).check(productionClasses)
    }

    @Test
    fun `infrastructure does not import domain or application types`() {
        noClasses()
            .that()
            .resideInAPackage("..infrastructure..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..domain..", "com.example.application..")
            .check(productionClasses)
    }

    @Test
    fun `application has no dependency on Ktor server`() {
        noClasses()
            .that()
            .resideInAPackage("com.example.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.ktor.server..")
            .check(productionClasses)
    }

    @Test
    fun `server handlers do not reach into infrastructure directly`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "com.example.auth..",
                "com.example.profile..",
                "com.example.acquisition..",
                "com.example.ingestion..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.infrastructure..")
            .check(productionClasses)
    }

    @Test
    fun `bounded contexts do not cross-depend at the server layer`() {
        slices()
            .matching("com.example.(auth|profile|acquisition|ingestion|health)..")
            .should()
            .notDependOnEachOther()
            .check(productionClasses)
    }

    @Test
    fun `only profile workers may advance last_applied_event_id`() {
        // ProfileRepository.upsert is the one method that writes
        // last_applied_event_id; SPEC §I-7 restricts that write to
        // the persistence worker so live request handlers cannot
        // race ahead of the migration log.
        noClasses()
            .that()
            .resideOutsideOfPackage("com.example.application.profile..")
            .should()
            .callMethod(
                ProfileRepository::class.java,
                "upsert",
                checkNotNull(Long::class.javaPrimitiveType),
                String::class.java,
                checkNotNull(Long::class.javaPrimitiveType),
            ).check(productionClasses)
    }
}
