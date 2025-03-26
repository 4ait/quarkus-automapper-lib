package ru.code4a.quarkus.automapper.exceptions

class CannotUpdateEntityInEmptyFieldInputAutomapperException(
  val fieldName: String
) : RuntimeException()
