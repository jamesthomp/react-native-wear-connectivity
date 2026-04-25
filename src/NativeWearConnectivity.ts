import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Messages
export type Payload = {};
export type ErrorCallback = (err: string) => void;
export type SendMessage = (message: Payload, errCb: ErrorCallback) => void;
export type FileTransferOptions = {
  readonly compress?: boolean;
};
export type SendFile = (
  file: string,
  options: FileTransferOptions
) => Promise<string>;
export type GetTransferFiles = () => Promise<
  Array<{ uri: string; fileName: string; url: string }>
>;
export type DeleteFileTransfer = (uri: string) => Promise<void>;

export interface Spec extends TurboModule {
  sendMessage: SendMessage;
  sendFile: SendFile;
  isConnected: () => Promise<boolean>;
  getTransferFiles: GetTransferFiles;
  deleteFileTransfer: DeleteFileTransfer;
}

export default TurboModuleRegistry.getEnforcing<Spec>('WearConnectivity');
