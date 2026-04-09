package ru.code4a.quarkus.automapper.utils.reflection.bean

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class KotlinBeanField private constructor(
  val name: String,
  val function: KFunction<*>
) {

  companion object {
    private fun validateDuplicateBeanFields(
      beanKClass: KClass<*>,
      beanFields: List<KotlinBeanField>,
    ) {
      if (beanFields.groupBy { field -> field.name }.any { entry -> entry.value.size > 1 }) {
        error(
          "Detected duplicated fields in bean $beanKClass:\n---\n" +
            beanFields.groupBy { field -> field.name }
              .flatMap { entry -> entry.value.map { field -> field.name } }.joinToString("\n") +
            "---\n"
        )
      }
    }

    private fun getBeanMethodFieldsWithPrefix(
      beanKClass: KClass<*>,
      prefix: String,
      requiredParameterSize: Int
    ): List<KotlinBeanField> {
      return beanKClass.memberFunctions
        .filter { function ->
          function.visibility == KVisibility.PUBLIC &&
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
    }

    fun getBeanGettersFields(beanKClass: KClass<*>): List<KotlinBeanField> {
      val propertyFields =
        beanKClass
          .memberProperties
          .filter { property -> property.visibility == KVisibility.PUBLIC }
          .map { property ->
            KotlinBeanField(
              property.name,
              function = property.getter
            )
          }

      val functionFields =
        getBeanMethodFieldsWithPrefix(beanKClass, "get", requiredParameterSize = 1)

      return (propertyFields + functionFields)
        .also { fields ->
          validateDuplicateBeanFields(beanKClass, fields)
        }
    }

    fun getBeanSettersFields(beanKClass: KClass<*>): List<KotlinBeanField> {
      val propertyFields =
        beanKClass
          .memberProperties
          .filter { property ->
            property.visibility == KVisibility.PUBLIC && property is KMutableProperty<*>
          }
          .map { property ->
            val mutableProperty = property as KMutableProperty<*>
            KotlinBeanField(
              property.name,
              function = mutableProperty.setter
            )
          }

      val functionFields =
        getBeanMethodFieldsWithPrefix(beanKClass, "set", requiredParameterSize = 2)

      return (propertyFields + functionFields)
        .also { fields ->
          validateDuplicateBeanFields(beanKClass, fields)
        }
    }
  }
}
