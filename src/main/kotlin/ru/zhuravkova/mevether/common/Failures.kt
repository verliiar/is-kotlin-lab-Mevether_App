package ru.zhuravkova.mevether.common

class BusinessException(val status: Int, val code: String, override val message: String) : RuntimeException(message)

fun invalid(message: String): Nothing = throw BusinessException(422, "INVALID_CHANGE", message)
fun missing(resource: String): Nothing = throw BusinessException(404, "NOT_FOUND", "$resource not found")
fun forbidden(): Nothing = throw BusinessException(403, "FORBIDDEN", "Project access denied")
fun conflict(code: String, message: String): Nothing = throw BusinessException(409, code, message)
