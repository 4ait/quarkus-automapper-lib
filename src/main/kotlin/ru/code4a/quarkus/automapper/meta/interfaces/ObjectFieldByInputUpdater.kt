package ru.code4a.quarkus.automapper.meta.interfaces

import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField
import kotlin.reflect.KClass

fun interface ObjectFieldByInputUpdater {
  fun updateField(
    autoMapper: AutoMapper,
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    inputFieldGetter: KotlinBeanField,
    obj: Any,
    inputValue: Any?
  )
}
