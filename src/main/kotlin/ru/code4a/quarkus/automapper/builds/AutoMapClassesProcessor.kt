package ru.code4a.quarkus.automapper.builds

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import jakarta.inject.Singleton
import io.quarkus.arc.deployment.UnremovableBeanBuildItem
import io.quarkus.arc.deployment.SyntheticBeanBuildItem
import io.quarkus.deployment.annotations.BuildProducer
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.annotations.ExecutionTime
import io.quarkus.deployment.annotations.Record
import io.quarkus.deployment.builditem.CombinedIndexBuildItem
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem
import org.jboss.jandex.DotName
import org.jboss.jandex.AnnotationInstance
import org.jboss.jandex.Type
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapTypeConverterDefault
import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import ru.code4a.quarkus.automapper.services.AutoMapperRecorder
import ru.code4a.quarkus.automapper.services.AutoMapperStaticPart
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLocatorKind
import ru.code4a.quarkus.automapper.services.AutoMapInputClassInfoProvider

class AutoMapClassesProcessor {
  private val javaObjectName = DotName.createSimple(Any::class.java)
  private val applicationScopedName = DotName.createSimple(ApplicationScoped::class.java)

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

    val existingEntityLookupClasses =
      annotationsInstances
        .flatMap { annotationInstance ->
          annotationInstance
            .value("existingEntityLookupClasses")
            ?.asClassArray()
            ?.toList()
            .orEmpty()
        }
        .toSet()

