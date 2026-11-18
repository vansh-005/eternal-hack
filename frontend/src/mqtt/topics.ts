export function cardsTopic(outletId: string): string {
  return `nocook/outlets/${outletId}/cards`
}

export function callsTopic(outletId: string): string {
  return `nocook/outlets/${outletId}/calls`
}

export const CONTROL_TOPIC = 'nocook/control'
