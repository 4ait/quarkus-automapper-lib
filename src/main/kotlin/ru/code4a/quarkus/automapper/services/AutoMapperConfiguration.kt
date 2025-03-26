package ru.code4a.quarkus.automapper.services

import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError

@Dependent
class AutoMapperConfiguration {

  companion object {
    private val autoMapper: AutoMapper

    init {
      val classLoader = Thread.currentThread().contextClassLoader

      val inputClassesNames =
        classLoader
          .getResource("ru/code4a/quarkus/automapper/automapinputs")
          .unwrapElseError { "Cannot find resource /ru/code4a/quarkus/automapper/automapinputs" }
          .readText()
          .split("\n")

      val mapperAutomapClasses =
        inputClassesNames
          .map { inputClassName ->
            classLoader.loadClass(inputClassName)
          }

      autoMapper = AutoMapMapperBuilder().build(mapperAutomapClasses)
    }
  }

  @Produces
  fun autoMapper(): AutoMapper {
    return autoMapper
  }
}
