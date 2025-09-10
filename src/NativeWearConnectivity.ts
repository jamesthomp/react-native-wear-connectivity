import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Messages
export type Payload = {};
export type ErrorCallback = (err: string) => void;

export type SendMessage = (message: Payload, errCb: ErrorCallback) => void;

export type SendFile = (file: string, metadata: unknown) => Promise<any>;

export interface Spec extends TurboModule {
  sendMessage: SendMessage;
  sendFile: SendFile;
  isConnected: () => Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('WearConnectivity');
