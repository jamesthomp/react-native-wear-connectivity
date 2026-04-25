package com.wearconnectivity;

import android.webkit.MimeTypeMap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
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
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;

import androidx.annotation.NonNull;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;

public class WearConnectivityDataClient implements DataClient.OnDataChangedListener, LifecycleEventListener {
    private final DataClient dataClient;
    private final ReactApplicationContext reactContext;
    
    // Thread-safe set to prevent processing the same URI multiple times simultaneously
    private final Set<String> processingUris = Collections.synchronizedSet(new HashSet<>());

    public WearConnectivityDataClient(ReactApplicationContext context) {
        this.reactContext = context;
        this.dataClient = Wearable.getDataClient(context);
        this.dataClient.addListener(this);
        this.reactContext.addLifecycleEventListener(this);
    }

    public void sendFile(String uri, Promise promise) {
        File file = new File(uri);
        Asset asset;
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            asset = Asset.createFromFd(pfd);
        } catch (IOException e) {
            promise.reject("Error creating asset from file: " + e.getMessage());
            return;
        }

        PutDataMapRequest dataMapRequest = PutDataMapRequest.createWithAutoAppendedId("/file_transfer");
        dataMapRequest.getDataMap().putString("fileName", file.getName());
        dataMapRequest.getDataMap().putAsset("file", asset);
        dataMapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest request = dataMapRequest.asPutDataRequest();
        request.setUrgent();

        dataClient.putDataItem(request)
            .addOnSuccessListener(dataItem -> promise.resolve("File sent successfully: " + dataItem.getUri()))
            .addOnFailureListener(e -> promise.reject("File sending failed: " + e));
    }

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                Uri uri = item.getUri();
                String path = uri.getPath();

                if (path != null && path.startsWith("/file_transfer")) {
                    final String uriString = uri.toString();

                    if (processingUris.contains(uriString)) {
                        continue;
                    }

                    processingUris.add(uriString);
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    final String fName = dataMap.getString("fileName", "unknown_file");
                    Asset asset = dataMap.getAsset("file");

                    if (asset != null) {
                        receiveFile(asset, fName, uriString);
                    } else {
                        processingUris.remove(uriString);
                    }
                }
            }
        }
    }

    private void receiveFile(Asset asset, final String fName, final String uriString) {
        final long taskStartTime = System.currentTimeMillis();
        
        dataClient.getFdForAsset(asset)
            .addOnSuccessListener(response -> {
                new Thread(() -> {
                    try (InputStream is = response.getInputStream()) {
                        if (is == null) return;
                        
                        File file = new File(baseDirectory(), fName);
                        saveFile(is, file);
                        dispatchFileTransferEvent("finished", taskStartTime, file.length(), 0, 1.0f, 0, fName, file.getAbsolutePath(), null);
                    } catch (IOException e) {
                        dispatchFileTransferEvent("error", taskStartTime, 0, 0, 0, 0, fName, "", e.getMessage());
                    } finally {
                        processingUris.remove(uriString);
                    }
                }).start();
            })
            .addOnFailureListener(e -> {
                processingUris.remove(uriString);
                dispatchFileTransferEvent("error", taskStartTime, 0, 0, 0, 0, fName, "", e.toString());
            });
    }

    private void saveFile(InputStream is, File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
            fos.getFD().sync();
        }
    }

    private void dispatchFileTransferEvent(
            String type, long startTime, long completedUnitCount, long estimatedTimeRemaining,
            float fractionCompleted, long throughput, String fName, String filePath, String errorMessage) {

        if (!reactContext.hasActiveReactInstance()) return;

        WritableMap event = Arguments.createMap();

        event.putString("type", type);
        event.putString("url", filePath);
        event.putString("id", fName);
        event.putDouble("startTime", (double) startTime);
        event.putDouble("endTime", type.equals("finished") ? (double) System.currentTimeMillis() : 0);
        event.putDouble("completedUnitCount", (double) completedUnitCount);
        event.putDouble("estimatedTimeRemaining", (double) estimatedTimeRemaining);
        event.putDouble("fractionCompleted", (double) fractionCompleted);
        event.putDouble("throughput", (double) throughput);
        event.putMap("metadata", getFileMetadata(fName));

        if (errorMessage != null) {
            event.putString("error", errorMessage);
        } else {
            event.putNull("error");
        }

        DeviceEventManagerModule.RCTDeviceEventEmitter emitter = 
            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

        if (type.equals("finished")) {
            WritableArray array = Arguments.createArray();
            array.pushMap(event);
            emitter.emit("file-received", array);
        } else {
            emitter.emit("file-transfer", event);
        }
    }

    private WritableMap getFileMetadata(String fName) {
        WritableMap metadata = Arguments.createMap();
        metadata.putString("fileName", fName);
        metadata.putString("fileType", MimeTypeMap.getFileExtensionFromUrl(fName));
        return metadata;
    }

    private File baseDirectory() {
        File dir = new File(reactContext.getFilesDir(), "FilesReceived");
        dir.mkdirs();
        return dir;
    }

    public void getTransferFiles(Promise promise) {
        new Thread(() -> {
            try {
                DataItemBuffer dataItems = Tasks.await(dataClient.getDataItems());
                WritableArray fileList = Arguments.createArray();
                for (DataItem item : dataItems) {
                    if (item.getUri().getPath().startsWith("/file_transfer")) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        String fName = dataMap.getString("fileName", "unknown_file");
                        Asset asset = dataMap.getAsset("file");
                        File actualFile = new File(baseDirectory(), fName);
                        if (!actualFile.exists() && asset != null) {
                            try {
                                Task<DataClient.GetFdForAssetResponse> fdTask = dataClient.getFdForAsset(asset);
                                DataClient.GetFdForAssetResponse response = Tasks.await(fdTask);
                                try (InputStream is = response.getInputStream()) {
                                    if (is != null) {
                                        saveFile(is, actualFile);
                                    }
                                }
                            } catch (Exception e) {
                                promise.reject("failed: " + e.getMessage());
                                return;
                            }
                        }
                        if (actualFile.exists()) {
                            WritableMap fileData = Arguments.createMap();
                            fileData.putString("uri", item.getUri().toString());
                            fileData.putString("id", fName);
                            fileData.putString("fileName", fName);
                            fileData.putString("url", "file://" + actualFile.getAbsolutePath());
                            fileList.pushMap(fileData);
                        }
                    }
                }
                dataItems.release();
                promise.resolve(fileList);

            } catch (Exception e) {
                promise.reject("Failed to retrieve transfer files: " + e.getMessage());
            }
        }).start();
    }

    public void deleteFileTransfer(String uri, Promise promise) {
        dataClient.deleteDataItems(Uri.parse(uri))
            .addOnSuccessListener(count -> {
                if (count > 0) promise.resolve("deleted");
                else promise.reject("File not found");
            })
            .addOnFailureListener(e -> promise.reject("Failed: " + e.getMessage()));
    }

    @Override public void onHostResume() {}
    @Override public void onHostPause() {}

    @Override
    public void onHostDestroy() {
        if (dataClient != null) {
            dataClient.removeListener(this);
        }
        if (reactContext != null) {
            reactContext.removeLifecycleEventListener(this);
        }
    }
}