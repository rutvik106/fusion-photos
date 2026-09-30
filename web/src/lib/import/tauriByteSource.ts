/**
 * Native (Tauri) byte source.
 *
 * The desktop importer reads a TB-scale Takeout ZIP part straight off disk via the
 * native `read_file_range` command, so the ZIP reader and the upload pipeline never hold
 * the archive (or a whole photo) in memory. The bridge is the Tauri v2 global
 * (`window.__TAURI__.core.invoke`), enabled with `withGlobalTauri: true`.
 */
import type { ByteSource } from './byteSource'

export interface TauriBridge {
  invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T>
}

type RawInvoke = (cmd: string, args?: Record<string, unknown>) => Promise<unknown>

/** The Tauri invoke bridge when running inside the desktop shell, else null. */
export function tauriBridge(): TauriBridge | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { __TAURI__?: { core?: { invoke?: RawInvoke } } }
  const invoke = w.__TAURI__?.core?.invoke
  if (typeof invoke !== 'function') return null
  return { invoke: <T,>(cmd: string, args?: Record<string, unknown>) => invoke(cmd, args) as Promise<T> }
}

/** ByteSource over a native file path, read by range through the Rust command. */
export function tauriFileSource(bridge: TauriBridge, path: string, size: number): ByteSource {
  return {
    size,
    async read(offset, length) {
      const bytes = await bridge.invoke<number[]>('read_file_range', { path, offset, length })
      return Uint8Array.from(bytes)
    },
  }
}
