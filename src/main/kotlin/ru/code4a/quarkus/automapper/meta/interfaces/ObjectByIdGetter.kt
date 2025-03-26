package ru.code4a.quarkus.automapper.meta.interfaces

fun interface ObjectByIdGetter {
  fun getObject(id: Any): Any?
}
