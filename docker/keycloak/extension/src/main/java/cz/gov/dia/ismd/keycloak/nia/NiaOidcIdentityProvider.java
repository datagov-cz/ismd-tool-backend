package cz.gov.dia.ismd.keycloak.nia;

import jakarta.ws.rs.core.UriBuilder;

import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.JsonWebToken;

/**
 * OIDC broker for NIA (Národní bod / Identita občana).
 *
 * <p>NIA deviates from what Keycloak's stock OIDC broker expects in three ways, all
 * confirmed against the NIA test environment:
 * <ul>
 *   <li>The id_token carries <b>no {@code sub}</b>. The stable citizen identifier is the
 *       eIDAS {@code PersonIdentifier} claim. Keycloak takes the brokered identity id
 *       from {@code idToken.getSubject()} and fails without it, so a missing subject is
 *       filled from {@code PersonIdentifier} right after the token is validated.</li>
 *   <li>{@code PersonIdentifier} contains '/', which Keycloak usernames may not. A
 *       {@code niaUsername} claim ('/' replaced by '-') is added for the username
 *       template mapper.</li>
 *   <li>Attributes (including {@code PersonIdentifier}) are only released when the
 *       authorize request carries a {@code claims} JSON parameter. It is read from the
 *       IdP config key {@code niaClaims} and appended to every authorize URL.</li>
 * </ul>
 *
 * <p>Everything else — JWE decryption with the realm's RSA-OAEP key, RS256 signature,
 * expiry, audience and issuer checks — is the stock implementation, untouched.
 * NIA's userinfo endpoint returns {@code {}}; configure the IdP with
 * {@code disableUserInfo=true} so the stock code does not overwrite the id from it.
 */
public class NiaOidcIdentityProvider extends OIDCIdentityProvider {

    public NiaOidcIdentityProvider(KeycloakSession session, OIDCIdentityProviderConfig config) {
        super(session, config);
    }

    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        UriBuilder uriBuilder = super.createAuthorizationUrl(request);
        String claims = getConfig().getConfig().get(NiaClaims.CLAIMS_CONFIG_KEY);
        if (claims != null && !claims.isBlank()) {
            uriBuilder.queryParam(NiaClaims.CLAIMS_PARAM, NiaClaims.encodeClaims(claims));
        }
        return uriBuilder;
    }

    @Override
    protected JsonWebToken validateToken(String encodedToken, boolean ignoreAudience) {
        JsonWebToken token = super.validateToken(encodedToken, ignoreAudience);
        if (token.getSubject() == null || token.getSubject().isBlank()) {
            token.setSubject(NiaClaims.subjectFrom(token.getOtherClaims()));
        }
        // Exposed as a claim so the username template mapper can use ${CLAIM.niaUsername}
        // — its templates cannot rewrite characters themselves.
        Object personIdentifier = token.getOtherClaims().get(NiaClaims.PERSON_IDENTIFIER_CLAIM);
        if (personIdentifier != null && !personIdentifier.toString().isBlank()) {
            token.getOtherClaims().putIfAbsent(NiaClaims.USERNAME_CLAIM,
                    NiaClaims.usernameFrom(personIdentifier.toString()));
        }
        return token;
    }
}
