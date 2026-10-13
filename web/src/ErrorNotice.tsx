import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

const eventName = 'febridge:error'
let lastMessage = ''
let lastTime = 0

function notifyError(message: string) {
  const now = Date.now()
  if (!message || (message === lastMessage && now - lastTime < 1500)) return
  lastMessage = message
  lastTime = now
  window.dispatchEvent(new CustomEvent<string>(eventName, { detail: message }))
}

export function useErrorState() {
  const [error, updateError] = useState('')
  const setError = useCallback((message: string) => {
    updateError(message)
    if (message) notifyError(message)
  }, [])
  return [error, setError] as const
}

export default function ErrorNotice() {
  const [notice, setNotice] = useState<{ message: string; id: number } | null>(null)
  const element = useRef<HTMLDivElement>(null)
  useEffect(() => {
    function receive(event: Event) {
      setNotice({ message: (event as CustomEvent<string>).detail, id: Date.now() })
    }
    window.addEventListener(eventName, receive)
    return () => window.removeEventListener(eventName, receive)
  }, [])
  useEffect(() => {
    if (!notice) return
    const popover = element.current
    popover?.showPopover()
    return () => { if (popover?.matches(':popover-open')) popover.hidePopover() }
  }, [notice])
  if (!notice) return null
  // Attach to an open modal so the close button remains interactive.
  const host = document.querySelector('dialog[open]') || document.body
  return createPortal(<div ref={element} popover="manual" className="error-notice" role="alert">
    <span className="error-notice-icon" aria-hidden="true">!</span>
    <div><strong>操作未完成</strong><p>{notice.message}</p></div>
    <button type="button" aria-label="关闭错误提示" onClick={() => setNotice(null)}>✕</button>
  </div>, host)
}
