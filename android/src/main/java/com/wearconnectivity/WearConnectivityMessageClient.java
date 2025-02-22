package com.wearconnectivity;

import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class WearConnectivityMessageClient implements MessageClient.OnMessageReceivedListener, LifecycleEventListener, CapabilityClient.OnCapabilityChangedListener {

    private static final String TAG = "WearConnectivityMessageClient";
    private final MessageClient messageClient;
    private CapabilityClient capabilityClient;
    private final ReactApplicationContext reactContext;
    private boolean isListenerAdded;
    private boolean isWearableDevice;
    private final Set<Node> connectedNodes = new HashSet<>();

    public WearConnectivityMessageClient(ReactApplicationContext context) {
        this.reactContext = context;
        this.messageClient = Wearable.getMessageClient(context);
        this.capabilityClient = Wearable.getCapabilityClient(context);
        this.isWearableDevice = context.getPackageManager().hasSystemFeature("android.hardware.type.watch");
        context.addLifecycleEventListener(this);

    }

    /**
     * Sends a message to the first nearby node among the provided connectedNodes.
     * If no nearby node is found, it invokes the error callback.
     */
    public void sendMessage(ReadableMap messageData, Callback errorCb) {
        for (Node node : connectedNodes) {
            if (node.isNearby()) {
                JSONObject messageJSON = new JSONObject(messageData.toHashMap());
                sendMessageToClient(messageJSON.toString(), node, errorCb);
                return;
            }
        }
        errorCb.invoke("No nearby node found");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        try {
            JSONObject jsonObject = new JSONObject(messageEvent.getPath());
            WritableMap messageAsWritableMap = fromJSONObject(jsonObject);
            sendEvent("message", messageAsWritableMap);
        } catch (JSONException e) {
            FLog.w(TAG, TAG + " onMessageReceived with message: " + messageEvent.getPath() + " failed with error: " + e);
        }
    }

    public static WritableMap fromJSONObject(JSONObject obj) throws JSONException {
        WritableMap result = Arguments.createMap();
        Iterator<String> keys = obj.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            Object val = obj.get(key);
            if (val instanceof JSONObject) {
                result.putMap(key, fromJSONObject((JSONObject) val));
            } else if (val instanceof JSONArray) {
                result.putArray(key, fromJSONArray((JSONArray) val));
            } else if (val instanceof String) {
                result.putString(key, (String) val);
            } else if (val instanceof Boolean) {
                result.putBoolean(key, (Boolean) val);
            } else if (val instanceof Integer) {
                result.putInt(key, (Integer) val);
            } else if (val instanceof Double) {
                result.putDouble(key, (Double) val);
            } else if (val instanceof Long) {
                result.putDouble(key, ((Long) val).doubleValue());
            } else if (obj.isNull(key)) {
                result.putNull(key);
            } else {
                // Unknown value type. Will throw
                throw new JSONException("Unexpected value when parsing JSON object. key: " + key);
            }
        }

        return result;
    }

    public static WritableArray fromJSONArray(JSONArray arr) throws JSONException {
        WritableArray result = Arguments.createArray();

        for (int i = 0; i < arr.length(); i++) {
            Object val = arr.get(i);

            if (val instanceof JSONObject) {
                result.pushMap(fromJSONObject((JSONObject) val));
            } else if (val instanceof JSONArray) {
                result.pushArray(fromJSONArray((JSONArray) val));
            } else if (val instanceof String) {
                result.pushString((String) val);
            } else if (val instanceof Boolean) {
                result.pushBoolean((Boolean) val);
            } else if (val instanceof Integer) {
                result.pushInt((Integer) val);
            } else if (val instanceof Double) {
                result.pushDouble((Double) val);
            } else if (val instanceof Long) {
                result.pushDouble(((Long) val).doubleValue());
            } else if (arr.isNull(i)) {
                result.pushNull();
            } else {
                // Unknown value type. Will throw
                throw new JSONException("Unexpected value when parsing JSON array. index: " + i);
            }
        }

        return result;
    }

    @Override
    public void onHostResume() {
        if (messageClient != null && !isListenerAdded) {
            Log.d(TAG, "Adding listener on host resume");
            messageClient.addListener(this);
            String localCapability = isWearableDevice ? "watch" : "phone";
            String targetCapability = isWearableDevice ? "phone" : "watch";
            capabilityClient.addLocalCapability(localCapability);
            capabilityClient.addListener(this, targetCapability);
            Task<CapabilityInfo> capabilityInfoTask = capabilityClient.getCapability(
                targetCapability, CapabilityClient.FILTER_REACHABLE);
            capabilityInfoTask.addOnSuccessListener(this::updateConnectedNodes);
            isListenerAdded = true;
        }
    }

    @Override
    public void onCapabilityChanged(@NonNull CapabilityInfo capabilityInfo) {
        updateConnectedNodes(capabilityInfo);
    }

    private void updateConnectedNodes(CapabilityInfo capabilityInfo) {
        connectedNodes.clear();
        for (Node node : capabilityInfo.getNodes()) {
            if (node.isNearby()) {
                connectedNodes.add(node);
            }
        }
        sendCapabilityEvent();
    }

    private void sendCapabilityEvent() {
        sendEvent("reachability", !connectedNodes.isEmpty());
    }

     private void sendEvent(String eventName, Object params) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    public boolean isConnected() {
        return !connectedNodes.isEmpty();
    }

    @Override
    public void onHostPause() {
        Log.d(TAG, "onHostPause: leaving listener active for background events");
    }

    @Override
    public void onHostDestroy() {
        if (messageClient != null && isListenerAdded) {
            Log.d(TAG, "Removing listener on host destroy");
            messageClient.removeListener(this);
            capabilityClient.removeLocalCapability(isWearableDevice ? "watch" : "phone");
            capabilityClient.removeListener(this);
            isListenerAdded = false;
        }
    }

    /**
     * Helper method that sends a message to a specific node.
     */
    private void sendMessageToClient(String message, Node node, Callback errorCb) {
        OnFailureListener onFailureListener = error -> errorCb.invoke("message sending failed: " + error.toString());
        try {
            Task<Integer> sendTask = messageClient.sendMessage(node.getId(), message, null);
            sendTask.addOnFailureListener(onFailureListener);
        } catch (Exception e) {
            errorCb.invoke("sendMessage failed: " + e);
        }
    }
}