package ru.code4a.quarkus.automapper.meta

import ru.code4a.quarkus.automapper.meta.interfaces.ObjectFieldByInputUpdater
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField

class ObjectFieldByInput(
  val inputGetterField: KotlinBeanField,
  val updater: ObjectFieldByInputUpdater
)
