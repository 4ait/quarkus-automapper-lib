package ru.code4a.quarkus.automapper.builds

import io.quarkus.builder.item.SimpleBuildItem

class AutoMapperInitializationPlanBuildItem(
  val staticMapperClassNames: Array<String>,
  val runtimeMapperClassNames: Array<String>,
  val applicationScopedComponentClassNamesByMapper: Map<String, Array<String>>,
  val defaultConverterClassNames: Array<String>,
  val singleLocatorClassNames: Array<String>,
  val batchLocatorClassNames: Array<String>,
) : SimpleBuildItem()
