package ru.code4a.quarkus.automapper.annotations

import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityConflictPolicy
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLocator
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookupOrder
import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class AutoMapObjectFromInput(
  val constructMethod: String = "",
  val idField: String = "",
  val objectGetterClass: KClass<*> = Object::class,
  val allowUpdate: Boolean = false,
  val allowCreate: Boolean = true,
  val existingEntityLookupClasses: Array<KClass<out AutoMapExistingEntityLocator<*, *, *, *, *>>> = [],
  val existingEntityLookupOrder: AutoMapExistingEntityLookupOrder = AutoMapExistingEntityLookupOrder.ID_FIRST,
  val existingEntityConflictPolicy: AutoMapExistingEntityConflictPolicy =
    AutoMapExistingEntityConflictPolicy.FIRST_MATCH,
)
