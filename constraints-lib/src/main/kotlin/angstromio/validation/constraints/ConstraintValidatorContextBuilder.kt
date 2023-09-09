package angstromio.validation.constraints

import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.ValidationException
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext

internal class ConstraintValidatorContextBuilder {

    companion object {
        private fun builder(): Builder = Builder(
            null, null,null, null, emptyMap(), emptyMap(), false
        )

        fun withPropertyNode(propertyNode: String?): Builder =
            builder().withPropertyNode(propertyNode)

        fun withParameterNode(parameterNode: Int?): Builder =
            builder().withParameterNode(parameterNode)

        fun withMessageTemplate(messageTemplate: String?): Builder =
            builder().withMessageTemplate(messageTemplate)

        fun withDynamicPayload(payload: Payload?): Builder =
            builder().withDynamicPayload(payload)

        fun addMessageParameter(name: String, value: Any): Builder =
            builder().addMessageParameter(name, value)

        fun addExpressionVariable(name: String, value: Any): Builder =
            builder().addExpressionVariable(name, value)

        fun addBeanNode(): Builder =
            builder().addBeanNode()
    }

    internal class Builder(
        private val propertyNode: String?,
        private val parameterNode: Int?,
        private val messageTemplate: String?,
        private val payload: Payload?,
        private val messageParameters: Map<String, Any>,
        private val expressionVariables: Map<String, Any>,
        private val addBeanNode: Boolean
    ) {

        fun withPropertyNode(propertyNode: String?): Builder =
            Builder(
                propertyNode = propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables,
                addBeanNode = this.addBeanNode
            )

        fun withParameterNode(parameterNode: Int?): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = parameterNode,
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables,
                addBeanNode = this.addBeanNode
            )

        fun withMessageTemplate(messageTemplate: String?): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables,
                addBeanNode = this.addBeanNode
            )

        fun withDynamicPayload(payload: Payload?): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = this.messageTemplate,
                payload = payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables,
                addBeanNode = this.addBeanNode
            )

        fun addMessageParameter(name: String, value: Any): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters + (name to value),
                expressionVariables = this.expressionVariables,
                addBeanNode = this.addBeanNode
            )

        fun addExpressionVariable(name: String, value: Any): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables + (name to value),
                addBeanNode = this.addBeanNode
            )

        fun addBeanNode(): Builder =
            Builder(
                propertyNode = this.propertyNode,
                parameterNode = this.parameterNode,
                messageTemplate = this.messageTemplate,
                payload = this.payload,
                messageParameters = this.messageParameters,
                expressionVariables = this.expressionVariables,
                addBeanNode = true
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
                    val constraintViolationBuilder = hibernateContext.buildConstraintViolationWithTemplate(template)
                    if (expressionVariables.isNotEmpty()) {
                        constraintViolationBuilder.enableExpressionLanguage()
                    }
                    if (!propertyNode.isNullOrEmpty()) {
                        constraintViolationBuilder.addPropertyNode(propertyNode)
                    }
                    if (parameterNode != null) {
                        constraintViolationBuilder.addParameterNode(parameterNode)
                    }
                    if (addBeanNode) {
                        constraintViolationBuilder.addBeanNode()
                    }
                    constraintViolationBuilder.addConstraintViolation()
                }
            }
        }
    }
}