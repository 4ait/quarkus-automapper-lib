# Quarkus AutoMapper

A powerful, declarative object mapping library for Quarkus applications that simplifies conversion between data transfer
objects (DTOs) and domain entities. Reduces boilerplate code and provides a clean annotation-based approach for handling
object conversions in GraphQL APIs, REST services, and other mapping scenarios.

[![Maven Central](https://img.shields.io/maven-central/v/ru.code4a/quarkus-automapper)](https://central.sonatype.com/artifact/ru.code4a/quarkus-automapper)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Features

- **Declarative Mapping**: Define input-to-entity mappings with annotations
- **Bidirectional Support**: Map from inputs to entities and back
- **Type Conversion**: Automatic type conversion with customizable converters
- **Field Validation Hooks**: Typed field-level validators invoked automatically during mapping
- **Field Name Strategies**: Flexible field naming conventions
- **GraphQL Integration**: Seamless integration with Quarkus' GraphQL implementation
- **CDI Support**: Fully compatible with Quarkus dependency injection
- **Build-time Processing**: Utilizes Quarkus' build-time processing for enhanced performance

## Installation

Add the dependency to your project's `build.gradle.kts`:

```kotlin
implementation("ru.code4a:quarkus-automapper:${version}")
```

Or if you're using Maven:

```xml

<dependency>
  <groupId>ru.code4a</groupId>
  <artifactId>quarkus-automapper</artifactId>
  <version>${version}</version>
</dependency>
```

## Quick Start

### 1. Define your entity

```kotlin
@Entity
class CameraDomainEntity(
  @Id
  val id: UUID,
  var name: String,
  var location: String,
  @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
  var streams: MutableList<StreamDomainEntity> = mutableListOf()
) {
  companion object {
    fun create(name: String, location: String, streams: List<StreamDomainEntity>): CameraDomainEntity {
      return CameraDomainEntity(
        id = UUID.randomUUID(),
        name = name,
        location = location,
        streams = streams.toMutableList()
      )
    }
  }
}
```

### 2. Create Input Classes

```kotlin
@Input("NewCameraInput")
@AutoMapObjectFromInput(
  constructMethod = "create"
)
class NewCameraInput(
  var name: String,
  var location: String,
  var streams: List<NewStreamInput>
) : AutoMapperSpecTo<CameraDomainEntity>

@Input("NewStreamInput")
@AutoMapObjectFromInput(
  constructMethod = "build"
)
class NewStreamInput(
  var rtspUrl: String
) : AutoMapperSpecTo<StreamDomainEntity>
```

### 3. Inject and Use the AutoMapper

```kotlin
@Inject
lateinit var autoMapper: AutoMapper

@TransactionalMutation
fun createCamera(newCameraInput: NewCameraInput): CreateCameraResult {
  val camera = autoMapper.createOrUpdateObjectByInput(
    allowedCreationObjectClasses = setOf(
      CameraDomainEntity::class,
      StreamDomainEntity::class
    ),
    input = newCameraInput
  )

  return CreateCameraResult(
    createdCamera = cameraGQLObjectBuilder.get(camera)
  )
}
```

## Core Concepts

### AutoMap Annotations

#### @AutoMapObjectFromInput

This annotation marks a class as an input mapper for creating or updating entities.

```kotlin
@AutoMapObjectFromInput(
  constructMethod = "create",           // Method to call on the companion object to create a new instance
  idField = "id",                       // Field that contains the entity ID (for updates)
  objectGetterClass = MyGetter::class,  // Class for retrieving existing entities
  allowUpdate = true,                   // Whether updates are allowed
  allowCreate = true                    // Whether creation is allowed
)
```

#### @AutoMapField

This annotation provides fine-grained control over field mapping.

```kotlin
@AutoMapField(
  fieldName = "entityFieldName",                 // Target field name in the entity
  setterFieldName = "entitySetterName",          // Setter method name in the entity
  constructParameterName = "constructParamName", // Parameter name in the constructor
  getterFieldName = "entityGetterName",          // Getter method name in the entity
  namingStrategy = CustomNamingStrategy::class,  // Custom naming strategy
  typeConverter = CustomTypeConverter::class,    // Custom type converter
  updateValidatorClass = CustomFieldUpdateValidator::class, // Field-level update validator
  mapper = CustomMapper::class                   // Custom mapper for nested objects
)
```

### Field Update Validators

Use `updateValidatorClass` to validate a mapped field during `UPDATE`, after conversion/create/update of the field
value but before assignment to the parent object.

```kotlin
object SubjectPhonesOwnershipValidator :
  AutoMapFieldUpdateValidator<
    SubjectDomainEntity,
    Set<SubjectPhoneDomainEntity>,
    Set<SubjectPhoneDomainEntity>,
    List<SubjectPhoneInput>
  > {

  override fun validate(
    parent: SubjectDomainEntity,
    currentValue: Set<SubjectPhoneDomainEntity>,
    newValue: Set<SubjectPhoneDomainEntity>,
    inputValue: List<SubjectPhoneInput>,
    fieldName: String,
  ) {
    val newPhones = newValue.orEmpty()

    require(newPhones.all { it.subjectId == parent.id }) {
      "Phone list contains a phone from another subject"
    }
  }
}

class UpdateSubjectInput(
  @get:AutoMapField(
    fieldName = "phones",
    updateValidatorClass = SubjectPhonesOwnershipValidator::class
  )
  var phones: List<SubjectPhoneInput>
) : AutoMapperSpecTo<SubjectDomainEntity>
```

Notes:
- Update validator generics are validated during mapper initialization. Incompatible `parent/current/new/input` types
  fail fast on application startup with a readable error.
- Update validators are invoked only for `UPDATE`.
- Update validators are not supported for in-place nested updates on fields without a setter. Such configuration fails
  fast on startup because the mapper cannot provide a stable `currentValue/newValue` boundary there.

### Mapping Interfaces

Implement one of these interfaces in your input classes to specify the target entity type:

- `AutoMapperSpecTo<T>`: For mapping input directly to type T
- `AutoMapperSpec<From, To>`: For mapping From type to To type

### Type Converters

Create custom type converters by implementing the `AutoMapTypeConverter` interface and annotating with
`@AutoMapTypeConverterDefault`:

```kotlin
@AutoMapTypeConverterDefault
class StringToPhoneNumberAutoMapTypeConverter : AutoMapTypeConverter<String, PhoneNumber> {
  override fun convert(value: String): PhoneNumber {
    // Conversion logic
  }
}
```

### Field Naming Strategies

Implement `AutoMapFieldNamingStrategy` to customize field name mapping:

```kotlin
object RemoveLastIdAndIdsAutoMapFieldNamingStrategy : AutoMapFieldNamingStrategy {
  override fun getObjectFieldName(inputName: String): String {
    return when {
      inputName.endsWith("Id") -> inputName.removeSuffix("Id")
      inputName.endsWith("Ids") -> inputName.removeSuffix("Ids")
      else -> error("Property name $inputName must ends with Ids or Id")
    }
  }
}
```

## Advanced Usage

### Entity Updates

To update an existing entity:

```kotlin
@Input("UpdateCameraInput")
@AutoMapObjectFromInput(
  idField = "id",
  allowUpdate = true,
  allowCreate = false
)
class UpdateCameraInput(
  @set:Id
  var id: String,
  var name: String,
  var location: String,
  var streams: List<UpdateStreamInput>
) : AutoMapperSpecTo<CameraDomainEntity>

// In your endpoint:
val camera = graphqlRelayNodeManager.getTypedEntityByNodeId<CameraDomainEntity>(updateCameraInput.id)
autoMapper.updateObjectByInput(
  allowedUpdateObjectClasses = setOf(CameraDomainEntity::class, StreamDomainEntity::class),
  allowedCreationObjectClasses = setOf(StreamDomainEntity::class),
  input = updateCameraInput,
  obj = camera
)
```

### Nested Objects and Collections

AutoMapper handles nested objects and collections automatically:

```kotlin
// Parent input with nested collection
class ParentInput(
  var name: String,
  var children: List<ChildInput>
) : AutoMapperSpecTo<ParentEntity>

// Child input
@AutoMapObjectFromInput(constructMethod = "create")
class ChildInput(
  var name: String
) : AutoMapperSpecTo<ChildEntity>
```

### Custom Entity Getters

For retrieving entities during update operations, implement a custom entity getter:

```kotlin
@RegisterForReflection
object AutoMapInputEntityGetterByNodeId {
  @DatabaseReadOperation
  fun get(
    entityClass: KClass<*>,
    id: String
  ): Any {
    val graphqlRelayNodeManager = ArcService.get<GraphqlRelayNodeManager>()
    return graphqlRelayNodeManager.getEntityByNodeId(id)
      ?: throw RuntimeException("$entityClass doesn't exist with id $id")
  }
}
```

Then reference it in your input class:

```kotlin
@AutoMapObjectFromInput(
  idField = "id",
  objectGetterClass = AutoMapInputEntityGetterByNodeId::class,
  allowUpdate = true
)
class UpdateStreamInput(/*...*/)
```

## Exception Handling

AutoMapper provides specific exceptions for common errors:

- `FieldCannotBeNullInputAutomapperException`: When a non-nullable field receives a null value
- `FieldCannotBeUpdatedInputAutomapperException`: When a field cannot be updated
- `CannotUpdateEntityInEmptyFieldInputAutomapperException`: When trying to update a null entity
- `MissingRequiredFieldInputAutomapperException`: When a required field is missing
- `FieldIsNotSupportedForCreateInputAutomapperException`: When a field is not supported for creation

## Customization

### Security Constraints

Use the `allowedCreationObjectClasses` and `allowedUpdateObjectClasses` parameters to enforce security constraints:

```kotlin
autoMapper.createOrUpdateObjectByInput(
  allowedCreationObjectClasses = setOf(EntityA::class, EntityB::class),
  allowedUpdateObjectClasses = setOf(EntityA::class),
  input = myInput
)
```

### Extension Functions

Use the provided extension functions for cleaner code:

```kotlin
// For AutoMapperSpecTo inputs
inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.createOrUpdateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T
): TO

// For updating existing objects
inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.updateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T,
  obj: TO
)
```

## Best Practices

1. **Define clear class hierarchies**: Keep your entity and input classes well-structured
2. **Use specific annotations**: Be explicit with field mappings for complex scenarios
3. **Handle exceptions**: Implement proper exception handling for mapping failures
4. **Follow naming conventions**: Use consistent naming patterns for fields
5. **Restrict allowed classes**: Always specify which entity classes can be created or updated
6. **Validate inputs**: Perform validation before mapping to ensure data integrity
7. **Use transactions**: Wrap mapping operations in transactions for data consistency

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

Apache 2.0
