package ru.code4a.quarkus.automapper.meta

import ru.code4a.quarkus.automapper.services.AutoMapConverterChainBuilder
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField

class InputCreateFieldInfo(
  val constructParameterName: String,
  val inputFieldGetter: KotlinBeanField,
  val converter: AutoMapConverterChainBuilder.AutoMapDynConverter,
)
