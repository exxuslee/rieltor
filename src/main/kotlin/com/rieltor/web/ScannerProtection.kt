package com.rieltor.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText

/** Rejects common secret/configuration probes before routing and file resolution. */
internal object ScannerProtection {
    private val sensitiveSegment = Regex(
        pattern = "(^|/)(?:\\.env(?:[./_-]|$)|\\.(?:git|svn|hg|bzr|aws)(?:/|$)|" +
            "wp-config(?:\\.php)?|phpinfo\\.php|config\\.php|credentials(?:\\.|$)|" +
            "secrets?(?:\\.|$)|application\\.(?:ya?ml|properties)|database\\.(?:ya?ml|json|config))",
        option = RegexOption.IGNORE_CASE,
    )
    private val secretFileExtension = Regex(
        pattern = "\\.(?:pem|key|p12|pfx|sql|sqlite|db)(?:\\.(?:bak|old|save|orig|tmp|zip))?$",
        option = RegexOption.IGNORE_CASE,
    )
    private val backupConfig = Regex(
        pattern = "(?:config|settings|credentials|secrets?|\\.env)[^/]*\\.(?:bak|old|save|orig|tmp|swp|swo|zip|txt)$",
        option = RegexOption.IGNORE_CASE,
    )

    fun isProbe(path: String): Boolean {
        val normalized = decodeCommonPathEscapes(path).replace('\\', '/').lowercase()
        return sensitiveSegment.containsMatchIn(normalized) ||
            secretFileExtension.containsMatchIn(normalized) ||
            backupConfig.containsMatchIn(normalized)
    }

    private fun decodeCommonPathEscapes(path: String): String = path
        .replace("%2e", ".", ignoreCase = true)
        .replace("%2f", "/", ignoreCase = true)
        .replace("%5c", "/", ignoreCase = true)
}

internal fun Application.installScannerProtection() {
    intercept(ApplicationCallPipeline.Plugins) {
        if (!ScannerProtection.isProbe(call.request.path())) return@intercept

        call.respondText("Not found", status = HttpStatusCode.NotFound)
        finish()
    }
}
