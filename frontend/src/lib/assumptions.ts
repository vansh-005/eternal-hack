import type { AssumptionNode } from '../api/opsTypes'

export interface AssumptionRow {
  path: string
  value: string
  assumed: boolean
}

function isLeaf(node: AssumptionNode): node is { value: unknown; assumed: boolean } {
  return 'value' in node && 'assumed' in node && typeof (node as { assumed: unknown }).assumed === 'boolean'
}

/** Flatten the nested assumptions tree; every {value, assumed} leaf becomes one row. */
export function flattenAssumptions(node: AssumptionNode, prefix = ''): AssumptionRow[] {
  if (isLeaf(node)) {
    const value = Array.isArray(node.value) ? node.value.join(', ') : String(node.value)
    return [{ path: prefix, value, assumed: node.assumed }]
  }
  const rows: AssumptionRow[] = []
  for (const [key, child] of Object.entries(node)) {
    rows.push(...flattenAssumptions(child, prefix === '' ? key : `${prefix}.${key}`))
  }
  return rows
}
