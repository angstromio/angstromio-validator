package angstromio.validation

import jakarta.validation.MessageInterpolator
import java.util.*

class WrongMessageInterpolator : MessageInterpolator {
    override fun interpolate(messageTemplate: String?, context: MessageInterpolator.Context?): String =
        "Whatever you entered was wrong"

    override fun interpolate(messageTemplate: String?, context: MessageInterpolator.Context?, locale: Locale?): String =
        "Whatever you entered was wrong"
}