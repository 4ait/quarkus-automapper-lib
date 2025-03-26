package ru.code4a.quarkus.automapper.utils.cast

import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseThrow

inline fun <reified T> Any?.castNullableElseThrow(exceptionGetter: () -> Throwable): T {
  val notNullValue = unwrapElseThrow(exceptionGetter)

  if (notNullValue is T) {
    return notNullValue as T
  }

  throw exceptionGetter()
}

inline fun <reified T> Any.castElseThrow(exceptionGetter: () -> Throwable): T {
  if (this is T) {
    return this as T
  }

  throw exceptionGetter()
}

inline fun <reified T> Any.castElseError(messageGetter: () -> String): T {
  if (this is T) {
    return this as T
  }

  error(messageGetter())
}

inline fun <reified T> Any?.castNullable(): T {
  return castNullableElseThrow {
    val thisClassName =
      if (this == null) {
        "null"
      } else {
        this::class.toString()
      }

    RuntimeException("Cannot cast from $thisClassName to ${T::class}")
  }
}

inline fun <reified T> Any?.castNullableElseError(errorMessageGetter: () -> String): T {
  return castNullableElseThrow {
    error(errorMessageGetter())
  }
}
