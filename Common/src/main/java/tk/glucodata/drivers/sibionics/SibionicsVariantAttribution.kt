package tk.glucodata.drivers.sibionics

/**
 * Decides what a successful V120 authentication is allowed to say about a sensor's identity.
 *
 * The sensor answers an auth packet with a bare ACK that carries nothing identifying the key
 * that unlocked it. Attribution is therefore positional: whatever candidate was in flight when
 * the ACK arrived gets the credit. That is only sound while a single auth packet has ever been
 * written on this connection. After an auth timeout the driver moves on to the next key group,
 * so a late ACK belonging to the previous key is credited to the new candidate — which used to
 * silently rewrite the variant the user picked during setup, along with the lifetime, the
 * auto-reset window and the maintenance reset packet that hang off it.
 *
 * So the two questions are kept apart:
 *  - *which key to try first* is a cheap hint, updated on every accepted auth;
 *  - *what this sensor is* is identity, rewritten only on unambiguous evidence — an ACK for the
 *    first and only auth packet of a connection.
 *
 * A genuinely mis-declared sensor still self-corrects: the ambiguous win moves the hint, the next
 * connection leads with that key, and its first-attempt ACK is unambiguous. The cost of being
 * wrong is one extra auth timeout on one connection, not a permanently mislabelled sensor.
 */
internal object SibionicsVariantAttribution {
    /** Fallback order used once the hint and the recorded variant have had their turn. */
    private val DEFAULT_ORDER = listOf(
        SibionicsConstants.Variant.EU,
        SibionicsConstants.Variant.HEMATONIX,
        SibionicsConstants.Variant.SIBIONICS2,
        SibionicsConstants.Variant.GS3,
    )

    data class Attribution(
        /** Whether [SibionicsRegistry.confirmAuthenticatedVariant] may write this variant. */
        val persistVariant: Boolean,
        /** Key group to lead with on the next connection. */
        val keyHint: SibionicsConstants.Variant,
    )

    /**
     * Key groups to try, in order, for one connection. Distinct by the material that actually
     * decides authentication, so variants sharing a key are never tried twice.
     */
    fun keyOrder(
        recordedVariant: SibionicsConstants.Variant,
        keyHint: SibionicsConstants.Variant?,
    ): List<SibionicsConstants.Variant> =
        (listOfNotNull(keyHint, recordedVariant) + DEFAULT_ORDER)
            .distinctBy { it.appId + it.registrationKeyHex }

    /**
     * @param attemptIndex index of the auth packet that this ACK is being credited to, within the
     *   current connection. Only index 0 can have been the sole packet in flight.
     */
    fun attribute(
        recordedVariant: SibionicsConstants.Variant,
        acceptedVariant: SibionicsConstants.Variant,
        attemptIndex: Int,
    ): Attribution =
        Attribution(
            persistVariant = acceptedVariant == recordedVariant || attemptIndex <= 0,
            keyHint = acceptedVariant,
        )
}
