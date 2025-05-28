package dev.bessems.usbserial;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.felhr.usbserial.UsbSerialDevice; // Assuming this is from 'com.github.felHR:usbSerial:X.X.X'

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.EventChannel;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;

import androidx.annotation.NonNull;


/** UsbSerialPlugin */
public class UsbSerialPlugin implements FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {

    private static final String TAG = "UsbSerialPlugin"; // Corrected TAG

    private Context m_Context;
    private UsbManager m_Manager;
    private int m_InterfaceId; // Used for generating unique IDs for UsbSerialPortAdapter
    private BinaryMessenger m_Messenger;
    private EventChannel.EventSink m_EventSink;


    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";

    private final BroadcastReceiver m_UsbAttachDetachReceiver = new BroadcastReceiver() {

        private UsbDevice getUsbDeviceFromIntent(Intent intent) {
            if (intent == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                // Create local variable to keep scope of deprecation suppression smallest
                @SuppressWarnings("deprecation")
                UsbDevice ret = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                return ret;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            Log.d(TAG, "Received action: " + action);
            if (m_EventSink == null) {
                Log.w(TAG, "m_EventSink is null, cannot forward USB event: " + action);
                return;
            }

            UsbDevice device = getUsbDeviceFromIntent(intent);
            if (device == null) {
                Log.e(TAG, action + " but no EXTRA_DEVICE or intent was null");
                return;
            }

            HashMap<String, Object> msg = serializeDevice(device); // serializeDevice should handle null device values gracefully if needed
            if (ACTION_USB_ATTACHED.equals(action)) {
                Log.d(TAG, "USB Device Attached: " + device.getDeviceName());
                msg.put("event", ACTION_USB_ATTACHED);
                m_EventSink.success(msg);
            } else if (ACTION_USB_DETACHED.equals(action)) {
                Log.d(TAG, "USB Device Detached: " + device.getDeviceName());
                msg.put("event", ACTION_USB_DETACHED);
                m_EventSink.success(msg);
            }
        }
    };

    public UsbSerialPlugin() {
        // Constructor
    }

    // Helper method to generate a unique action string for USB permission
    private String getActionUsbPermission() {
        if (m_Context == null) {
            Log.e(TAG, "Context is null, cannot generate unique ACTION_USB_PERMISSION. Using fallback.");
            return "dev.bessems.usbserial.USB_PERMISSION_FALLBACK"; // Ensure this is unique enough
        }
        return m_Context.getPackageName() + ".USB_PERMISSION";
    }

    private interface AcquirePermissionCallback {
        void onSuccess(UsbDevice device);
        void onFailed(UsbDevice device, String reason);
    }

    @SuppressLint("PrivateApi") // Kept from original, re-evaluate if necessary
    private void acquirePermissions(UsbDevice device, AcquirePermissionCallback cb) {
        if (m_Context == null) {
            Log.e(TAG, "Context is null in acquirePermissions. Cannot request permission.");
            cb.onFailed(device, "Context not available for permission request.");
            return;
        }
        if (m_Manager == null) {
            Log.e(TAG, "UsbManager is null in acquirePermissions. Cannot request permission.");
            cb.onFailed(device, "UsbManager not initialized for permission request.");
            return;
        }

        // Using a nested class for the BroadcastReceiver to handle the permission result
        class PermissionReceiver extends BroadcastReceiver {
            private final UsbDevice m_Device;
            private final AcquirePermissionCallback m_CB;
            private boolean m_Handled = false; // To prevent multiple callbacks

            PermissionReceiver(UsbDevice device, AcquirePermissionCallback cb) {
                m_Device = device;
                m_CB = cb;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                if (m_Handled) return; // Ensure one-shot handling

                String action = intent.getAction();
                if (getActionUsbPermission().equals(action)) {
                    m_Handled = true;
                    Log.d(TAG, "Permission broadcast received for action: " + action);
                    try {
                        // It's good practice to unregister the receiver as soon as it's done its job.
                        m_Context.unregisterReceiver(this);
                        Log.d(TAG, "USB permission receiver unregistered.");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Error unregistering USB permission receiver: " + e.getMessage() + ". Already unregistered?");
                    }

                    // The EXTRA_DEVICE in the permission result intent might not always be the same instance
                    // or might be null in some edge cases. It's safer to use the m_Device we started with.
                    // UsbDevice grantedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.d(TAG, "Permission GRANTED for device " + (m_Device != null ? m_Device.getDeviceName() : "null device"));
                        if (m_Device != null) {
                           m_CB.onSuccess(m_Device);
                        } else {
                           Log.e(TAG, "Device in PermissionReceiver was unexpectedly null after permission grant.");
                           m_CB.onFailed(null, "Internal error: device reference lost post-grant.");
                        }
                    } else {
                        Log.d(TAG, "Permission DENIED for device " + (m_Device != null ? m_Device.getDeviceName() : "null device"));
                        m_CB.onFailed(m_Device, "USB permission denied by user.");
                    }
                }
            }
        }

        PermissionReceiver usbPermissionReceiver = new PermissionReceiver(device, cb);

        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_MUTABLE;
        }
        // For older versions, '0' implies no specific flags like FLAG_ONE_SHOT or FLAG_NO_CREATE.
        // FLAG_ONE_SHOT might be suitable here to ensure it's used once, but 0 is also common.

