export interface DeviceEntity {
  "@deviceType": string;
  deviceId: string;
  deviceClass: string;
  label: string;
  available: boolean;
  lastUpdated: string;
  tenancyId: string;
  providerId: string;
  location: string | null;
  [key: string]: unknown;
}

export interface StateChangeEvent {
  before: DeviceEntity | null;
  after: DeviceEntity;
  changedCapabilities: string[];
  occurredAt: string;
  providerId: string;
}

export interface StateChangePayload {
  "@type": "STATE_CHANGE";
  tenancyId: string;
  timestamp: string;
  event: StateChangeEvent;
}

export interface ReplayedStateChangePayload {
  "@type": "REPLAYED_STATE_CHANGE";
  tenancyId: string;
  timestamp: string;
  event: StateChangeEvent;
}

export interface StateSnapshotPayload {
  "@type": "STATE_SNAPSHOT";
  tenancyId: string;
  timestamp: string;
  devices: DeviceEntity[];
}

export interface ProviderStatusPayload {
  "@type": "PROVIDER_STATUS";
  tenancyId: string;
  timestamp: string;
  status: {
    providerId: string;
    previousStatus: "CONNECTED" | "CONNECTING" | "DISCONNECTED";
    currentStatus: "CONNECTED" | "CONNECTING" | "DISCONNECTED";
  };
}

export interface CommandPayload {
  "@type": "COMMAND";
  tenancyId: string;
  timestamp: string;
  correlationId: string;
  command: {
    targetDeviceId: string;
    action: string;
    parameters: Record<string, unknown>;
    dispatchedBy: string;
    correlationId: string;
  };
}

export interface CommandResponsePayload {
  "@type": "COMMAND_RESULT";
  tenancyId: string;
  timestamp: string;
  correlationId: string;
  result: "SENT" | "FAILED" | "TIMEOUT";
}

export type BridgeMessagePayload =
  | StateChangePayload
  | ReplayedStateChangePayload
  | StateSnapshotPayload
  | ProviderStatusPayload
  | CommandPayload
  | CommandResponsePayload;

export type BridgeAuditEventType =
  | "STATE_CHANGE"
  | "REPLAYED_STATE_CHANGE"
  | "STATE_SNAPSHOT"
  | "PROVIDER_STATUS_CHANGE"
  | "COMMAND_SENT"
  | "COMMAND_RESPONSE"
  | "AGENT_CONNECTED"
  | "AGENT_DISCONNECTED";

export interface AuditRecord {
  eventType: BridgeAuditEventType;
  deviceId: string | null;
  correlationId: string | null;
  payload: BridgeMessagePayload | null;
  occurredAt: string;
}
