package com.example

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

// The contents of the `install` function will be used for the project template
fun Application.configureDependencyInjection() {
    dependencies {
        provide { GreetingService { "Hello, World!" } }
    }
}
