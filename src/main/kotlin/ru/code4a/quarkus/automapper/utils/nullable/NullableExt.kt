package ru.code4a.quarkus.automapper.utils.nullable

inline fun <T> T?.unwrapElseThrow(exceptionGetter: () -> Throwable): T {
  if (this == null) {
    throw exceptionGetter()
  }

  return this
}

inline fun <T> T?.unwrapElseError(messageGetter: () -> String): T {
  if (this == null) {
    error(messageGetter())
  }

  return this
}

fun <T> T?.getElseThrowRuntimeException(exceptionMessage: String): T {
  if (this == null) {
    throw RuntimeException(exceptionMessage)
  }

  return this
}
