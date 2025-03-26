package ru.code4a.quarkus.automapper.fieldnamingstrategies

import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy

object RemoveLastIdAndIdsAutoMapFieldNamingStrategy : AutoMapFieldNamingStrategy {
  override fun getObjectFieldName(inputName: String): String {
    return when {
      inputName.endsWith("Id") -> inputName.removeSuffix("Id")
      inputName.endsWith("Ids") -> inputName.removeSuffix("Ids")
      else -> error("Property name $inputName must ends with Ids or Id")
    }
  }
}
