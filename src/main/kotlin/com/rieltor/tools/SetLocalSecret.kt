package com.rieltor.tools

import com.rieltor.infrastructure.config.SecretNames
import com.rieltor.infrastructure.database.SqliteDatabase
import com.rieltor.infrastructure.database.SqliteRepositories
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: SetLocalSecret <secret-name> <target-db>" }
    val allowedNames = SecretNames.bootstrapNames.toSet()
    val name = args[0]
    require(name in allowedNames) { "Unsupported secret name: $name" }
    val value = System.getenv("SECRET_VALUE")
        ?.takeIf { it.isNotBlank() }
        ?: error("Set SECRET_VALUE in the process environment.")
    SqliteRepositories(SqliteDatabase(Path.of(args[1]))).put(name, value)
    println("Secret '$name' was updated in SQLite; its value was not printed.")
}
