#!/usr/bin/env node
/*
 * Capacitor 2 registers native plugins by hand in MainActivity.java:
 *
 *   this.init(savedInstanceState, new ArrayList<Class<? extends Plugin>>() {{
 *     add(PluginSecurePlugin.class);        // <-- this line
 *   }});
 *
 * `cap sync` never touches MainActivity.java (Capacitor 2's android update only writes
 * capacitor.settings.gradle, capacitor.build.gradle, the cordova plugins build.gradle and
 * cordova.variables.gradle), and Capacitor 2.5 has no `capacitor:sync:*` hook either, so
 * that line silently stays missing and every call from the web layer is rejected with
 * "PluginSecure does not have web implementation.".
 *
 * This script performs that edit idempotently. Run it after `cap sync`:
 *
 *   node node_modules/@al/pluginsecure/scripts/ensure-android.js [platformDir]
 *
 * platformDir defaults to "android" and is resolved against the current working directory,
 * so run it from the folder that holds capacitor.config.json (for Quasar Capacitor mode
 * that is the project's src-capacitor folder).
 */
const fs = require('fs')
const path = require('path')

const PLUGIN_CLASS = 'PluginSecurePlugin'
const PLUGIN_IMPORT = 'com.al.pluginsecure.PluginSecurePlugin'
const INIT_MARKER = 'new ArrayList<Class<? extends Plugin>>() {{'

function log (message) {
  console.log(`[pluginsecure] ${message}`)
}

function fail (message) {
  console.error(`[pluginsecure] ${message}`)
  process.exit(1)
}

function findMainActivity (javaDir) {
  if (!fs.existsSync(javaDir)) {
    return null
  }
  const stack = [javaDir]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(full)
      } else if (entry.name === 'MainActivity.java') {
        return full
      }
    }
  }
  return null
}

const platformDir = path.resolve(process.argv[2] || 'android')
const configPath = path.join(process.cwd(), 'capacitor.config.json')

if (!fs.existsSync(configPath)) {
  log(`warning: ${configPath} not found, are you running this from the folder that holds capacitor.config.json?`)
}

const settingsPath = path.join(platformDir, 'capacitor.settings.gradle')
if (fs.existsSync(settingsPath)) {
  const settings = fs.readFileSync(settingsPath, 'utf-8')
  if (!settings.includes(':al-pluginsecure')) {
    log('warning: capacitor.settings.gradle does not include :al-pluginsecure yet, run `npx cap sync android` first')
  }
} else {
  log(`warning: ${settingsPath} not found, run \`npx cap sync android\` first`)
}

const activityPath = findMainActivity(path.join(platformDir, 'app', 'src', 'main', 'java'))

if (!activityPath) {
  fail(`could not find MainActivity.java under ${path.join(platformDir, 'app', 'src', 'main', 'java')}`)
}

let source = fs.readFileSync(activityPath, 'utf-8')

if (source.includes(`add(${PLUGIN_CLASS}.class)`)) {
  log(`already registered in ${activityPath}`)
  process.exit(0)
}

const initIndex = source.indexOf(INIT_MARKER)
if (initIndex === -1) {
  fail(`could not find the plugin registration block (\`${INIT_MARKER}\`) in ${activityPath}. ` +
    `Add \`add(${PLUGIN_CLASS}.class);\` manually.`)
}

// Insert the registration right after the {{ that opens the ArrayList initializer,
// keeping whatever whitespace the surrounding lines use.
const lineStart = source.lastIndexOf('\n', initIndex) + 1
const indent = source.slice(lineStart, initIndex).match(/^\s*/)[0] + '  '
source = source.slice(0, initIndex + INIT_MARKER.length) +
  `\n${indent}add(${PLUGIN_CLASS}.class);` +
  source.slice(initIndex + INIT_MARKER.length)

// Insert the import alphabetically among the existing com.* imports, otherwise right
// after the package declaration.
if (!source.includes(`import ${PLUGIN_IMPORT};`)) {
  const imports = source.match(/^import .+;$/gm) || []
  const before = imports.find(line => line > `import ${PLUGIN_IMPORT};`)
  if (before) {
    source = source.replace(before, `import ${PLUGIN_IMPORT};\n${before}`)
  } else if (imports.length > 0) {
    const last = imports[imports.length - 1]
    source = source.replace(last, `${last}\n\nimport ${PLUGIN_IMPORT};`)
  } else {
    source = source.replace(/^(package .+;)$/m, `$1\n\nimport ${PLUGIN_IMPORT};`)
  }
}

fs.writeFileSync(activityPath, source)
log(`registered ${PLUGIN_CLASS} in ${activityPath}`)
