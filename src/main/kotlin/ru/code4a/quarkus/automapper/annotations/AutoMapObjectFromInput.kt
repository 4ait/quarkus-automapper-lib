package ru.code4a.quarkus.automapper.annotations

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class AutoMapObjectFromInput(
  val constructMethod: String = "",
  val idField: String = "",
  val objectGetterClass: KClass<*> = Object::class,
  val allowUpdate: Boolean = false,
  val allowCreate: Boolean = true,
)
