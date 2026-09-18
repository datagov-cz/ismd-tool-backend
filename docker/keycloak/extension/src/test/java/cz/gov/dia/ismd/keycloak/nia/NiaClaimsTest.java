package cz.gov.dia.ismd.keycloak.nia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.IdentityBrokerException;

class NiaClaimsTest {

    @Test
    void subjectIsTakenFromPersonIdentifier() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("PersonIdentifier", "CZ/CZ/abc-123");
        claims.put("CurrentGivenName", "Jan");

        assertEquals("CZ/CZ/abc-123", NiaClaims.subjectFrom(claims));
    }

    @Test
    void missingPersonIdentifierFailsWithBrokerException() {
        Map<String, Object> claims = Map.of("CurrentGivenName", "Jan");

        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(claims));
    }

    @Test
    void blankPersonIdentifierFailsWithBrokerException() {
        Map<String, Object> claims = Map.of("PersonIdentifier", "  ");

        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(claims));
    }

    @Test
    void nullClaimsFailWithBrokerException() {
        assertThrows(IdentityBrokerException.class, () -> NiaClaims.subjectFrom(null));
    }

    @Test
    void usernameReplacesSlashesOnly() {
        assertEquals("CZ-CZ-ede5bc65-36ad-416c-ac39-6fdb7db11160",
                NiaClaims.usernameFrom("CZ/CZ/ede5bc65-36ad-416c-ac39-6fdb7db11160"));
    }

    @Test
    void usernameWithoutSlashesIsUnchanged() {
        assertEquals("abc-123", NiaClaims.usernameFrom("abc-123"));
    }

    @Test
    void claimsAreEncodedWithoutTemplateBracesAndRoundTrip() {
        String json = "{\"id_token\":{\"PersonIdentifier\":null,\"CurrentGivenName\":null,\"CurrentFamilyName\":null}}";

        String encoded = NiaClaims.encodeClaims(json);

        assertFalse(encoded.contains("{") || encoded.contains("}"), "raw braces would be read as a URI template");
        assertFalse(encoded.contains("+"), "spaces must not be encoded as '+'");
        assertEquals(json, URLDecoder.decode(encoded, StandardCharsets.UTF_8));
    }

    @Test
    void missingPersonIdentifierCarriesTheRefusalMessageCode() {
        IdentityBrokerException e = assertThrows(IdentityBrokerException.class,
                () -> NiaClaims.subjectFrom(Map.of()));

        assertEquals(NiaClaims.IDENTIFIER_REFUSED_MESSAGE, e.getMessageCode());
    }

    @Test
    void hasPersonIdentifierRejectsMissingBlankAndNull() {
        assertTrue(NiaClaims.hasPersonIdentifier(Map.of("PersonIdentifier", "CZ/CZ/abc")));
        assertFalse(NiaClaims.hasPersonIdentifier(Map.of("PersonIdentifier", " ")));
        assertFalse(NiaClaims.hasPersonIdentifier(Map.of("CurrentGivenName", "Jan")));
        assertFalse(NiaClaims.hasPersonIdentifier(null));
    }

    @Test
    void refusedStateRoundTripsTheClientId() {
        String state = NiaClaims.refusedState("ismd-app");

        assertTrue(NiaClaims.isRefusedState(state));
        assertEquals("ismd-app", NiaClaims.clientIdFromRefusedState(state));
    }

    @Test
    void refusedStateWithoutClientHasNoClientId() {
        String state = NiaClaims.refusedState(null);

        assertTrue(NiaClaims.isRefusedState(state));
        assertNull(NiaClaims.clientIdFromRefusedState(state));
    }

    @Test
    void ordinaryLogoutStateIsNotARefusal() {
        // Keycloak's own logout state is the user session id (a UUID).
        String state = "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f";

        assertFalse(NiaClaims.isRefusedState(state));
        assertFalse(NiaClaims.isRefusedState(null));
        assertNull(NiaClaims.clientIdFromRefusedState(state));
    }
}
