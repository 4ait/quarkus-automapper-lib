package ru.code4a.quarkus.automapper.annotations

import ru.code4a.quarkus.automapper.converters.NotSpecifiedAutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.fieldnamingstrategies.AutoMapFieldNamingStrategyDefault
import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class AutoMapField(
  val fieldName: String = "",
  val setterFieldName: String = "",
  val constructParameterName: String = "",
  val getterFieldName: String = "",
  val namingStrategy: KClass<out AutoMapFieldNamingStrategy> = AutoMapFieldNamingStrategyDefault::class,
  val typeConverter: KClass<out AutoMapTypeConverter<*, *>> = NotSpecifiedAutoMapTypeConverter::class,
  val mapper: KClass<*> = Object::class
)
