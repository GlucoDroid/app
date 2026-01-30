package tk.glucodata.aidex;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.UUID;

import tk.glucodata.Applic;
import tk.glucodata.Log;

/**
 * A dedicated probe tool to reverse engineer the AiDex/Micro Tech Medical
 * sensor protocol.
 * This class scans for devices, connects to them, discovers services, and logs
 * all
 * communication to Logcat with the tag "AIDEX_RAW".
 */
public class AiDexProbe {
    private static final String TAG = "AIDEX_RAW";
    private static final long SCAN_PERIOD = 10000;
    private static AiDexProbe instance;

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private boolean isScanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private byte[] sessionKey = null;

    private AiDexProbe() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static synchronized AiDexProbe getInstance() {
        if (instance == null) {
            instance = new AiDexProbe();
        }
        return instance;
    }

    @SuppressLint("MissingPermission")
    public void startProbe() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled");
            Applic.Toaster("Enable Bluetooth first!");
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null");
            return;
        }

        if (!isScanning) {
            scanLeDevice();
        }
    }

    @SuppressLint("MissingPermission")
    public void stopProbe() {
        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            Log.i(TAG, "Scan stopped manually");
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
            Log.i(TAG, "Gatt closed manually");
        }
    }

    @SuppressLint("MissingPermission")
    private void scanLeDevice() {
        if (!isScanning) {
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(() -> {
                if (isScanning) {
                    isScanning = false;
                    bluetoothLeScanner.stopScan(scanCallback);
                    Log.i(TAG, "Scan stopped after timeout");
                }
            }, SCAN_PERIOD);

            isScanning = true;
            bluetoothLeScanner.startScan(scanCallback);
            Log.i(TAG, "Scan started...");
            Applic.Toaster("AiDex Probe: Scanning...");
        } else {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            Log.i(TAG, "Scan stopped");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            @SuppressLint("MissingPermission")
            String deviceName = device.getName();
            String address = device.getAddress();
            int rssi = result.getRssi();

            Log.d(TAG, "Found device: " + deviceName + " [" + address + "] RSSI: " + rssi);

            // Heuristic to find the sensor (Adjust as needed based on user feedback)
            // For now, let's look for "AiDex" or just log everything.
            // Since we want to be aggressive in Phase 1, we might connect to the first
            // strong signal
            // if name matches nothing, but let's try to match name first.
            if (deviceName != null
                    && (deviceName.toLowerCase().contains("aidex") || deviceName.toLowerCase().contains("meter"))) {
                connectToDevice(device);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Scan failed with error: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        if (isScanning) {
            bluetoothLeScanner.stopScan(scanCallback);
            isScanning = false;
        }

        Log.i(TAG, "Connecting to " + device.getAddress());
        bluetoothGatt = device.connectGatt(Applic.app, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                // Discover services after successful connection.
                @SuppressLint("MissingPermission")
                boolean success = gatt.discoverServices();
                Log.i(TAG, "Attempting to start service discovery: " + success);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            } else {
                Log.i(TAG, "Connection state changed: " + newState);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered:");
                BluetoothGattService service = gatt.getService(UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb"));

                if (service != null) {
                    final BluetoothGattCharacteristic authChar = service
                            .getCharacteristic(UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb"));
                    final BluetoothGattCharacteristic dataChar = service
                            .getCharacteristic(UUID.fromString("0000f003-0000-1000-8000-00805f9b34fb"));

                    // Execute sequentially in a background thread to avoid blocking callback
                    new Thread(() -> {
                        try {
                            List<BluetoothGattCharacteristic> chars = service.getCharacteristics();

                            // 1. Enable Notifications for ALL supported characteristics
                            // F001 (Auth Challenge), F002, F003 (Data)
                            for (BluetoothGattCharacteristic c : chars) {
                                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                    Log.i(TAG, "Enabling Notify on " + c.getUuid());
                                    enableNotification(gatt, c);
                                    Thread.sleep(300); // Small delay between enables
                                }
                                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0) {
                                    Log.i(TAG, "Enabling Indicate on " + c.getUuid());
                                    enableIndication(gatt, c);
                                    Thread.sleep(300);
                                }
                            }

                            // Wait for notifications to settle
                            Thread.sleep(1000);

                            // 2. Active Handshake: Push Key/Token
                            if (authChar != null) {
                                // "Master Key" observed in HCI Log on Handle 0x32 (F001)
                                String masterKey = "AC4C8ECDD8761B512EEB95D707942912";
                                Log.i(TAG, "Writing Master Key to F001 (Auth)... " + masterKey);
                                authChar.setValue(hexStringToByteArray(masterKey));
                                authChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                gatt.writeCharacteristic(authChar);
                                Thread.sleep(500);
                            }

                            // 3. Request History/Control
                            BluetoothGattCharacteristic ctrlChar = service
                                    .getCharacteristic(UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb"));
                            if (ctrlChar != null) {
                                Log.i(TAG, "Writing F0 to F002 (Control)...");
                                ctrlChar.setValue(new byte[] { (byte) 0xF0 }); // Try F0 on Control?
                                ctrlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                gatt.writeCharacteristic(ctrlChar);
                            }

                            // Note: F001 does not support READ (Prop 0x18).
                            // We expect the Challenge to come as a NOTIFICATION on F001.
                            // See onCharacteristicChanged.

                        } catch (Exception e) {
                            android.util.Log.e(TAG, "Error in probe sequence", e);
                        }
                    }).start();
                } else {
                    Log.w(TAG, "Service F000 not found!");
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        public static byte[] hexStringToByteArray(String s) {
            int len = s.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                        + Character.digit(s.charAt(i + 1), 16));
            }
            return data;
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String uuid = characteristic.getUuid().toString();
            byte[] data = characteristic.getValue();
            String hexStr = bytesToHex(data);
            Log.i(TAG, "NOTIFY/INDICATE " + uuid + " -> " + hexStr);

            if (uuid.equals("0000f001-0000-1000-8000-00805f9b34fb")) {
                // AUTH CHALLENGE NOTIFICATION
                if (data.length >= 16) {
                    sessionKey = new byte[16];
                    System.arraycopy(data, 0, sessionKey, 0, 16);
                    Log.i(TAG, "CAPTURED SESSION KEY (from Notify): " + bytesToHex(sessionKey));

                    // Send Response: BC5EECB4
                    byte[] shortResp = hexStringToByteArray("BC5EECB4");
                    Log.i(TAG, "Writing Challenge Response: BC5EECB4");
                    characteristic.setValue(shortResp);
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    gatt.writeCharacteristic(characteristic);

                    // Trigger History Request (F0)
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            Log.i(TAG, "Requesting History (F0)...");
                            byte[] historyCmd = new byte[] { (byte) 0xF0 };
                            characteristic.setValue(historyCmd);
                            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                            gatt.writeCharacteristic(characteristic);
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "History Request Failed", e);
                        }
                    }).start();
                } else {
                    Log.w(TAG, "Received F001 notification but length < 16: " + hexStr);
                }
            }

            if (uuid.equals("0000f003-0000-1000-8000-00805f9b34fb") && data.length > 1) {
                int b0 = data[0] & 0xFF;
                int b1 = data[1] & 0xFF;
                int glucose = 0;
                String type = "UNKNOWN";

                if (uuid.equals("0000f003-0000-1000-8000-00805f9b34fb")) {
                    Log.i(TAG, "RAW DATA (F003): " + hexStr);

                    // HCI Log Key Candidate (From Packet 000036b0 in clean log)
                    // Full Payload: AC 4C 8E CD D8 76 1B 51 2E EB 95 D7 07 94 29 12 E6
                    if (data.length >= 17) {
                        try {
                            byte[] keyBytes = sessionKey;
                            if (keyBytes == null) {
                                String logKey = "AC4C8ECDD8761B512EEB95D707942912";
                                keyBytes = hexStringToByteArray(logKey);
                                Log.w(TAG, "Using Fallback Key (Handshake missed?)");
                            }
                            Log.i(TAG, "Decrypting with Key: " + bytesToHex(keyBytes));

                            byte[] encrypted = new byte[16];
                            System.arraycopy(data, 1, encrypted, 0, 16);

                            javax.crypto.spec.SecretKeySpec funcKey = new javax.crypto.spec.SecretKeySpec(keyBytes,
                                    "AES");
                            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, funcKey);

                            byte[] decrypted = cipher.doFinal(encrypted);
                            String decHex = bytesToHex(decrypted);

                            int op = decrypted[0] & 0xFF; // Valid: A1, A4, D7, 06
                            Log.i(TAG, String.format("DECRYPTED (F003) [Op %02X]: %s", op, decHex));

                        } catch (Exception e) {
                            android.util.Log.e(TAG, "Decryption Error", e);
                        }
                    }
                }

                if (b0 == 0xA1 || b0 == 0xA4 || b0 == 0xD7) {
                    // A1/A4/D7 -> Scale x1
                    glucose = b1;
                    type = String.format("Opcode %02X (x1)", b0);
                } else if (b0 == 0xD2) {
                    // D2 -> Scale x2
                    glucose = b1 / 2;
                    type = "Opcode D2 (x2)";
                } else if (b0 == 0x5B) {
                    // 5B -> x1 (Maybe)
                    glucose = b0;
                    type = "Opcode 5B (?)";
                } else if (b0 == 0xAF) {
                    type = "Opcode AF (Locked?)";
                    Log.w(TAG, "Received AF Packet (Encryption/Handshake?): " + hexStr);
                } else if ((b0 & 0xF0) == 0xA0) {
                    // Check for A1, A4, etc dynamics
                    glucose = b1;
                    type = String.format("Opcode %02X (x1)", b0);
                }

                if (glucose > 0) {
                    float mmol = (float) glucose / 18.0182f;
                    Log.i(TAG, String.format("GLUCOSE DECODED [%s]: %d mg/dL (%.1f mmol/L)", type, glucose, mmol));
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                String uuid = characteristic.getUuid().toString();
                byte[] data = characteristic.getValue();
                String hex = bytesToHex(data);
                Log.i(TAG, "READ " + uuid + " -> " + hex);

                if (uuid.equals("0000f001-0000-1000-8000-00805f9b34fb")) {
                    // Capture Session Key
                    if (data.length >= 16) {
                        sessionKey = new byte[16];
                        System.arraycopy(data, 0, sessionKey, 0, 16);
                        Log.i(TAG, "CAPTURED SESSION KEY: " + bytesToHex(sessionKey));
                    }

                    // Send Hardcoded Response (from HCI Log 36f0)
                    // The log shows a Write Command with payload: BC 5E EC B4
                    byte[] shortResp = hexStringToByteArray("BC5EECB4");
                    Log.i(TAG, "Writing Challenge Response: BC5EECB4");
                    characteristic.setValue(shortResp);
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    gatt.writeCharacteristic(characteristic);

                    // 2. Request History (F0) - Delayed to allow Auth to process
                    // This is a guess: F0 is often a 'Dump History' or 'Control' command
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            Log.i(TAG, "Requesting History (F0)...");
                            byte[] historyCmd = new byte[] { (byte) 0xF0 };
                            characteristic.setValue(historyCmd);
                            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                            gatt.writeCharacteristic(characteristic);
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "History Request Failed", e);
                        }
                    }).start();
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.i(TAG, "WRITE " + characteristic.getUuid().toString() + " status: " + status);
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (characteristic == null)
            return; // Added null check for characteristic
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic
                .getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean success = gatt.writeDescriptor(descriptor);
            Log.i(TAG, "Enabling NOTIFY for " + characteristic.getUuid() + ": " + success);
        }
    }

    @SuppressLint("MissingPermission")
    private void enableIndication(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic
                .getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            boolean success = gatt.writeDescriptor(descriptor);
            Log.i(TAG, "Enabling INDICATE for " + characteristic.getUuid() + ": " + success);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    // Helper to fix missing symbol in generated code
    private static class BluetoothProfile {
        static final int STATE_CONNECTED = 2;
        static final int STATE_DISCONNECTED = 0;
    }
}
