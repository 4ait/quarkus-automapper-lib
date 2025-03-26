package ru.code4a.quarkus.automapper.utils.reflection.bean

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class KotlinBeanField private constructor(
  val name: String,
  val function: KFunction<*>
) {

  companion object {
    private fun getBeanFieldsWithPrefix(
      beanKClass: KClass<*>,
      prefix: String,
      requiredParameterSize: Int
    ): List<KotlinBeanField> {
      val propertiesFunctions =
        beanKClass
          .memberProperties
          .filter { property -> property.visibility == kotlin.reflect.KVisibility.PUBLIC }
          .map { property ->
            KotlinBeanField(
              property.name,
              function = property.getter
            )
          }

      val functions =
        beanKClass.memberFunctions
          .filter { function ->
            function.visibility == kotlin.reflect.KVisibility.PUBLIC &&
                    function.name.length > 3 &&
                    function.name.startsWith(prefix) &&
                    function.name[3].isUpperCase() &&
                    function.parameters.size == requiredParameterSize
          }
          .map { function ->
            KotlinBeanField(
              name = function.name.removePrefix(prefix).replaceFirstChar { it.lowercase() },
              function = function
            )
          }

      val beanFields = propertiesFunctions + functions

      if (beanFields.groupBy { field -> field.name }.any { entry -> entry.value.size > 1 }) {
        error(
          "Detected duplicated fields in bean $beanKClass:\n---\n" +
                  beanFields.groupBy { field -> field.name }
                    .flatMap { entry -> entry.value.map { field -> field.name } }.joinToString("\n") +
                  "---\n"
        )
      }

      return propertiesFunctions + functions
    }

    fun getBeanGettersFields(beanKClass: KClass<*>): List<KotlinBeanField> {
      return getBeanFieldsWithPrefix(beanKClass, "get", requiredParameterSize = 1)
    }

    fun getBeanSettersFields(beanKClass: KClass<*>): List<KotlinBeanField> {
      return getBeanFieldsWithPrefix(beanKClass, "set", requiredParameterSize = 2)
    }
  }
}
