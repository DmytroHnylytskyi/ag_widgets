package dev.agwidgets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing dialog is driven by a deep link that any app or web page can send.
 * Only a `VIEW` intent for exactly `agw://pair` may be treated as a request;
 * everything else (launcher intents, another scheme or host, missing data) must
 * be ignored — and the checked-in URI must be consumed once so a rotation does
 * not resurrect the dialog.
 */
class PairingLinkTest {

    private val view = "android.intent.action.VIEW"

    @Test
    fun acceptsThePairingLink() {
        assertTrue(isPairingLink(view, "agw", "pair"))
    }

    @Test
    fun acceptsMixedCaseSchemeAndHost() {
        assertTrue(isPairingLink(view, "AGW", "Pair"))
    }

    @Test
    fun rejectsNonViewIntents() {
        assertFalse(isPairingLink("android.intent.action.MAIN", "agw", "pair"))
        assertFalse(isPairingLink(null, "agw", "pair"))
    }

    @Test
    fun rejectsOtherSchemesAndHosts() {
        assertFalse(isPairingLink(view, "agw2", "pair"))
        assertFalse(isPairingLink(view, "agw", "pair2"))
        assertFalse(isPairingLink(view, null, null))
        assertFalse(isPairingLink(view, "https", "example.com"))
    }
}
