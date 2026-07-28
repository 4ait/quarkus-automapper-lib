package ru.code4a.quarkus.automapper.meta.interfaces

import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.services.AutoMapMappingFrame
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField
import kotlin.reflect.KClass

internal fun interface ObjectFieldByInputUpdater {
  fun updateField(
    autoMapper: AutoMapper,
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    mappingContext: AutoMapMappingFrame,
    inputFieldGetter: KotlinBeanField,
    obj: Any,
    inputValue: Any?
  )
}
