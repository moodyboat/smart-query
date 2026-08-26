import { AUTH_STORAGE_KEYS } from '../constants.js'

/**
 * SSE client built on fetch so JWT and Last-Event-ID can be sent as headers.
 * It keeps the EventSource-shaped API used by existing components and resumes
 * durable task events after transient network failures.
 */
export function createAuthenticatedEventStream(url, options = {}) {
  const listeners = new Map()
  const controller = {
    url,
    onmessage: null,
    onerror: null,
    closed: false,
    lastEventId: options.lastEventId || '',
    abortController: null,
    addEventListener(name, callback) {
      if (!listeners.has(name)) listeners.set(name, new Set())
      listeners.get(name).add(callback)
    },
    removeEventListener(name, callback) {
      listeners.get(name)?.delete(callback)
    },
    close() {
      this.closed = true
      this.abortController?.abort()
    }
  }

  const dispatch = (name, data, id) => {
    if (id) controller.lastEventId = id
    const event = { type: name, data, lastEventId: controller.lastEventId }
    listeners.get(name)?.forEach(callback => callback(event))
    if (name === 'message') controller.onmessage?.(event)
  }

  const parseBlock = block => {
    let name = 'message'
    let id = ''
    const data = []
    for (const rawLine of block.split(/\r?\n/)) {
      if (!rawLine || rawLine.startsWith(':')) continue
      const separator = rawLine.indexOf(':')
      const field = separator < 0 ? rawLine : rawLine.slice(0, separator)
      const value = separator < 0 ? '' : rawLine.slice(separator + 1).replace(/^ /, '')
      if (field === 'event') name = value
      else if (field === 'id') id = value
      else if (field === 'data') data.push(value)
    }
    if (data.length) dispatch(name, data.join('\n'), id)
  }

  const connect = async () => {
    let retry = 0
    while (!controller.closed) {
      try {
        controller.abortController = new AbortController()
        const token = localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN)
        const headers = { Accept: 'text/event-stream' }
        if (token) headers.Authorization = `Bearer ${token}`
        if (controller.lastEventId) headers['Last-Event-ID'] = controller.lastEventId
        const response = await fetch(url, {
          headers,
          signal: controller.abortController.signal,
          cache: 'no-store'
        })
        if (!response.ok || !response.body) {
          throw new Error(`SSE连接失败 (${response.status})`)
        }
        retry = 0
        const reader = response.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''
        while (!controller.closed) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const blocks = buffer.split(/\r?\n\r?\n/)
          buffer = blocks.pop() || ''
          blocks.forEach(parseBlock)
        }
        if (controller.closed) return
        throw new Error('SSE连接已关闭')
      } catch (error) {
        if (controller.closed || error.name === 'AbortError') return
        retry += 1
        error.retryCount = retry
        error.willReconnect = options.reconnect !== false && retry <= (options.maxRetries ?? 8)
        controller.onerror?.(error)
        if (!error.willReconnect) return
        await new Promise(resolve => setTimeout(resolve, Math.min(1000 * retry, 5000)))
      }
    }
  }

  connect()
  return controller
}
