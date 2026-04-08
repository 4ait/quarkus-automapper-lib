package ru.code4a.quarkus.automapper.exceptions

class FieldCannotBeNullInputAutomapperException(
  val fieldName: String,
  val className: String? = null
) : RuntimeException() {
  constructor(fieldName: String) : this(fieldName, null)

  override val message: String =
    buildString {
      append("Field cannot be null: ")
      if (className != null) {
        append("className='$className', ")
      }
      append("fieldName='$fieldName'.")
    }
}