    existingEntityLookupClasses.forEach { className ->
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

  @BuildStep
  fun registerUserComponents(
    combinedIndex: CombinedIndexBuildItem,
    reflectiveClassProducer: BuildProducer<ReflectiveClassBuildItem>,
    unremovableBeanProducer: BuildProducer<UnremovableBeanBuildItem>,
  ) {
    val componentClassNames = getUserComponentClassNames(combinedIndex)

    componentClassNames.forEach { className ->
      reflectiveClassProducer.produce(
        ReflectiveClassBuildItem
          .builder(className.toString())
          .fields()
          .methods()
          .build()
      )
    }

    if (componentClassNames.isNotEmpty()) {
      unremovableBeanProducer.produce(
        UnremovableBeanBuildItem.beanClassNames(
          componentClassNames.mapTo(linkedSetOf(), DotName::toString)
        )
      )
    }
  }

  @BuildStep
  fun createAutoMapperInitializationPlan(
    combinedIndex: CombinedIndexBuildItem,
  ): AutoMapperInitializationPlanBuildItem {
    val index = combinedIndex.index
    val locatorKinds = AutoMapExistingEntityLocatorBuildTimeValidator.validate(index)
    val allMapperClassNames =
      index
        .getAnnotations(DotName.createSimple(AutoMapObjectFromInput::class.java))
        .map { annotation -> annotation.target().asClass().name() }
        .distinct()
        .sorted()
    val applicationScopedComponentClassNames =
      getUserComponentClassNames(combinedIndex)
        .filterTo(linkedSetOf()) { className ->
          index.getClassByName(className)?.hasDeclaredAnnotation(applicationScopedName) == true
        }
    val defaultConverterClassNames =
      index
        .getAnnotations(DotName.createSimple(AutoMapTypeConverterDefault::class.java))
        .map { annotation -> annotation.target().asClass().name().toString() }
        .distinct()
        .sorted()
    val classLoader = Thread.currentThread().contextClassLoader
    val mapperClassesByName =
      allMapperClassNames.associateWith { mapperClassName ->
        classLoader.loadClass(mapperClassName.toString())
      }
    val prevalidatedLocatorKinds =
      locatorKinds.mapKeys { (locatorClassName, _) ->
        classLoader.loadClass(locatorClassName.toString()).kotlin
      }
    val dependencyBlueprint =
      AutoMapMapperBuilder(
        cdiLookup = { null },
        prevalidatedLocatorKinds = prevalidatedLocatorKinds,
        defaultConverterClassNames = defaultConverterClassNames,
      ).buildBlueprint(mapperClassesByName.values.toList())
    val applicationScopedComponentNames =
      applicationScopedComponentClassNames.mapTo(linkedSetOf(), DotName::toString)
    val applicationScopedComponentClassNamesByMapper =
      mapperClassesByName.mapValues { (_, mapperClass) ->
        dependencyBlueprint
          .componentClasses(mapperClass)
          .map { componentClass -> componentClass.java.name }
          .filter(applicationScopedComponentNames::contains)
          .sorted()
          .toTypedArray()
      }.mapKeys { (mapperClassName, _) -> mapperClassName.toString() }
    val runtimeMapperClassNames =
      allMapperClassNames.filter { mapperClassName ->
        applicationScopedComponentClassNamesByMapper.getValue(mapperClassName.toString()).isNotEmpty()
      }.toSet()
    val staticMapperClassNames = allMapperClassNames.filterNot(runtimeMapperClassNames::contains)
    val singleLocatorClassNames =
      locatorKinds
        .filterValues { kind -> kind == AutoMapExistingEntityLocatorKind.SINGLE }
        .keys
        .map(DotName::toString)
        .toTypedArray()
    val batchLocatorClassNames =
      locatorKinds
        .filterValues { kind -> kind == AutoMapExistingEntityLocatorKind.BATCH }
        .keys
        .map(DotName::toString)
        .toTypedArray()

    return AutoMapperInitializationPlanBuildItem(
      staticMapperClassNames = staticMapperClassNames.map(DotName::toString).toTypedArray(),
      runtimeMapperClassNames = runtimeMapperClassNames.map(DotName::toString).toTypedArray(),
      applicationScopedComponentClassNamesByMapper = applicationScopedComponentClassNamesByMapper,
      defaultConverterClassNames = defaultConverterClassNames.toTypedArray(),
      singleLocatorClassNames = singleLocatorClassNames,
      batchLocatorClassNames = batchLocatorClassNames,
    )
  }

  @BuildStep
  @Record(ExecutionTime.STATIC_INIT)
  fun produceStaticAutoMapperBean(
    plan: AutoMapperInitializationPlanBuildItem,
    recorder: AutoMapperRecorder,
  ): SyntheticBeanBuildItem {
    val staticPart =
      recorder.createStaticPart(
        staticMapperClassNames = plan.staticMapperClassNames,
        runtimeMapperClassNames = plan.runtimeMapperClassNames,
        defaultConverterClassNames = plan.defaultConverterClassNames,
        singleLocatorClassNames = plan.singleLocatorClassNames,
        batchLocatorClassNames = plan.batchLocatorClassNames,
      )

    return SyntheticBeanBuildItem
      .configure(AutoMapperStaticPart::class.java)
      .scope(Singleton::class.java)
      .unremovable()
      .runtimeValue(staticPart)
      .done()
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  fun produceRuntimeMapperBeans(
    plan: AutoMapperInitializationPlanBuildItem,
    recorder: AutoMapperRecorder,
  ): List<SyntheticBeanBuildItem> {
    val staticPartType = Type.create(AutoMapperStaticPart::class.java)
    return plan.runtimeMapperClassNames.map { mapperClassName ->
      val qualifier = mapperQualifier(mapperClassName)
      val runtimeBean =
        SyntheticBeanBuildItem
          .configure(AutoMapInputClassInfoProvider::class.java)
          .scope(ApplicationScoped::class.java)
          .addQualifier(qualifier)
          .setRuntimeInit()
          .createWith(recorder.createRuntimeMapperBeanCreator(mapperClassName))

      runtimeBean.addInjectionPoint(staticPartType)
      plan.applicationScopedComponentClassNamesByMapper
        .getValue(mapperClassName)
        .forEach { componentClassName ->
          runtimeBean.addInjectionPoint(
            Type.create(DotName.createSimple(componentClassName), Type.Kind.CLASS)
          )
        }
      runtimeBean.done()
    }
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  fun produceRuntimeAutoMapperBean(
    plan: AutoMapperInitializationPlanBuildItem,
    recorder: AutoMapperRecorder,
  ): SyntheticBeanBuildItem {
    val runtimeBean =
      SyntheticBeanBuildItem
        .configure(AutoMapper::class.java)
        .scope(Singleton::class.java)
        .setRuntimeInit()
        .createWith(
          recorder.createRuntimeAutoMapperBeanCreator(
            runtimeMapperClassNames = plan.runtimeMapperClassNames,
          )
        )

    runtimeBean.addInjectionPoint(Type.create(AutoMapperStaticPart::class.java))
    plan.runtimeMapperClassNames.forEach { mapperClassName ->
      runtimeBean.addInjectionPoint(
        Type.create(AutoMapInputClassInfoProvider::class.java),
        mapperQualifier(mapperClassName),
      )
    }

    return runtimeBean.done()
  }

  private fun mapperQualifier(mapperClassName: String): AnnotationInstance {
    return AnnotationInstance.builder(Named::class.java).value(mapperClassName).build()
  }

  private fun getUserComponentClassNames(
    combinedIndex: CombinedIndexBuildItem,
  ): LinkedHashSet<DotName> {
    val index = combinedIndex.index
    val componentClassNames = linkedSetOf<DotName>()

    index
      .getAnnotations(DotName.createSimple(AutoMapObjectFromInput::class.java))
      .forEach { annotation ->
        annotation.value("objectGetterClass")?.asClass()?.name()?.let(componentClassNames::add)
        annotation
          .value("existingEntityLookupClasses")
          ?.asClassArray()
          ?.map { type -> type.name() }
          ?.let(componentClassNames::addAll)
      }

    index
      .getAnnotations(DotName.createSimple(AutoMapField::class.java))
      .forEach { annotation ->
        listOf("namingStrategy", "typeConverter", "updateValidatorClass")
          .mapNotNull { name -> annotation.value(name)?.asClass()?.name() }
          .forEach(componentClassNames::add)
      }

    index
      .getAnnotations(DotName.createSimple(AutoMapTypeConverterDefault::class.java))
      .map { annotation -> annotation.target().asClass().name() }
      .forEach(componentClassNames::add)

    componentClassNames.remove(javaObjectName)
    return componentClassNames
  }
}
