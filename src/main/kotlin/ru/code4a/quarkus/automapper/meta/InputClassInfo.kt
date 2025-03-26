package ru.code4a.quarkus.automapper.meta

import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByIdGetter
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByInputUpdater
import kotlin.reflect.KClass

class InputClassInfo(
  val inputKClass: KClass<*>,
  val objectKClass: KClass<*>,
  val objectByInputUpdater: ObjectByInputUpdater?,
  val autoMapObjectFromInputAnnotation: AutoMapObjectFromInput,
  val idGetterField: KotlinBeanField?,
  val inputCreateInfo: InputCreateInfo?,
  val objectByIdGetter: ObjectByIdGetter?
)
