import { useEffect, useState } from 'react'

let rubikRequest: Promise<void> | null = null

function loadRubik(): Promise<void> {
  if (rubikRequest) return rubikRequest
  rubikRequest = (async () => {
    const faces = await Promise.all([400, 500, 600].map((weight) => (
      document.fonts.load(`${weight} 16px "Rubik"`)
    )))
    if (faces.some((items) => items.length === 0)) {
      throw new Error('Rubik 字体加载失败')
    }
    await document.fonts.ready
    if (!document.fonts.check('500 16px "Rubik"')) {
      throw new Error('Rubik 字体未能注册到文档')
    }
  })().catch((error: unknown) => {
    rubikRequest = null
    throw error
  })
  return rubikRequest
}

export function useRubikFont(): { ready: boolean; error: string } {
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    loadRubik()
      .then(() => {
        if (active) setReady(true)
      })
      .catch((reason: unknown) => {
        console.error(reason)
        if (active) setError(reason instanceof Error ? reason.message : String(reason))
      })
    return () => {
      active = false
    }
  }, [])

  return { ready, error }
}
