package net.extrawdw.apps.locationhistory

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds attest via Play Integrity, which verifies a genuine, unmodified install from Play
 * on a real device -- the assurance that lets Google authorize the Routes web-service call.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory =
    PlayIntegrityAppCheckProviderFactory.getInstance()
