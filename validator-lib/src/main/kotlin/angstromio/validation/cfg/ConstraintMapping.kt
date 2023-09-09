package angstromio.validation.cfg

import jakarta.validation.ConstraintValidator

/**
 * Simple configuration class for defining constraint mappings for DataClassValidator configuration.
 * ==Usage==
 *
 *   val customConstraintMapping: ConstraintMapping =
 *     ConstraintMapping(Annotation::class,ConstraintValidator::class)
 *
 *   val validator: DataClassValidator =
 *     DataClassValidator.builder()
 *       .withConstraintMapping(customConstraintMapping)
 *       .validator()
 *
 *
 * or multiple mappings
 *
 *
 *   val customConstraintMapping1: ConstraintMapping = ???
 *   val customConstraintMapping2: ConstraintMapping = ???
 *
 *   val validator: DataClassValidator =
 *     DataClassValidator.builder()
 *       .withConstraintMappings(Set(customConstraintMapping1, customConstraintMapping2))
 *       .validator()
 *
 *
 * @param annotationClazz the `KClass<Annotation>` of the constraint annotation.
 * @param constraintValidator the implementing ConstraintValidator class for the given constraint annotation.
 * @param includeExistingValidators if this is an additional validator for the given constraint annotation type
 *                                  or if this should replace all existing validators for the given constraint annotation
 *                                  type. Default is true (additional).
 * @note adding multiple constraint mappings for the same annotation type will result in a ValidationException being thrown.
 */
data class ConstraintMapping(
    val annotationClazz: Class<out Annotation>,
    val constraintValidator: Class<out ConstraintValidator<out Annotation, *>>,
    val includeExistingValidators: Boolean = true
)