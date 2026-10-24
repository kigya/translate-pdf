const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const {expo} = require('../app.config');

const nativePlist = readFileSync(resolve(__dirname, '../ios/TranslatePDF/Info.plist'), 'utf8');
function plistStrings(key) {
  const body = nativePlist.match(new RegExp(`<key>${key}</key>\\s*<array>([\\s\\S]*?)</array>`));
  expect(body).not.toBeNull();
  return [...body[1].matchAll(/<string>([^<]+)<\/string>/g)].map(match => match[1]);
}

test('native and Expo builds retain the background-processing mode and permitted dynamic task prefix', () => {
  const identifier = `${expo.ios.bundleIdentifier}.continuedProcessing.*`;
  expect(expo.ios.infoPlist.UIBackgroundModes).toEqual(['processing']);
  expect(expo.ios.infoPlist.BGTaskSchedulerPermittedIdentifiers).toEqual([identifier]);
  expect(plistStrings('UIBackgroundModes')).toEqual(['processing']);
  expect(plistStrings('BGTaskSchedulerPermittedIdentifiers')
    .map(value => value.replace('$(PRODUCT_BUNDLE_IDENTIFIER)', expo.ios.bundleIdentifier))).toEqual([identifier]);
});
