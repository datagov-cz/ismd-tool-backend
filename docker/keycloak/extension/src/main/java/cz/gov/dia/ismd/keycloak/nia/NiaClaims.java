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
    static final String CLAIMS_CONFIG_KEY = "niaClaims";
    static final String CLAIMS_PARAM = "claims";

    private NiaClaims() {
    }

    /** The brokered identity id for a token without {@code sub}. */
    static String subjectFrom(Map<String, Object> claims) {
        Object personIdentifier = claims == null ? null : claims.get(PERSON_IDENTIFIER_CLAIM);
        if (personIdentifier == null || personIdentifier.toString().isBlank()) {
            throw new IdentityBrokerException(
                    "NIA token has neither sub nor " + PERSON_IDENTIFIER_CLAIM
                            + " — check that the authorize request carries the claims parameter");
        }
        return personIdentifier.toString();
    }

    /**
     * Percent-encodes the claims JSON before it reaches {@code UriBuilder#queryParam}.
     * UriBuilder treats {@code {...}} as a URI template, so raw JSON would make
     * {@code build()} fail; already-encoded sequences are preserved by UriBuilder.
     */
    static String encodeClaims(String json) {
        return URLEncoder.encode(json.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
