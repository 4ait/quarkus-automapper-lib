package ru.code4a.quarkus.automapper.exceptions

class MissingRequiredFieldInputAutomapperException(
  val fieldName: String,
  val className: String? = null
) : RuntimeException() {
  constructor(fieldName: String) : this(fieldName, null)

  override val message: String =
    buildString {
      append("Missing required field: ")
      if (className != null) {
        append("className='$className', ")
      }
      append("fieldName='$fieldName'.")
    }
}
