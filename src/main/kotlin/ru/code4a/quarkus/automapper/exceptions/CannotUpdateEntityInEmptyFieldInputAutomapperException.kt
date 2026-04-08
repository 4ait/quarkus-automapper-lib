package ru.code4a.quarkus.automapper.exceptions

class CannotUpdateEntityInEmptyFieldInputAutomapperException(
  val fieldName: String,
  val className: String? = null
) : RuntimeException() {
  constructor(fieldName: String) : this(fieldName, null)

  override val message: String =
    buildString {
      append("Cannot update entity in empty field: ")
      if (className != null) {
        append("className='$className', ")
      }
      append("fieldName='$fieldName'.")
    }
}
