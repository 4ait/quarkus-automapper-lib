package ru.code4a.quarkus.automapper.utils.reflection

import kotlin.reflect.KClass

fun KClass<*>.getReadableName(): String {
  return qualifiedName ?: simpleName ?: toString()
}
