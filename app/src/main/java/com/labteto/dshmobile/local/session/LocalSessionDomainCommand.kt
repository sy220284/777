package com.labteto.dshmobile.local.session

/**
 * Feature-owned input carried through the shared Session creation boundary.
 *
 * Session infrastructure treats this value as opaque. The composition layer dispatches it to the
 * owning Feature while Session keeps navigation/transaction semantics generic.
 */
internal interface LocalSessionDomainCreateSpec {
    val domainId: String
}

/** Opaque Feature command for changing a Session-local domain mode. */
internal interface LocalSessionDomainModeCommand {
    val domainId: String
}
