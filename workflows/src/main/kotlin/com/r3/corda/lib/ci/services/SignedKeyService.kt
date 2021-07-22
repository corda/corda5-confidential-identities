package com.r3.corda.lib.ci.services

import com.r3.corda.lib.ci.workflows.ChallengeResponse
import com.r3.corda.lib.ci.workflows.SignedKeyForAccount
import net.corda.v5.application.injection.CordaFlowInjectable
import net.corda.v5.application.services.CordaService
import net.corda.v5.base.annotations.CordaInternal
import net.corda.v5.base.annotations.VisibleForTesting
import java.security.PublicKey
import java.util.*

interface SignedKeyService : CordaService, CordaFlowInjectable {

    /**
     * Generates a fresh key pair and stores the mapping to the [UUID]. This key is used construct the [SignedKeyForAccount]
     * containing the new [PublicKey], signed data structure and additional [ChallengeResponse] parameter required for
     * verification by the counter-party.
     *
     * @param challengeResponseParam The random number used to prevent replay attacks
     * @param uuid The external ID to be associated with the new [PublicKey]
     */
    @CordaInternal
    @VisibleForTesting
    fun createSignedOwnershipClaimFromUUID(
        challengeResponseParam: ChallengeResponse,
        uuid: UUID
    ): SignedKeyForAccount

    /**
     * Returns the [SignedKeyForAccount] containing the known [PublicKey], signed data structure and additional
     * [ChallengeResponse] parameter required for verification by the counter-party.
     *
     * @param challengeResponseParam The random number used to prevent replay attacks
     * @param knownKey The [PublicKey] to sign the challengeResponseId
     */
    @CordaInternal
    @VisibleForTesting
    fun createSignedOwnershipClaimFromKnownKey(
        challengeResponseParam: ChallengeResponse,
        knownKey: PublicKey
    ): SignedKeyForAccount

    /**
     * Verifies the signature on the used to sign the [ChallengeResponse].
     */
    @CordaInternal
    @VisibleForTesting
    fun verifySignedChallengeResponseSignature(signedKeyForAccount: SignedKeyForAccount)
}
