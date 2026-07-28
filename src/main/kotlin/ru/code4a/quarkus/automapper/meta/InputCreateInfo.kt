package ru.code4a.quarkus.automapper.meta

import kotlin.reflect.KFunction

internal class InputCreateInfo(
  val constructorObject: Any?,
  val constructMethod: KFunction<*>,
  val createFieldsByName: Map<String, InputCreateFieldInfo>,
  val createFields: List<InputCreateFieldInfo>,
)
