package com.rieltor.tools

import com.rieltor.infrastructure.config.SecretNames
import com.rieltor.infrastructure.config.bootstrapSecrets
import com.rieltor.infrastructure.database.SqliteDatabase
import com.rieltor.infrastructure.database.SqliteRepositories
import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Files
import java.nio.file.Path

/** One-time local migration helper. It never prints secret values. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: ImportLocalSecrets <legacy-autoposter-dir> <target-db>" }
    val source = Path.of(args[0]).toAbsolutePath().normalize()
    val targetDatabase = Path.of(args[1]).toAbsolutePath().normalize()
    require(Files.isDirectory(source)) { "Legacy autoposter directory does not exist: $source" }

    val repository = SqliteRepositories(SqliteDatabase(targetDatabase))
    bootstrapSecrets(repository, Dotenv.configure().ignoreIfMissing().load())

    val mtProtoSource = Files.readString(source.resolve("src/main/kotlin/clients/MtProtoBot.kt"))
    val apiId = extract(mtProtoSource, "apiId")
    val apiHash = extract(mtProtoSource, "apiHash")
    val apiUserId = extract(mtProtoSource, "apiUserId")

    listOfNotNull(
        apiId?.let { SecretNames.TELEGRAM_API_ID to it },
        apiHash?.let { SecretNames.TELEGRAM_API_HASH to it },
        apiUserId?.let { SecretNames.TELEGRAM_USER_ID to it },
    ).forEach { (name, value) -> repository.putIfAbsent(name, value) }

    check(repository.get(SecretNames.TELEGRAM_API_ID) != null && repository.get(SecretNames.TELEGRAM_API_HASH) != null) {
        "Telegram API credentials were not found in the legacy project."
    }
    println("Local secrets were imported into SQLite; values were not printed.")
}

private fun extract(source: String, variableName: String): String? =
    Regex("(?:val|var)\\s+${Regex.escape(variableName)}\\s*:\\s*String\\s*=\\s*\"([^\"]+)\"")
        .find(source)?.groupValues?.get(1)
        ?: Regex("(?:val|var)\\s+${Regex.escape(variableName)}\\s*=\\s*\"([^\"]+)\"")
            .find(source)?.groupValues?.get(1)
