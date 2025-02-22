import { Platform } from 'react-native';
import type { SendMessage, Payload } from './NativeWearConnectivity';
import { WearConnectivity } from './index';
import { LIBRARY_NAME, IOS_NOT_SUPPORTED_WARNING } from './constants';

const defaultCb = () => {};

const sendMessage: SendMessage = (message, errCb) => {
  const json: Payload = message;
  const errCbWithDefault = errCb ?? defaultCb;
  return WearConnectivity.sendMessage(json, errCbWithDefault);
};

const sendMessageMock: SendMessage = () =>
  console.warn(LIBRARY_NAME + 'message' + IOS_NOT_SUPPORTED_WARNING);

let sendMessageExport: SendMessage = sendMessageMock;
if (Platform.OS !== 'ios') {
  sendMessageExport = sendMessage;
}

export { sendMessageExport as sendMessage };
