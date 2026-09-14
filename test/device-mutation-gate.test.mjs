import { test } from "node:test"
import assert from "node:assert/strict"
import {
  DEVICE_MUTATION_REVOKED,
  DeviceMutationGate,
  runAuthorizedDeviceMutation,
} from "../src/device-mutation-gate.js"

test("drain waits for a registered device mutation", async () => {
  const gate = new DeviceMutationGate()
  let release
  const blocked = new Promise((resolve) => { release = resolve })
  let completed = false

  const mutation = gate.run("device-1", async () => {
    await blocked
    completed = true
    return "done"
  })
  let drained = false
  const drain = gate.drain("device-1").then(() => { drained = true })

  await Promise.resolve()
  assert.equal(drained, false)
  release()
  assert.equal(await mutation, "done")
  await drain
  assert.equal(completed, true)
  assert.equal(drained, true)
})

test("one device does not block another device's revocation", async () => {
  const gate = new DeviceMutationGate()
  let release
  const blocked = new Promise((resolve) => { release = resolve })
  const mutation = gate.run("device-1", () => blocked)

  await gate.drain("device-2")
  release()
  await mutation
})

test("failed mutations are drained without masking their original error", async () => {
  const gate = new DeviceMutationGate()
  const mutation = gate.run("device-1", async () => {
    throw new Error("workspace create failed")
  })

  await assert.rejects(mutation, /workspace create failed/)
  await gate.drain("device-1")
})

test("revocation drains admitted work and rejects later mutations", async () => {
  const gate = new DeviceMutationGate()
  let authorized = true
  let mutated = false
  let lateMutated = false
  let release
  const blocked = new Promise((resolve) => { release = resolve })

  const admitted = runAuthorizedDeviceMutation(
    gate,
    "device-1",
    () => authorized,
    () => {
      return blocked.then(() => {
        mutated = true
        return "mutated"
      })
    },
  )
  await Promise.resolve()

  authorized = false
  let drained = false
  const drain = gate.drain("device-1").then(() => { drained = true })
  await Promise.resolve()
  assert.equal(drained, false)

  release()
  assert.equal(await admitted, "mutated")
  await drain
  assert.equal(mutated, true)

  const result = await runAuthorizedDeviceMutation(
    gate,
    "device-1",
    () => authorized,
    () => {
      lateMutated = true
      return "mutated"
    },
  )

  assert.equal(result, DEVICE_MUTATION_REVOKED)
  assert.equal(lateMutated, false)
  await gate.drain("device-1")
})
