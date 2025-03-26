package ru.code4a.quarkus.automapper.exceptions

class MissingRequiredFieldInputAutomapperException(
  val fieldName: String
) : RuntimeException()
