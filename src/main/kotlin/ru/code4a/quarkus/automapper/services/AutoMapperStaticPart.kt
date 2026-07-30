package ru.code4a.quarkus.automapper.services

/** Mapper metadata and CDI binding blueprint produced during STATIC_INIT. */
class AutoMapperStaticPart internal constructor(
  internal val autoMapper: AutoMapper,
  internal val runtimeBlueprint: AutoMapperBlueprint,
)
