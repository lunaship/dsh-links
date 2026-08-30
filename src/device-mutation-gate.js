/**
 * Tracks device-authorized mutations that may outlive their HTTP request.
 * Revocation drains this gate before it acknowledges success, so callers never
 * observe a successful revocation followed by a late mutation from that device.
 */
export class DeviceMutationGate {
  constructor() {
    this.inflight = new Map()
  }

  run(deviceId, operation) {
    const key = String(deviceId ?? "")
    let operations = this.inflight.get(key)
    if (!operations) {
      operations = new Set()
      this.inflight.set(key, operations)
    }

    // Register before invoking operation. This closes the authorization-to-start
    // gap because JavaScript cannot interleave a revocation in this synchronous
    // section.
    const pending = Promise.resolve().then(operation)
    operations.add(pending)
    const done = () => {
      operations.delete(pending)
      if (operations.size === 0) this.inflight.delete(key)
    }
    pending.then(done, done)
    return pending
  }

  async drain(deviceId) {
    const key = String(deviceId ?? "")
    for (;;) {
      const operations = this.inflight.get(key)
      if (!operations || operations.size === 0) return
      await Promise.allSettled([...operations])
    }
  }
}
