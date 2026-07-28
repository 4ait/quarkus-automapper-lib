package ru.code4a.quarkus.automapper.meta.interfaces

import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.services.AutoMapMappingFrame
import kotlin.reflect.KClass

internal fun interface ObjectByInputUpdater {
  fun updateObj(
    autoMapper: AutoMapper,
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    mappingContext: AutoMapMappingFrame,
    obj: Any,
    input: Any
  )
}
