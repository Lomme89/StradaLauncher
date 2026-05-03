const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'launcher.html'),
  'utf8'
);

function has(pattern, message) {
  assert.match(html, pattern, message);
}

has(/iconMode\s*:\s*'native'/, 'default buttons should keep native slot icons');
has(/btn\.iconMode\s*\|\|\s*buttons\[i\]\.iconMode\s*\|\|\s*'native'/, 'loadConfig should restore iconMode with native fallback');
has(/iconMode:b\.iconMode/, 'saveConfig should persist iconMode per button');
has(/id="icon-mode-native"/, 'picker should expose native icon option');
has(/id="icon-mode-app"/, 'picker should expose app icon option');
has(/function\s+setIconMode\(/, 'picker should update icon mode without changing app');
has(/function\s+getAppIconForButton\(/, 'render should have helper for assigned app icon');
has(/btn\.iconMode\s*===\s*'app'[\s\S]*getAppIconForButton/, 'render should use app icon only when iconMode is app');
has(/buttons\[pickerSlotIndex\]\.iconMode\s*=\s*getSelectedIconMode\(\)/, 'assignApp should save selected icon mode');
has(/b\.iconMode\s*=\s*'native'/, 'clearSlot should reset icon mode to native');
