package cz.gov.dia.ismd.keycloak.nia;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.keycloak.broker.provider.IdentityBrokerException;

/**
 * NIA-specific claim handling, kept free of the Keycloak broker class hierarchy so it
 * can be unit-tested without a running server (loading OIDCIdentityProvider needs
 * server runtime classes).
 */
final class NiaClaims {

    static final String PERSON_IDENTIFIER_CLAIM = "PersonIdentifier";
    static final String USERNAME_CLAIM = "niaUsername";
    static final String CLAIMS_CONFIG_KEY = "niaClaims";
    static final String CLAIMS_PARAM = "claims";

    /** Message key shown when NIA released no identifier; see theme-resources/messages. */
    static final String IDENTIFIER_REFUSED_MESSAGE = "niaIdentifierRefused";

    /**
     * Logout state marking "NIA released no identifier". NIA hands the state back on the
     * return to logout_response, where it selects the explanatory page instead of the
     * normal "finish the user session" path. Followed by the Keycloak client_id, so the
     * page can link back to the application.
     */
    static final String REFUSED_STATE_PREFIX = "nia-no-identifier.";

    private NiaClaims() {
    }

    /** Whether the token carries the claim the brokered identity is keyed on. */
    static boolean hasPersonIdentifier(Map<String, Object> claims) {
        Object personIdentifier = claims == null ? null : claims.get(PERSON_IDENTIFIER_CLAIM);
        return personIdentifier != null && !personIdentifier.toString().isBlank();
    }

    /** The brokered identity id for a token without {@code sub}. */
    static String subjectFrom(Map<String, Object> claims) {
        if (!hasPersonIdentifier(claims)) {
            throw refused();
        }
        return claims.get(PERSON_IDENTIFIER_CLAIM).toString();
    }

    /**
     * No identifier: the citizen refused consent, or the authorize request lacked the
     * claims parameter. Carries the message code, so the stock broker error path shows
     * the explanatory page rather than "unexpected error".
     */
    static IdentityBrokerException refused() {
        return new IdentityBrokerException(
                "NIA token has neither sub nor " + PERSON_IDENTIFIER_CLAIM
                        + " — the citizen refused consent, or the authorize request lacks the claims parameter")
                .withMessageCode(IDENTIFIER_REFUSED_MESSAGE);
    }

    /**
     * A Keycloak-valid username for a PersonIdentifier. The realm's
     * username-prohibited-characters validator rejects '/', which eIDAS identifiers
     * contain ("CZ/CZ/&lt;id&gt;"), and a rejected username forces the "Update Account
     * Information" page on first login. Only '/' is replaced, so no assumption is made
     * about the identifier's structure. The federated identity link keeps the original
     * value; this is the display/login name only.
     */
    static String usernameFrom(String personIdentifier) {
        return personIdentifier.replace('/', '-');
    }

    /**
     * Percent-encodes the claims JSON before it reaches {@code UriBuilder#queryParam}.
     * UriBuilder treats {@code {...}} as a URI template, so raw JSON would make
     * {@code build()} fail; already-encoded sequences are preserved by UriBuilder.
     */
    static String encodeClaims(String json) {
        return URLEncoder.encode(json.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Logout state for "no identifier", carrying the client to link back to (may be null). */
    static String refusedState(String clientId) {
        return REFUSED_STATE_PREFIX + (clientId == null ? "" : clientId);
    }

    static boolean isRefusedState(String state) {
        return state != null && state.startsWith(REFUSED_STATE_PREFIX);
    }

    /**
     * The client_id carried by a refused state, or null. The state comes back from the
     * browser, so it is untrusted: it is only ever used to LOOK UP a client for the
     * back-link, and an unknown or empty value just means no link.
     */
    static String clientIdFromRefusedState(String state) {
        if (!isRefusedState(state)) {
            return null;
        }
        String clientId = state.substring(REFUSED_STATE_PREFIX.length());
        return clientId.isBlank() ? null : clientId;
    }
}