        Intent permissionIntentExtras = new Intent(getActionUsbPermission());
        String appPackageName = m_Context.getPackageName();
        if (appPackageName != null && !appPackageName.isEmpty()) {
            permissionIntentExtras.setPackage(appPackageName);
        } else {
            Log.w(TAG, "Could not get package name for permission intent. Proceeding without it for setPackage.");
        }
        // Add device info to the intent if needed for the receiver, though not strictly necessary
        // as the receiver instance already holds a reference to the device.
        // permissionIntentExtras.putExtra("device_hash", device.hashCode());


        PendingIntent permissionPendingIntent = PendingIntent.getBroadcast(m_Context, device.getDeviceId(), permissionIntentExtras, flags);
        // Using device.getDeviceId() as requestCode for PendingIntent to make it unique per device if multiple requests fly.

        IntentFilter filter = new IntentFilter(getActionUsbPermission());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m_Context.registerReceiver(usbPermissionReceiver, filter, null, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            m_Context.registerReceiver(usbPermissionReceiver, filter);
        }
        Log.d(TAG, "Registered USB permission receiver. Requesting USB permission for device: " + device.getDeviceName());
        m_Manager.requestPermission(device, permissionPendingIntent);
    }

    private void openDevice(String type, UsbDevice device, int iface, Result result, boolean attemptToAcquirePermissionOnFirstTry) {
        Log.d(TAG, "openDevice called for " + device.getDeviceName() + ", interface " + iface + ", type: '" + type + "', attemptPermission: " + attemptToAcquirePermissionOnFirstTry);

        if (m_Manager == null) {
            Log.e(TAG, "UsbManager is null in openDevice.");
            result.error(TAG, "UsbManager not initialized.", null);
            return;
        }

        if (!m_Manager.hasPermission(device)) {
            Log.d(TAG, "No permission for device " + device.getDeviceName());
            if (attemptToAcquirePermissionOnFirstTry) {
                Log.d(TAG, "Attempting to acquire permission for " + device.getDeviceName());
                acquirePermissions(device, new AcquirePermissionCallback() {
                    @Override
                    public void onSuccess(UsbDevice grantedDevice) {
                        Log.d(TAG, "Permission granted for " + grantedDevice.getDeviceName() + " via callback. Opening device again.");
                        openDevice(type, grantedDevice, iface, result, false); // Now we have permission, don't re-request.
                    }

                    @Override
                    public void onFailed(UsbDevice failedDevice, String reason) {
                        Log.e(TAG, "Permission denied for " + (failedDevice != null ? failedDevice.getDeviceName() : "null device") + ". Reason: " + reason);
                        result.error(TAG, "USB Permission Denied: " + reason, "Device: " + (failedDevice != null ? failedDevice.getDeviceName() : "N/A"));
                    }
                });
            } else {
                Log.e(TAG, "USB permission not granted and not attempting to acquire for " + device.getDeviceName());
                result.error(TAG, "USB permission not granted for device " + device.getDeviceName(), null);
            }
            return;
        }

        Log.d(TAG, "Permission already granted for device " + device.getDeviceName() + ". Proceeding to open.");
        UsbDeviceConnection connection = null;
        try {
            connection = m_Manager.openDevice(device);
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device " + device.getDeviceName() + " (connection is null) even though permission was granted.");
                result.error(TAG, "Failed to open USB device (connection is null).", "This can happen if the device is already in use, the interface is invalid, or due to other system issues.");
                return;
            }

            Log.d(TAG, "Device " + device.getDeviceName() + " opened successfully. Creating UsbSerialDevice.");
            UsbSerialDevice serialDevice; // Renamed to avoid conflict with UsbDevice device
            if (type != null && !type.isEmpty()) {
                serialDevice = UsbSerialDevice.createUsbSerialDevice(type, device, connection, iface);
            } else {
                // If type is empty/null, let createUsbSerialDevice auto-detect or use default behavior.
                // The felhr library uses iface = -1 to select the first suitable interface if not specified.
                serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, iface);
            }

            if (serialDevice != null) {
                int newInterfaceId = m_InterfaceId++; // Use a new ID for this port
                UsbSerialPortAdapter adapter = new UsbSerialPortAdapter(m_Messenger, newInterfaceId, connection, serialDevice);
                Log.d(TAG, "UsbSerialDevice created. Method channel name: " + adapter.getMethodChannelName() + " for interface ID: " + newInterfaceId);
                result.success(adapter.getMethodChannelName());
            } else {
                Log.e(TAG, "Not a recognized serial device, or failed to create UsbSerialDevice for: " + device.getDeviceName() + " with type: '" + type + "' on interface: " + iface);
                connection.close(); // Close the connection if we can't create a serial device
                result.error(TAG, "Not a serial device or type/interface mismatch.", "Ensure the device VID/PID matches a supported type or that the specified interface is correct.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException during openDevice for " + device.getDeviceName() + ": " + e.getMessage(), e);
            if (connection != null) {
                connection.close();
            }
            result.error(TAG, "USB security error: " + e.getMessage(), "Permission might have been revoked or is missing unexpectedly.");
        } catch (Exception e) {
            Log.e(TAG, "Exception while opening or setting up USB device " + device.getDeviceName() + ": " + e.getMessage(), e);
            if (connection != null) {
                connection.close();
            }
            result.error(TAG, "Failed to configure USB device: " + e.getMessage(), e.toString());
        }
    }

    private void createTyped(String type, int vid, int pid, int deviceIdFromArg, int iface, Result result) {
        if (m_Manager == null) {
            result.error(TAG, "UsbManager not initialized.", null);
            return;
        }
        Map<String, UsbDevice> devices = m_Manager.getDeviceList();
        if (devices == null) {
            result.error(TAG, "Could not get USB device list.", null);
            return;
        }

        UsbDevice foundDevice = null;
        for (UsbDevice device : devices.values()) {
            // Prioritize deviceId if it's valid (not the default 0 or -1 often used as placeholder)
            if (deviceIdFromArg != 0 && deviceIdFromArg != -1 && deviceIdFromArg == device.getDeviceId()) {
                foundDevice = device;
                break;
            }
            // Fallback to VID/PID if deviceId doesn't match or isn't specified meaningfully
            if (device.getVendorId() == vid && device.getProductId() == pid) {
                foundDevice = device;
                // Don't break here if deviceIdFromArg was specified but didn't match;
                // continue searching in case a VID/PID match is also a deviceId match.
                // If deviceIdFromArg was NOT specified meaningfully, this first VID/PID match is fine.
                if (deviceIdFromArg == 0 || deviceIdFromArg == -1) break;
            }
        }

        if (foundDevice != null) {
            Log.d(TAG, "Device found for createTyped: " + foundDevice.getDeviceName());
            openDevice(type, foundDevice, iface, result, true); // true to attempt permission if needed
        } else {
            Log.w(TAG, "No such device found for VID: " + vid + " PID: " + pid + " DeviceID: " + deviceIdFromArg);
            result.error(TAG, "No such device found", "VID: " + vid + ", PID: " + pid + ", DeviceID: " + deviceIdFromArg);
        }
    }

    private HashMap<String, Object> serializeDevice(UsbDevice device) {
        HashMap<String, Object> dev = new HashMap<>();
        if (device == null) {
            Log.w(TAG, "serializeDevice called with null device");
            dev.put("deviceName", "Unknown/Null Device");
            dev.put("vid", 0);
            dev.put("pid", 0);
            dev.put("deviceId", 0);
            return dev;
        }
        dev.put("deviceName", device.getDeviceName());
        dev.put("vid", device.getVendorId());
        dev.put("pid", device.getProductId());
        dev.put("deviceId", device.getDeviceId()); // This is an OS-assigned ID, can change

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            dev.put("manufacturerName", device.getManufacturerName());
            dev.put("productName", device.getProductName());
            dev.put("interfaceCount", device.getInterfaceCount());
            try {
                // Serial number access requires permission, which we might not have at enumeration time.
                // This call can throw SecurityException if permission is not granted yet.
                if (m_Manager != null && m_Manager.hasPermission(device)) {
                    dev.put("serialNumber", device.getSerialNumber());
                } else {
                    dev.put("serialNumber", "N/A (No permission)");
                }
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException getting serial number for " + device.getDeviceName() + ": " + e.getMessage());
                dev.put("serialNumber", "N/A (SecurityException)");
            } catch (Exception e) { // Catch any other unexpected exception
                Log.e(TAG, "Exception getting serial number for " + device.getDeviceName() + ": " + e.getMessage());
                dev.put("serialNumber", "N/A (Error)");
            }
        }
        return dev;
    }

    private void listDevices(Result result) {
        if (m_Manager == null) {
            result.error(TAG, "UsbManager not initialized.", null);
            return;
        }
        Map<String, UsbDevice> devices = m_Manager.getDeviceList();
        if (devices == null) {
            result.error(TAG, "Could not get USB device list (returns null).", null);
            return;
        }
        List<HashMap<String, Object>> transferDevices = new ArrayList<>();
        for (UsbDevice device : devices.values()) {
            transferDevices.add(serializeDevice(device));
        }
        result.success(transferDevices);
    }


    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        Log.d(TAG, "EventChannel onListen called.");
        m_EventSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
        Log.d(TAG, "EventChannel onCancel called.");
        m_EventSink = null;
    }

    private EventChannel m_EventChannel;

    // Called by onAttachedToEngine
    private void register(@NonNull BinaryMessenger messenger, @NonNull Context context) {
        Log.d(TAG, "Registering UsbSerialPlugin.");
        m_Messenger = messenger;
        m_Context = context.getApplicationContext(); // Use application context
        m_Manager = (UsbManager) m_Context.getSystemService(Context.USB_SERVICE);
        m_InterfaceId = 100; // Initial ID for port adapters

        m_EventChannel = new EventChannel(messenger, "usb_serial/usb_events");
        m_EventChannel.setStreamHandler(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);

        // Registering receiver for attach/detach events
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m_Context.registerReceiver(m_UsbAttachDetachReceiver, filter, Context.RECEIVER_EXPORTED);
            // For system broadcasts like ATTACHED/DETACHED, if your manifest doesn't declare it with <receiver>,
            // dynamic registration might need RECEIVER_EXPORTED (or not, depending on if it's a "protected broadcast").
            // Given it's a system broadcast, EXPORTED might be safer or required if no manifest receiver.
            // Test this carefully. If an Activity handles USB_DEVICE_ATTACHED via manifest, that's the primary way.
            // If this receiver is purely for app-lifetime monitoring, then it's fine.
        } else {
            m_Context.registerReceiver(m_UsbAttachDetachReceiver, filter);
        }
        Log.d(TAG, "Registered receiver for USB attach/detach events.");
    }

    // Called by onDetachedFromEngine
    private void unregister() {
        Log.d(TAG, "Unregistering UsbSerialPlugin.");
        if (m_Context != null && m_UsbAttachDetachReceiver != null) {
            try {
                m_Context.unregisterReceiver(m_UsbAttachDetachReceiver);
                Log.d(TAG, "Unregistered USB attach/detach receiver.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Error unregistering USB attach/detach receiver: " + e.getMessage() + ". Already unregistered?");
            }
        }
        if (m_EventChannel != null) {
            m_EventChannel.setStreamHandler(null);
            m_EventChannel = null;
        }
        m_Manager = null;
        m_Context = null;
        m_Messenger = null;
        m_EventSink = null; // Clear the sink
    }

    private MethodChannel m_Channel;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        Log.d(TAG, "onAttachedToEngine");
        register(flutterPluginBinding.getBinaryMessenger(), flutterPluginBinding.getApplicationContext());
        m_Channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "usb_serial");
        m_Channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine");
        if (m_Channel != null) {
            m_Channel.setMethodCallHandler(null);
            m_Channel = null;
        }
        unregister();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        Log.d(TAG, "onMethodCall: " + call.method);
        switch (call.method) {
            case "create": {
                String type = call.argument("type"); // Can be null or empty
                Integer vid = call.argument("vid");
                Integer pid = call.argument("pid");
                Integer deviceId = call.argument("deviceId"); // Can be null, default to 0 or -1 for "any"
                Integer interfaceId = call.argument("interface"); // Can be null, default to -1 for auto

                if (vid == null || pid == null) {
                    result.error(TAG, "Missing VID or PID for create method.", null);
                    return;
                }
                // Provide defaults if arguments are null
                createTyped(
                    type != null ? type : "", // Ensure type is not null
                    vid,
                    pid,
                    deviceId != null ? deviceId : 0, // 0 might mean "any" or "invalid", depends on logic in createTyped
                    interfaceId != null ? interfaceId : -1, // -1 is common for "auto-select interface"
                    result
                );
                break;
            }
            case "listDevices":
                listDevices(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }
}
