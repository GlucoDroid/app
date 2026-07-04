package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PhotoScanTest {
    private static final int REQUEST_BARCODE = 0x10;
    private static final String GS = "\u001D";
    private static final String ACCUCHEK_COMPACT = "01040156300880101125040417260213211R000199040";

    @Test
    public void trimOuterScannerWhitespacePreservesLeadingGsSeparator() {
        String raw = GS + ACCUCHEK_COMPACT;

        assertEquals(raw, PhotoScan.trimOuterScannerWhitespace(raw));
    }

    @Test
    public void normalizeAccuChekPayloadKeepsNativeExpectedLeadingSeparator() {
        String raw = GS + ACCUCHEK_COMPACT;

        assertEquals(raw, PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE));
    }

    @Test
    public void normalizeAccuChekPayloadRestoresScannerStrippedLeadingSeparator() {
        assertEquals(GS + ACCUCHEK_COMPACT, PhotoScan.normalizeScanPayload(ACCUCHEK_COMPACT, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadDoesNotSplitSerialInternal21() {
        String raw = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        String normalized = PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE);

        assertEquals(raw, normalized);
        assertFalse(normalized.contains("P2251" + GS + "211237"));
    }

    @Test
    public void normalizeSibionicsPayloadRepairsSeparatorInsideSerial() {
        String broken = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251" + GS + "211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(broken, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadRepairsSeparatorInsideSerialWithoutBatchSeparator() {
        String broken = "0106972831641476112512161727061510LT46251211C"
                + "21P2251" + GS + "211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(broken, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadAddsMissingSerialSeparator() {
        String compact = "0106972831641476112512161727061510LT46251211C"
                + "21P2251211237GDR75";
        String expected = GS + "0106972831641476112512161727061510LT46251211C"
                + GS + "21P2251211237GDR75";

        assertEquals(expected, PhotoScan.normalizeScanPayload(compact, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadKeepsExistingLongSerial() {
        String raw = GS + "0106972831640165112312091724120810LT41231108C"
                + GS + "21231108GEPD802JPP76";

        assertEquals(raw, PhotoScan.normalizeScanPayload(raw, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadLeavesExistingCompactLongSerialWithoutGsAlone() {
        String compact = "0106972831640165112312091724120810LT41231108C"
                + "21231108GEPD802JPP76";

        assertEquals(compact, PhotoScan.normalizeScanPayload(compact, REQUEST_BARCODE));
    }

    @Test
    public void normalizeSibionicsPayloadKeepsSymbologyPrefixLongSerialFallback() {
        String prefixed = "^]0106972831640165112312091724120810LT41231108C"
                + "21231108GEPD802JPP76";
        String expected = GS + "0106972831640165112312091724120810LT41231108C"
                + GS + "21231108GEPD802JPP76";

        assertEquals(expected, PhotoScan.normalizeScanPayload(prefixed, REQUEST_BARCODE));
    }
}
