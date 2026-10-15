import { createContext, useContext } from 'react'
import type { PlatformUser } from './api'

export const CurrentUserContext = createContext<PlatformUser | null>(null)
export function useCurrentUser() {
  const user = useContext(CurrentUserContext)
  if (!user) throw new Error('当前用户尚未登录')
  return user
}
