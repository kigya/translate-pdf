// Preserve upstream version checks while honoring explicit Node selection in GUI-launched builds.
const fs = require('node:fs');
const path = require('node:path');

// Expo 58's native-module scan can exceed its default 5 s on this project during Xcode builds.
// Patch both the TypeScript source and the shipped JS entrypoint, but fail closed on upstream drift.
function patchAppleModuleScannerTimeout() {
  const packageRoot = path.join(__dirname, '..', 'node_modules', 'expo-modules-autolinking');
  const packageJson = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));
  if (packageJson.version !== '58.0.14') {
    throw new Error(`Review Expo module scanner patch: expected expo-modules-autolinking 58.0.14, found ${packageJson.version}.`);
  }

  const replacements = [
    {
      file: path.join(packageRoot, 'src/platforms/apple/moduleScanner.ts'),
      before: 'output = await scanner.scanModules(roots, { timeout: 5000 });',
      after: 'output = await scanner.scanModules(roots, { timeout: 30000 });',
    },
    {
      file: path.join(packageRoot, 'build/platforms/apple/moduleScanner.js'),
      before: 'output = await scanner.scanModules(roots, {\n            timeout: 5000\n        });',
      after: 'output = await scanner.scanModules(roots, {\n            timeout: 30000\n        });',
    },
  ];
  const pendingWrites = [];
  for (const replacement of replacements) {
    const source = fs.readFileSync(replacement.file, 'utf8');
    const beforeCount = source.split(replacement.before).length - 1;
    const afterCount = source.split(replacement.after).length - 1;
    if (beforeCount === 1 && afterCount === 0) {
      pendingWrites.push([replacement.file, source.replace(replacement.before, replacement.after)]);
    } else if (beforeCount !== 0 || afterCount !== 1) {
      throw new Error(`Review Expo module scanner patch: unexpected scan timeout source in ${path.relative(packageRoot, replacement.file)}.`);
    }
  }
  for (const [file, updated] of pendingWrites) fs.writeFileSync(file, updated);
}

patchAppleModuleScannerTimeout();

// The published Expo package has no Android instrumentation test sources, but
// declares an obsolete JSC test dependency that prevents AGP lint model resolution.
// Keep lint enabled and remove only that unused test dependency from this version.
function patchUnusedDevClientTestDependency() {
  const root = path.join(__dirname, '..', 'node_modules', 'expo-dev-client');
  const version = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).version;
  if (version !== '58.0.14' || fs.existsSync(path.join(root, 'android', 'src', 'androidTest'))) {
    throw new Error('Review Expo dev-client test dependency patch: expected 58.0.14 without Android test sources.');
  }
  const file = path.join(root, 'android', 'build.gradle');
  const source = fs.readFileSync(file, 'utf8');
  const before = "  androidTestImplementation 'org.webkit:android-jsc:+'";
  const after = '  // No Android tests ship in this package; unused legacy JSC dependency removed.';
  const beforeCount = source.split(before).length - 1;
  const afterCount = source.split(after).length - 1;
  if (beforeCount === 1 && afterCount === 0) fs.writeFileSync(file, source.replace(before, after));
  else if (beforeCount !== 0 || afterCount !== 1) throw new Error('Review Expo dev-client test dependency patch: upstream dependency changed.');
}
patchUnusedDevClientTestDependency();

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
