package ru.code4a.quarkus.automapper.meta

import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator

class AutoMapFieldUpdateValidatorInfo(
  val fieldName: String,
  private val validator: AutoMapFieldUpdateValidator<Any, Any, Any, Any>,
) {
  fun validate(
    parent: Any,
    currentValue: Any?,
    newValue: Any?,
    inputValue: Any?,
  ) {
    validator.validate(
      parent = parent,
      currentValue = currentValue,
      newValue = newValue,
      inputValue = inputValue,
      fieldName = fieldName,
    )
  }
}
