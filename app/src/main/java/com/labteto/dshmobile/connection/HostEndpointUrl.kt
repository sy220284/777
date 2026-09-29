package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.authorityOf

/**
 * `host:port` as a URL requires it — an IPv6 literal goes back into brackets.
 * Keep URL construction separate from connect-form parsing so endpoint persistence does not depend
 * on the retired HostInput parser.
 */
internal fun urlAuthority(host: String, port: Int): String = authorityOf(host, port)

/** The scheme-qualified base URL for one harness endpoint. */
internal fun harnessBaseUrl(host: String, port: Int, useTls: Boolean): String =
    (if (useTls) "https://" else "http://") + urlAuthority(host, port)
