package ru.code4a.quarkus.automapper.exceptions

class FieldCannotBeUpdatedInputAutomapperException(
  val fieldName: String
) : RuntimeException()
