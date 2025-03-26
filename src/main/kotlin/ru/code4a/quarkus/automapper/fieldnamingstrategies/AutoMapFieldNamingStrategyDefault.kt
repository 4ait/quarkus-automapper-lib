package ru.code4a.quarkus.automapper.fieldnamingstrategies

import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy

object AutoMapFieldNamingStrategyDefault : AutoMapFieldNamingStrategy {
  override fun getObjectFieldName(inputName: String): String {
    return inputName
  }
}
