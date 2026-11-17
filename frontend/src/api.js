export const API = '/api'

export async function api(path, options = {}) {
  const response = await fetch(API + path, {
    headers: options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
    ...options
  })
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!response.ok) {
    const message = body && body.message ? body.message : `Request failed (${response.status})`
    throw new Error(message)
  }
  return body
}

export function money(value) {
  if (value === null || value === undefined || value === '') return ''
  const number = Number(value)
  if (Number.isNaN(number)) return value
  return number.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
