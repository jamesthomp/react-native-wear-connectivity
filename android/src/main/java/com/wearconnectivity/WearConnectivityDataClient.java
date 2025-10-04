package com.wearconnectivity;

import android.webkit.MimeTypeMap;
import android.net.Uri;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.facebook.react.bridge.ReactApplicationContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;

public class WearConnectivityDataClient implements DataClient.OnDataChangedListener, LifecycleEventListener {
    private static final String TAG = "WearConnectivityDataClient";
    private DataClient dataClient;
    private static ReactApplicationContext reactContext;
    private String fileName = "unknown_file";
    private long startTime;
    private int totalBytes;

    public WearConnectivityDataClient(ReactApplicationContext context) {
        dataClient = Wearable.getDataClient(context);
        reactContext = context;
        dataClient.addListener(this);
        context.addLifecycleEventListener(this);
    }

    /**
     * Sends a file (as an Asset) using the DataClient API.
     * @param uri path to the file to be sent.
     */
    public void sendFile(String uri, Promise promise) {
        File file = new File(uri);
        Asset asset = createAssetFromFile(file);
        if (asset == null) {
            FLog.w(TAG, "Failed to create asset from file.");
            return;
        }
        PutDataMapRequest dataMapRequest = PutDataMapRequest.create("/file_transfer");
        dataMapRequest.getDataMap().putAsset("file", asset);
        dataMapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());
        PutDataRequest request = dataMapRequest.asPutDataRequest();
        Task<DataItem> task = dataClient.putDataItem(request);
        task.addOnSuccessListener(dataItem -> {
            promise.resolve("File sent successfully via DataClient.");
        }).addOnFailureListener(e -> {
            promise.reject("File sending failed: " + e);
        });
    }

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().equals("/file_transfer")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    // Extract metadata from the DataMap
                    if (dataMap.containsKey("metadata")) {
                        DataMap metadata = dataMap.getDataMap("metadata");
                        fileName = metadata.getString("fileName", "unknown_file");
                    }

                    Asset asset = dataMap.getAsset("file");
                    if (asset != null) {
                        receiveFile(asset);
                    }
                }
            }
        }
    }

    /**
     * Helper method to create an Asset from a file.
     * @param file the file to convert.
     * @return the resulting Asset, or null if an error occurred.
     */
    private Asset createAssetFromFile(File file) {
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] byteArray = new byte[(int) file.length()];
            fileInputStream.read(byteArray);
            fileInputStream.close();
            return Asset.createFromBytes(byteArray);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static ReactApplicationContext getReactContext() {
        return reactContext;
    }

    private void receiveFile(Asset asset) {
        Task<DataClient.GetFdForAssetResponse> task = dataClient.getFdForAsset(asset);
        startTime = System.currentTimeMillis();

        // Dispatch 'started' event
        dispatchFileTransferEvent("started", startTime, 0, 0, 0, 0, fileName, null);
        task.addOnSuccessListener(this::handleFileReceived)
                .addOnFailureListener(this::handleFileReceiveError);
    }


    /**
     * Dispatches a file transfer event to React Native.
     */
    private void dispatchFileTransferEvent(
            String type, long startTime, long completedUnitCount, long estimatedTimeRemaining,
            float fractionCompleted, long throughput, String fileName, String errorMessage) {
        WritableMap event = Arguments.createMap();
        String correctPath = "/data/data/" + getReactContext().getPackageName() + "/files/" + fileName;
        FLog.w(TAG, "WatchFileReceived filePath: " + correctPath);
        event.putString("type", type);
        event.putString("url", correctPath);
        event.putString("id", fileName);
        event.putDouble("startTime", startTime);
        event.putDouble("endTime", type.equals("finished") ? System.currentTimeMillis() : 0);
        event.putDouble("completedUnitCount", completedUnitCount);
        event.putDouble("estimatedTimeRemaining", estimatedTimeRemaining);
        event.putDouble("fractionCompleted", fractionCompleted);
        event.putDouble("throughput", throughput);
        event.putMap("metadata", getFileMetadata(fileName)); // Get metadata if available
        if (errorMessage != null) {
            event.putString("error", errorMessage);
        } else {
            event.putNull("error");
        }

        if (type.equals("finished")) {
            WritableArray array = Arguments.createArray();
            array.pushMap(event);
            getReactContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("file-received", array);
        } else {
            getReactContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("file-transfer", event);
        }
    }

    /**
     * Retrieves metadata associated with a file.
     */
    private WritableMap getFileMetadata(String fileName) {
        WritableMap metadata = Arguments.createMap();
        metadata.putString("fileName", fileName);
        metadata.putString("fileType", MimeTypeMap.getFileExtensionFromUrl(fileName));
        return metadata;
    }

    private void handleFileReceived(DataClient.GetFdForAssetResponse response) {
        InputStream is = response.getInputStream();
        if (is == null) {
            FLog.w(TAG, "WatchFileReceiveError: InputStream is null");
            return;
        }

        try {
            File file = new File(getReactContext().getFilesDir(), fileName);
            totalBytes = response.getInputStream().available();

            saveFile(is, file);
            dispatchFileTransferEvent("finished", startTime, totalBytes, 0, 1.0f, 0, fileName, null);
        } catch (IOException e) {
            dispatchFileTransferEvent("error", startTime, 0, 0, 0, 0, fileName, e.getMessage());
        }
    }

    private void handleFileReceiveError(@NonNull Exception e) {
        dispatchFileTransferEvent("error", startTime, 0, 0, 0, 0, fileName, e.toString());
    }

    private void dispatchEvent(String eventName, String body) {
        getReactContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, body);
    }

    private void saveFile(InputStream is, File file) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int bytesRead;
        long completedBytes = 0;

        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
            completedBytes += bytesRead;

            // Calculate progress metrics
            float fractionCompleted = (float) completedBytes / totalBytes;
            long elapsedTime = System.currentTimeMillis() - startTime;
            long estimatedTimeRemaining = (long) ((1 - fractionCompleted) * elapsedTime / fractionCompleted);
            long throughput = completedBytes * 8 / (elapsedTime + 1); // Avoid division by zero

            // Dispatch 'progress' event
            dispatchFileTransferEvent("progress", startTime, completedBytes, estimatedTimeRemaining, fractionCompleted, throughput, fileName, null);
        }
        fos.flush();
        fos.close();
        is.close();
    }

    /**
     * Retrieves a list of files currently transferring in the DataClient.
     * @param promise Promise to resolve the list of files or an error.
     */
    public void getTransferFiles(Promise promise) {
        Task<DataItemBuffer> task = dataClient.getDataItems();
        task.addOnSuccessListener(dataItems -> {
            WritableArray fileList = Arguments.createArray();

            for (DataItem item : dataItems) {
                if (item.getUri().getPath().startsWith("/file_transfer")) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    WritableMap fileData = Arguments.createMap();
                    fileData.putString("uri", item.getUri().toString());
                    fileData.putString("fileName", dataMap.getString("fileName", "unknown_file"));
                    fileData.putDouble("timestamp", dataMap.getLong("timestamp", 0));
                    fileList.pushMap(fileData);
                }
            }

            dataItems.release();
            promise.resolve(fileList);
        }).addOnFailureListener(e -> {
            promise.reject("Failed to retrieve transfer files: " + e.getMessage());
        });
    }

    /**
     * Deletes a specific file from the DataClient.
     * @param uri URI of the file to delete.
     * @param promise Promise to resolve the result of the deletion.
     */
    public void deleteFileTransfer(String uri, Promise promise) {
        Task<Integer> task = dataClient.deleteDataItems(Uri.parse(uri));
        task.addOnSuccessListener(dataItems -> {
            boolean fileDeleted = dataItems > 0;
            if (fileDeleted) {
                promise.resolve("deleted");
            } else {
                promise.reject("File not found");
            }
        }).addOnFailureListener(e -> {
            promise.reject("Failed to delete file: " + e.getMessage());
        });
    }

    @Override
    public void onHostResume() {
        // do nothing
    }

    @Override
    public void onHostPause() {
        // do nothing
    }

    @Override
    public void onHostDestroy() {
        dataClient.removeListener(this);
    }
}