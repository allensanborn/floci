package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignedMarkersTest {

    private final SignedMarkers markers = new SignedMarkers();

    @Test
    void anIssuedMarkerNamesItsCursor() {
        assertEquals("role.with.dots", markers.cursorOf(markers.issue("role.with.dots")));
    }

    @Test
    void aMarkerIssuedByAnotherInstanceIsNotRecognised() {
        assertNull(markers.cursorOf(new SignedMarkers().issue("role-b")));
    }

    @Test
    void anUnsignedOrUndecodableMarkerIsNotRecognised() {
        assertNull(markers.cursorOf(Base64Url.encode("role-b")));
        assertNull(markers.cursorOf(Base64Url.encode("role-b.forged")));
        assertNull(markers.cursorOf("not base64!"));
    }

    @Test
    void aScopedMarkerResumesOnlyItsOwnScope() {
        String marker = markers.issue("account-a\nListUsers", "user-b");

        assertEquals("user-b", markers.cursorOf("account-a\nListUsers", marker));
        assertNull(markers.cursorOf("account-b\nListUsers", marker));
        assertNull(markers.cursorOf("account-a\nListGroups", marker));
        assertNull(markers.cursorOf(marker), "an unscoped read does not accept a scoped marker");
        assertNull(markers.cursorOf("account-a\nListUsers", markers.issue("user-b")),
                "a scoped read does not accept an unscoped marker");
    }

    /** The scope is length-prefixed, so moving characters between scope and cursor breaks the signature. */
    @Test
    void theScopeAndCursorCannotTradeCharacters() {
        String marker = markers.issue("ab", "c");

        assertNull(markers.cursorOf("a", Base64Url.encode(
                new String(java.util.Base64.getUrlDecoder().decode(marker)).replaceFirst("^c", "bc"))));
    }
}
