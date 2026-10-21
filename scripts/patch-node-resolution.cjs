// Preserve upstream version checks while honoring explicit Node selection in GUI-launched builds.
const fs = require('node:fs');
const path = require('node:path');
for (const name of ['react-native-reanimated', 'react-native-worklets']) {
  const file = path.join(__dirname, '..', 'node_modules', name, 'android', 'build.gradle.kts');
  const source = fs.readFileSync(file, 'utf8');
  const before = 'commandLine("node",';
  const after = 'commandLine(System.getenv("NODE_BINARY") ?: "node",';
  if (!source.includes(before) && !source.includes(after)) {
    throw new Error(`Review Node resolution in ${name}: upstream build script changed.`);
  }
  fs.writeFileSync(file, source.split(before).join(after));
}

// Expo settings also run before project Exec tasks can be configured.
const expoPlugin = path.join(__dirname, '..', 'node_modules/expo-modules-autolinking/android/expo-gradle-plugin');
function patchExpo(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) { if (entry.name !== 'build' && entry.name !== '.gradle' && entry.name !== 'node_modules') patchExpo(file); continue; }
    if (!/\.(kt|gradle|gradle\.kts)$/.test(file)) continue;
    const source = fs.readFileSync(file, 'utf8');
    const updated = source.replace(/commandLine\("node",/g, 'commandLine(System.getenv("NODE_BINARY") ?: "node",')
      .replace(/^(\s*)"node",$/gm, '$1(System.getenv("NODE_BINARY") ?: "node"),')
      .replace(/\["node"\]/g, '[(System.getenv("NODE_BINARY") ?: "node")]')
      .replace(/\["node",/g, '[(System.getenv("NODE_BINARY") ?: "node"),');
    if (updated !== source) fs.writeFileSync(file, updated);
  }
}
patchExpo(expoPlugin);
patchExpo(path.join(__dirname, '..', 'node_modules/@react-native/gradle-plugin'));

for (const name of fs.readdirSync(path.join(__dirname, '..', 'node_modules'))) {
  if (name === 'expo' || name.startsWith('expo-')) patchExpo(path.join(__dirname, '..', 'node_modules', name));
}

patchExpo(path.join(__dirname, '..', 'node_modules/@shopify/react-native-skia/android'));
