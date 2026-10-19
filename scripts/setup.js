#!/usr/bin/env node
/*
 * @al/pluginsecure native setup (Capacitor 3).
 *
 * Run this once from the Capacitor project (Quasar: `src-capacitor`), after
 * `npm i @al/pluginsecure`:
 *
 *     cd src-capacitor
 *     npx pluginsecure-setup
 *
 * It upgrades the project to Capacitor 3 when it is still on the @quasar/app
 * default (Capacitor 2), adds the platforms, runs `cap sync` and writes the
 * Quasar boot file. Safe to re-run (idempotent).
 */
'use strict'

const {
  findCapacitorDir,
  findQuasarRoot,
  ensureCapacitor3,
  syncNative,
  installBootFile,
  log,
  warn
} = require('./auto-install.js')

function main () {
  const cwd = process.env.INIT_CWD || process.cwd()
  const capDir = findCapacitorDir(cwd)
  const quasarRoot = findQuasarRoot(cwd)

  if (!capDir && !quasarRoot) {
    warn('no Capacitor/Quasar project found. Run this from your src-capacitor folder.')
    process.exit(1)
  }

  if (capDir) {
    log(`Capacitor project: ${capDir}`)
    ensureCapacitor3(capDir)
    syncNative(capDir)
  }

  if (quasarRoot) {
    log(`Quasar project root: ${quasarRoot}`)
    installBootFile(quasarRoot)
  }

  log('done')
}

try {
  main()
} catch (error) {
  warn(`setup failed: ${error.message}`)
  process.exit(1)
}
