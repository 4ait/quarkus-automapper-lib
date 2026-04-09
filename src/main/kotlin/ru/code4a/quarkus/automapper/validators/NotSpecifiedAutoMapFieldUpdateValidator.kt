package ru.code4a.quarkus.automapper.validators

import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator

class NotSpecifiedAutoMapFieldUpdateValidator : AutoMapFieldUpdateValidator<Any, Any, Any, Any> {
  override fun validate(
    parent: Any,
    currentValue: Any?,
    newValue: Any?,
    inputValue: Any?,
    fieldName: String,
  ) {
    error("Not specified AutoMapFieldUpdateValidator")
  }
}
