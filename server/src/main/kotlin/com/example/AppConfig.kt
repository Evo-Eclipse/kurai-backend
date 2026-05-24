package com.example

data class AppConfig(
    val jwtSecret: String,
) {
    companion object {
        fun load(env: Map<String, String> = System.getenv()): AppConfig {
            val secret =
                env["KURAI_JWT_SECRET"]
                    ?: error("Missing required environment variable: KURAI_JWT_SECRET")
            return AppConfig(jwtSecret = secret)
        }
    }
}
