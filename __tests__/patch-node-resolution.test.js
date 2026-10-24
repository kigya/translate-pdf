const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const patchSource = path.resolve(__dirname, '../scripts/patch-node-resolution.cjs');
const scannerSource = 'output = await scanner.scanModules(roots, { timeout: 5000 });';
const scannerBuild = 'output = await scanner.scanModules(roots, {\n            timeout: 5000\n        });';

function write(root, relativePath, content) {
  const file = path.join(root, 'node_modules', relativePath);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
  return file;
}

function withFixture(test) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'folivela-postinstall-'));
  try {
    const script = path.join(root, 'scripts', 'patch-node-resolution.cjs');
    fs.mkdirSync(path.dirname(script), { recursive: true });
    fs.copyFileSync(patchSource, script);
    write(root, 'expo-modules-autolinking/package.json', JSON.stringify({ version: '58.0.14' }));
    const source = write(root, 'expo-modules-autolinking/src/platforms/apple/moduleScanner.ts', scannerSource);
    const build = write(root, 'expo-modules-autolinking/build/platforms/apple/moduleScanner.js', scannerBuild);
    write(root, 'expo-dev-client/package.json', JSON.stringify({ version: '58.0.14' }));
    const devClient = write(root, 'expo-dev-client/android/build.gradle', "dependencies {\n  androidTestImplementation 'org.webkit:android-jsc:+'\n}\n");
    const reanimated = write(root, 'react-native-reanimated/android/build.gradle.kts', 'commandLine("node", "resolve.js")\n');
    const worklets = write(root, 'react-native-worklets/android/build.gradle.kts', 'commandLine("node", "resolve.js")\n');
    write(root, 'expo-modules-autolinking/android/expo-gradle-plugin/Fixture.kt', 'fun fixture() = "node"\n');
    write(root, '@react-native/gradle-plugin/Fixture.kt', 'fun fixture() = "node"\n');
    write(root, '@shopify/react-native-skia/android/Fixture.kt', 'fun fixture() = "node"\n');
    test({ root, script, source, build, reanimated, worklets, devClient });
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function run(script) {
  return spawnSync(process.execPath, [script], { encoding: 'utf8' });
}

describe('postinstall Expo Apple module scanner patch', () => {
  it('raises both Expo 58.0.14 scan timeouts and remains byte-idempotent', () => {
    withFixture(({ script, source, build, reanimated, worklets }) => {
      const first = run(script);
      expect(first.status).toBe(0);
      expect(fs.readFileSync(source, 'utf8')).toContain('timeout: 30000');
      expect(fs.readFileSync(build, 'utf8')).toContain('timeout: 30000');
      expect(fs.readFileSync(reanimated, 'utf8')).toContain('commandLine(System.getenv("NODE_BINARY") ?: "node",');
      expect(fs.readFileSync(worklets, 'utf8')).toContain('commandLine(System.getenv("NODE_BINARY") ?: "node",');

      const afterFirstRun = [source, build, reanimated, worklets].map(file => fs.readFileSync(file));
      const second = run(script);
      expect(second.status).toBe(0);
      [source, build, reanimated, worklets].forEach((file, index) => {
        expect(fs.readFileSync(file)).toEqual(afterFirstRun[index]);
      });
    });
  });

  it('rejects an unexpected autolinking version before mutating any dependency file', () => {
    withFixture(({ root, script, source, reanimated }) => {
      write(root, 'expo-modules-autolinking/package.json', JSON.stringify({ version: '58.0.15' }));
      const originalSource = fs.readFileSync(source);
      const originalReanimated = fs.readFileSync(reanimated);
      const result = run(script);
      expect(result.status).not.toBe(0);
      expect(result.stderr).toContain('expected expo-modules-autolinking 58.0.14');
      expect(fs.readFileSync(source)).toEqual(originalSource);
      expect(fs.readFileSync(reanimated)).toEqual(originalReanimated);
    });
  });

  it('fails closed and leaves both scanner files unchanged when either upstream anchor drifts', () => {
    withFixture(({ script, source, build }) => {
      fs.writeFileSync(build, scannerBuild.replace('timeout: 5000', 'timeout: 7000'));
      const originalSource = fs.readFileSync(source);
      const originalBuild = fs.readFileSync(build);
      const result = run(script);
      expect(result.status).not.toBe(0);
      expect(result.stderr).toContain('unexpected scan timeout source in build/platforms/apple/moduleScanner.js');
      expect(fs.readFileSync(source)).toEqual(originalSource);
      expect(fs.readFileSync(build)).toEqual(originalBuild);
    });
  });

  it('removes only the unused legacy JSC test dependency and remains idempotent', () => {
    withFixture(({ script, devClient }) => {
      expect(run(script).status).toBe(0);
      const patched = fs.readFileSync(devClient);
      expect(patched.toString()).not.toContain("androidTestImplementation 'org.webkit:android-jsc:+'");
      expect(patched.toString()).toContain('No Android tests ship in this package');
      expect(run(script).status).toBe(0);
      expect(fs.readFileSync(devClient)).toEqual(patched);
    });
  });

  it('rejects changed Expo test sources, versions or dependencies before editing its Gradle file', () => {
    for (const mutation of ['sources', 'version', 'dependency']) {
      withFixture(({ root, script, devClient }) => {
        if (mutation === 'sources') write(root, 'expo-dev-client/android/src/androidTest/NewTest.kt', 'class NewTest {}');
        if (mutation === 'version') write(root, 'expo-dev-client/package.json', JSON.stringify({ version: '58.0.15' }));
        if (mutation === 'dependency') fs.writeFileSync(devClient, 'dependencies {}');
        const before = fs.readFileSync(devClient);
        const result = run(script);
        expect(result.status).not.toBe(0);
        expect(result.stderr).toContain('Review Expo dev-client test dependency patch');
        expect(fs.readFileSync(devClient)).toEqual(before);
      });
    }
  });
});
