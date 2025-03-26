package ru.code4a.quarkus.automapper.utils.reflection.bean

import kotlin.reflect.KClass

fun KClass<*>.getBeanGettersFields(): List<KotlinBeanField> {
  return KotlinBeanField.getBeanGettersFields(this)
}

fun KClass<*>.getBeanSettersFields(): List<KotlinBeanField> {
  return KotlinBeanField.getBeanSettersFields(this)
}
