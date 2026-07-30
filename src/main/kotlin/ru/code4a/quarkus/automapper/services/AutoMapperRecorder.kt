package ru.code4a.quarkus.automapper.services

import io.quarkus.arc.SyntheticCreationalContext
import io.quarkus.runtime.RuntimeValue
import io.quarkus.runtime.annotations.Recorder
import jakarta.enterprise.inject.literal.NamedLiteral
import java.util.function.Function
import kotlin.reflect.KClass

@Recorder
open class AutoMapperRecorder {

  open fun createStaticPart(
    staticMapperClassNames: Array<String>,
    runtimeMapperClassNames: Array<String>,
    defaultConverterClassNames: Array<String> = emptyArray(),
    singleLocatorClassNames: Array<String>,
    batchLocatorClassNames: Array<String>,
  ): RuntimeValue<AutoMapperStaticPart> {
    val classLoader = Thread.currentThread().contextClassLoader
    val builder =
      AutoMapMapperBuilder(
        cdiLookup = { null },
        prevalidatedLocatorKinds =
          locatorKinds(classLoader, singleLocatorClassNames, batchLocatorClassNames),
        defaultConverterClassNames = defaultConverterClassNames.toList(),
      )
    val autoMapper = builder.build(staticMapperClassNames.map(classLoader::loadClass))
    val runtimeBlueprint =
      builder.buildBlueprint(runtimeMapperClassNames.map(classLoader::loadClass))

    return RuntimeValue(AutoMapperStaticPart(autoMapper, runtimeBlueprint))
  }

  open fun createRuntimeMapperBeanCreator(
    mapperClassName: String,
  ): Function<SyntheticCreationalContext<AutoMapInputClassInfoProvider>, AutoMapInputClassInfoProvider> {
    return Function { context ->
      val staticPart = context.getInjectedReference(AutoMapperStaticPart::class.java)
      val mapperClass = staticPart.runtimeBlueprint.mapperClass(mapperClassName)
      val cdiComponents =
        staticPart.runtimeBlueprint.componentClasses(mapperClass).associateWith { componentClass ->
          @Suppress("UNCHECKED_CAST")
          context.getInjectedReference(componentClass.java as Class<Any>)
        }
      val inputClassInfo =
        staticPart.runtimeBlueprint.bindMapper(mapperClass) { componentClass: KClass<*> ->
          cdiComponents[componentClass]
        }

      fixedInputClassInfoProvider(inputClassInfo)
    }
  }

  open fun createRuntimeAutoMapperBeanCreator(
    runtimeMapperClassNames: Array<String>,
  ): Function<SyntheticCreationalContext<AutoMapper>, AutoMapper> {
    return Function { context ->
      val staticPart = context.getInjectedReference(AutoMapperStaticPart::class.java)
      val runtimeProviders =
        runtimeMapperClassNames.associate { mapperClassName ->
          val provider =
            context.getInjectedReference(
              AutoMapInputClassInfoProvider::class.java,
              NamedLiteral.of(mapperClassName),
            )
          staticPart.runtimeBlueprint.mapperClass(mapperClassName) to provider
        }
      mergeParts(staticPart.autoMapper, runtimeProviders)
    }
  }

  private fun mergeParts(
    staticPart: AutoMapper,
    runtimeProviders: Map<Class<*>, AutoMapInputClassInfoProvider>,
  ): AutoMapper {
    val duplicateMapperClasses =
      staticPart.inputClassInfoProvidersByMapperSpecClass.keys intersect runtimeProviders.keys
    check(duplicateMapperClasses.isEmpty()) {
      "Mapper metadata was initialized in both STATIC_INIT and RUNTIME_INIT: $duplicateMapperClasses"
    }

    return AutoMapper(
      staticPart.inputClassInfoProvidersByMapperSpecClass + runtimeProviders
    )
  }

  private fun locatorKinds(
    classLoader: ClassLoader,
    singleLocatorClassNames: Array<String>,
    batchLocatorClassNames: Array<String>,
  ): Map<KClass<*>, AutoMapExistingEntityLocatorKind> {
    return buildMap {
      singleLocatorClassNames.forEach { className ->
        put(classLoader.loadClass(className).kotlin, AutoMapExistingEntityLocatorKind.SINGLE)
      }
      batchLocatorClassNames.forEach { className ->
        put(classLoader.loadClass(className).kotlin, AutoMapExistingEntityLocatorKind.BATCH)
      }
    }
  }
}
