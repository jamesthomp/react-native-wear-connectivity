// Subscriptions
export type EventType =
  | 'message'
  | 'file-received'
  | 'file-transfer'
  | 'reachability';
type UnsubscribeFn = Function;
type CallbackFunction = (event: any) => void;
export type AddListener = (
  event: EventType,
  cb: CallbackFunction
) => UnsubscribeFn;

export type WatchEvents = {
  addListener: AddListener;
  on: AddListener;
};
