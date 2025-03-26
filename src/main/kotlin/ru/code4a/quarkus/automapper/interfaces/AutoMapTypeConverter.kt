package ru.code4a.quarkus.automapper.interfaces

interface AutoMapTypeConverter<From, To> {
  fun convert(value: From): To
}
