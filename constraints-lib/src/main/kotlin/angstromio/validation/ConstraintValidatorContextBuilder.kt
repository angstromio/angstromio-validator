package angstromio.validation

import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.ValidationException
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext

internal class ConstraintValidatorContextBuilder {

    companion object {
        private fun builder(): Builder = Builder(
            null, null, emptyMap(), emptyMap()
        )

        fun withMessageTemplate(messageTemplate: String): Builder =
            builder().withMessageTemplate(messageTemplate)

        fun withDynamicPayload(payload: Payload): Builder =
            builder().withDynamicPayload(payload)

        fun addMessageParameter(name: String, value: Any): Builder =
            builder().addMessageParameter(name, value)

        fun addExpressionVariable(name: String, value: Any): Builder =
            builder().addExpressionVariable(name, value)
    }

    internal class Builder(
        val messageTemplate: String?,
        val payload: Payload?,
        val messageParameters: Map<String, Any>,
        val expressionVariables: Map<String, Any>
    ) {
        fun withMessageTemplate(messageTemplate: String): Builder =
            Builder(
                messageTemplate = messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables
            )

        fun withDynamicPayload(payload: Payload): Builder =
            Builder(
                messageTemplate = this.messageTemplate,
                payload = payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables
            )

        fun addMessageParameter(name: String, value: Any): Builder =
            Builder(
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters + (name to value),
                expressionVariables = this.expressionVariables
            )

        fun addExpressionVariable(name: String, value: Any): Builder =
            Builder(
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables + (name to value)
            )

        @Throws(ValidationException::class)
        fun addConstraintViolation(context: ConstraintValidatorContext?) {
            if (context != null) {
                val hibernateContext: HibernateConstraintValidatorContext =
                    context.unwrap(HibernateConstraintValidatorContext::class.java)
                // custom payload
                payload?.let { hibernateContext.withDynamicPayload(it) }
                // set message parameters
                messageParameters.forEach { (name, value) ->
                    hibernateContext.addMessageParameter(name, value)
                }
                // set expression variables
                expressionVariables.forEach { (name, value) ->
                    hibernateContext.addExpressionVariable(name, value)
                }
                // custom message
                messageTemplate?.let { template ->
                    hibernateContext.disableDefaultConstraintViolation()
                    if (expressionVariables.isNotEmpty()) {
                        hibernateContext
                            .buildConstraintViolationWithTemplate(template)
                            .enableExpressionLanguage()
                            .addConstraintViolation()
                    } else {
                        hibernateContext
                            .buildConstraintViolationWithTemplate(template)
                            .addConstraintViolation()
                    }
                }
            }
        }
    }
}