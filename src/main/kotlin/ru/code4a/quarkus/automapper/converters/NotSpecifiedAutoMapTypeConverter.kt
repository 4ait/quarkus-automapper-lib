package ru.code4a.quarkus.automapper.converters

import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter

class NotSpecifiedAutoMapTypeConverter : AutoMapTypeConverter<Any, Any> {
  override fun convert(value: Any): Any {
    error("Not specified AutoMapTypeConverter")
  }
}
