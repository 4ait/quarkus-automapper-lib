package ru.code4a.quarkus.automapper.interfaces

interface AutoMapFieldUpdateValidator<PARENT : Any, CURRENT, NEW, INPUT> {
  fun validate(
    parent: PARENT,
    currentValue: CURRENT?,
    newValue: NEW?,
    inputValue: INPUT?,
    fieldName: String,
  )
}
