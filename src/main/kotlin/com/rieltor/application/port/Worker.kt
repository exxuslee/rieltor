package com.rieltor.application.port

/** Background component managed by the application lifecycle. */
interface Worker : AutoCloseable {
    fun start()
    override fun close()
}