package ru.code4a.quarkus.automapper.exceptions

class FieldIsNotSupportedForCreateInputAutomapperException(
  val fieldName: String
) : RuntimeException()
