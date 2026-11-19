package com.projectfocus.server.domain

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Test

class EmailChallengeDigestsTest {
    @Test fun sharedHelperPreservesTheExactPreR168RegistrationHmacAndSeparatesResetIdentity() {
        val secret = "own-fixture-hmac-domain-key"
        val subject = "captain@pf.test"
        val generation = "fixed-fixture-generation"
        val code = "012345"

        // of the new enum/domain-selection implementation.
        val legacy = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256"))
            doFinal("projectfocus-register\u0000$subject\u0000$generation\u0000$code".toByteArray(UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
        val digests = EmailChallengeDigests(secret)
        assertThat(digests.digest(EmailChallengePurpose.REGISTER, subject, generation, code)).isEqualTo(legacy)
        assertThat(digests.digest(EmailChallengePurpose.PASSWORD_RESET, subject, generation, code)).isNotEqualTo(legacy)
        assertThat(digests.digest(EmailChallengePurpose.PASSWORD_RESET, "$subject\u0000owner-one", generation, code))
            .isNotEqualTo(digests.digest(EmailChallengePurpose.PASSWORD_RESET, "$subject\u0000owner-two", generation, code))
        assertThat(digests.digest(EmailChallengePurpose.PASSWORD_RESET, subject, "next-generation", code))
            .isNotEqualTo(digests.digest(EmailChallengePurpose.PASSWORD_RESET, subject, generation, code))
    }
}
