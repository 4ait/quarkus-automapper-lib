package ru.code4a.quarkus.automapper.meta.interfaces

import ru.code4a.quarkus.automapper.services.AutoMapper
import kotlin.reflect.KClass

fun interface ObjectByInputUpdater {
  fun updateObj(
    autoMapper: AutoMapper,
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    obj: Any,
    input: Any
  )
}
