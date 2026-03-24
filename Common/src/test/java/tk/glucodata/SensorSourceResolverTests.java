package tk.glucodata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SensorSourceResolverTests {
    @Test
    public void resolvesKnownFallbackKindsWithoutSnapshot() {
        assertEquals("Libre2", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_LIBRE2));
        assertEquals("Libre3", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_LIBRE3));
        assertEquals("GS1Sb", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_SIBIONICS));
        assertEquals("AccuChek", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_ACCUCHEK));
        assertEquals("G7", SensorSourceResolver.resolveSourceInfo(null, SensorSourceResolver.SENSOR_KIND_DEXCOM));
    }

    @Test
    public void unresolvedFallbackDoesNotLie() {
        assertEquals("Unknown", SensorSourceResolver.resolveSourceInfo(null, 0));
        assertEquals("Unknown", SensorSourceResolver.resolveSourceInfo(null, -1));
    }

    @Test
    public void xdripSourceInfoUsesCompatibilityFallbackForUnsupportedKinds() {
        assertEquals("Libre2", SensorSourceResolver.resolveXdripSourceInfo(null, 0));
        assertEquals("Libre2", SensorSourceResolver.resolveXdripSourceInfo(null, SensorSourceResolver.SENSOR_KIND_AIDEX));
        assertEquals("Libre3", SensorSourceResolver.resolveXdripSourceInfo(null, SensorSourceResolver.SENSOR_KIND_LIBRE3));
        assertEquals("G7", SensorSourceResolver.resolveXdripSourceInfo(null, SensorSourceResolver.SENSOR_KIND_DEXCOM));
    }
}
