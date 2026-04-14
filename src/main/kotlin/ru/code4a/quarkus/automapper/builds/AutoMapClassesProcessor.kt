package ru.code4a.quarkus.automapper.builds

import io.quarkus.deployment.annotations.BuildProducer
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.builditem.CombinedIndexBuildItem
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem
import org.jboss.jandex.DotName
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.annotations.AutoMapTypeConverterDefault

class AutoMapClassesProcessor {
  @BuildStep
  fun produceClassesGraphqlAutoMapFromInput(
    combinedIndex: CombinedIndexBuildItem,
    resourceProducer: BuildProducer<GeneratedResourceBuildItem>,
    reflectiveClassProducer: BuildProducer<ReflectiveClassBuildItem>
  ) {
    val annotationsInstances =
      combinedIndex
        .index
        .getAnnotations(DotName.createSimple(AutoMapObjectFromInput::class.java))

    val classes =
      annotationsInstances.map { annotationInstance ->
        val target = annotationInstance.target()
        val targetClass = target.asClass()

        targetClass.name()
      }
        .toSet()

    resourceProducer.produce(
      GeneratedResourceBuildItem(
        "ru/code4a/quarkus/automapper/automapinputs",
        classes.joinToString("\n").toByteArray()
      )
    )

    classes.forEach { className ->
      reflectiveClassProducer.produce(
        ReflectiveClassBuildItem
          .builder(className.toString())
          .fields()
          .methods()
          .build()
      )
    }
  }

  @BuildStep
  fun produceClassesAutoMapTypeConverters(
    combinedIndex: CombinedIndexBuildItem,
    resourceProducer: BuildProducer<GeneratedResourceBuildItem>
  ) {
    val indexInstances =
      combinedIndex
        .index
        .getAnnotations(DotName.createSimple(AutoMapTypeConverterDefault::class.java))

    val classes =
      indexInstances.map { indexInstance ->
        indexInstance.target().asClass().toString()
      }
        .toSet()

    resourceProducer.produce(
      GeneratedResourceBuildItem(
        "ru/code4a/quarkus/automapper/automaptypeconverters",
        classes.joinToString("\n").toByteArray()
      )
    )
  }
}
