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
