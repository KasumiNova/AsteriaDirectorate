import { useEffect, useState } from 'react'
import { shipSources } from '../data/ships'
import type { ImageAssets } from './shared'

const imageCache = new Map<string, Promise<HTMLImageElement>>()

function loadImage(source: string): Promise<HTMLImageElement> {
  const cached = imageCache.get(source)
  if (cached) return cached

  const request = new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image()
    image.decoding = 'async'
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error(`图片加载失败：${source}`))
    image.src = source
  })
  imageCache.set(source, request)
  request.catch(() => imageCache.delete(source))
  return request
}

const sources = [
  '/assets/backgrounds/deep-space-hero.png',
  '/assets/backgrounds/deep-space-atlas.png',
  '/assets/crest.png',
  ...new Set(shipSources),
]

export function usePosterAssets(): {
  assets: ImageAssets
  errors: string[]
  pending: number
} {
  const [assets, setAssets] = useState<Map<string, HTMLImageElement>>(new Map())
  const [errors, setErrors] = useState<string[]>([])
  const [pending, setPending] = useState(sources.length)

  useEffect(() => {
    let active = true
    sources.forEach((source) => {
      loadImage(source)
        .then((image) => {
          if (!active) return
          setAssets((current) => new Map(current).set(source, image))
        })
        .catch((error: unknown) => {
          console.error(error)
          if (!active) return
          const message = error instanceof Error ? error.message : String(error)
          setErrors((current) => current.includes(message) ? current : [...current, message])
        })
        .finally(() => {
          if (active) setPending((current) => current - 1)
        })
    })
    return () => {
      active = false
    }
  }, [])

  return { assets, errors, pending }
}
