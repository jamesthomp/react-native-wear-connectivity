package com.wearconnectivity;

import androidx.annotation.NonNull;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.Wearable;

import java.util.List;
import com.google.android.gms.common.GoogleApiAvailability;

public class WearConnectivityModule extends WearConnectivitySpec {
  public static final String NAME = "WearConnectivity";
  private static final String TAG = "react-native-wear-connectivity ";
  private final WearConnectivityMessageClient messageClient;
  private final WearConnectivityDataClient dataClient;

  WearConnectivityModule(ReactApplicationContext context) {
    super(context);
    messageClient = new WearConnectivityMessageClient(context);
    dataClient = new WearConnectivityDataClient(context);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  /**
   * send a file to wearOs
   * @param filePath the path of the file to be sent
   * @param promise
   */
  @ReactMethod
  public void sendFile(String filePath, ReadableMap metadata, Promise promise) {
    if (dataClient != null) {
      dataClient.sendFile(filePath, promise);
    } else {
      promise.reject("E_SEND_FAILED", "Failed to send file");
    }
  }

  /**
   * Sends a message to the first nearby node among the provided connectedNodes.
   * If no nearby node is found, it invokes the error callback.
   */
  @ReactMethod
  public void sendMessage(ReadableMap messageData, Callback errorCb) {
    messageClient.sendMessage(messageData, errorCb);
  }

  @ReactMethod
  public void isConnected(Promise promise) {
      promise.resolve(messageClient.isConnected());
  }

  @ReactMethod
  public void getTransferFiles(Promise promise) {
    if (dataClient != null) {
      dataClient.getTransferFiles(promise);
    } else {
      promise.reject("E_GET_FILES_FAILED", "Failed to retrieve transfer files");
    }
  }

  @ReactMethod
  public void deleteFileTransfer(String fileName, Promise promise) {
    if (dataClient != null) {
      dataClient.deleteFileTransfer(fileName, promise);
    } else {
      promise.reject("E_DELETE_FILE_FAILED", "Failed to delete file");
    }
  }
}
