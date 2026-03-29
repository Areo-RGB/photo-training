import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

function run(command, args) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.error) {
    throw result.error;
  }
  return result;
}

function fail(message, detail = '') {
  console.error(message);
  if (detail.trim().length > 0) {
    console.error(detail.trim());
  }
  process.exit(1);
}

const flavorTargets = [
  {
    id: 'topazSingle',
    modelMatchers: ['23021RAA2Y', 'topaz'],
    apkCandidates: [
      resolve(
        process.cwd(),
        'android',
        'app',
        'build',
        'outputs',
        'apk',
        'topazSingle',
        'debug',
        'app-topazSingle-debug.apk',
      ),
    ],
    appId: 'sync.sprint.topaz.single',
  },
  {
    id: 'emlL29Single',
    modelMatchers: ['EML_L29', 'EML-L29'],
    apkCandidates: [
      resolve(
        process.cwd(),
        'android',
        'app',
        'build',
        'outputs',
        'apk',
        'emlL29Single',
        'debug',
        'app-emlL29Single-debug.apk',
      ),
    ],
    appId: 'sync.sprint.emll29.single',
  },
  {
    id: 'xiaomiPadDisplay',
    modelMatchers: ['2410CRP4CG', 'Xiaomi_Pad_7', 'Pad_7'],
    apkCandidates: [
      resolve(
        process.cwd(),
        'android',
        'app',
        'build',
        'outputs',
        'apk',
        'xiaomiPadDisplay',
        'debug',
        'app-xiaomiPadDisplay-debug.apk',
      ),
    ],
    appId: 'sync.sprint.xiaomi.display',
  },
];

for (const target of flavorTargets) {
  const apkPath = target.apkCandidates.find((path) => existsSync(path));
  if (!apkPath) {
    fail(
      `APK for ${target.id} not found. Expected one of:\n- ${target.apkCandidates.join('\n- ')}\nRun "npm run build:flavor:apks" first.`,
    );
  }
  target.apkPath = apkPath;
}

const devicesResult = run('adb', ['devices', '-l']);
if (devicesResult.status !== 0) {
  fail('Failed to run "adb devices -l". Ensure adb is installed and in PATH.', devicesResult.stderr);
}

const lines = devicesResult.stdout
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => line.length > 0 && !line.startsWith('List of devices attached'));

const readyDevices = [];
for (const line of lines) {
  const parts = line.split(/\s+/);
  if (parts.length < 2 || parts[1] !== 'device') {
    continue;
  }
  const serial = parts[0];
  const modelPart = parts.find((part) => part.startsWith('model:')) ?? '';
  const model = modelPart.replace(/^model:/, '');
  readyDevices.push({ serial, model, raw: line });
}

if (readyDevices.length === 0) {
  fail('No ready Android devices found. Connect devices and run "adb devices -l".');
}

function targetForModel(model) {
  return flavorTargets.find((target) => target.modelMatchers.some((matcher) => model.includes(matcher)));
}

let installs = 0;
let skipped = 0;
let failed = 0;

for (const device of readyDevices) {
  const target = targetForModel(device.model);
  if (!target) {
    skipped += 1;
    console.log(`Skipping ${device.serial} (${device.model || 'unknown model'}): no flavor mapping.`);
    continue;
  }

  console.log(`Installing ${target.id} on ${device.serial} (${device.model})...`);
  const installResult = run('adb', ['-s', device.serial, 'install', '-r', target.apkPath]);
  const installOutput = `${installResult.stdout}\n${installResult.stderr}`.trim();
  if (installResult.status !== 0 || !installOutput.includes('Success')) {
    failed += 1;
    console.error(`Install failed on ${device.serial}.`);
    if (installOutput.length > 0) {
      console.error(installOutput);
    }
    continue;
  }

  const launchResult = run('adb', [
    '-s',
    device.serial,
    'shell',
    'monkey',
    '-p',
    target.appId,
    '-c',
    'android.intent.category.LAUNCHER',
    '1',
  ]);
  const launchOutput = `${launchResult.stdout}\n${launchResult.stderr}`.trim();
  const launchSucceeded =
    launchResult.status === 0 &&
    !launchOutput.includes('No activities found') &&
    !launchOutput.includes('Error');
  if (!launchSucceeded) {
    failed += 1;
    console.error(`Launch failed on ${device.serial}.`);
    if (launchOutput.length > 0) {
      console.error(launchOutput);
    }
    continue;
  }

  installs += 1;
  console.log(`Install + launch success on ${device.serial}.`);
}

if (failed > 0) {
  fail(`Completed with ${failed} failure(s). Successful installs: ${installs}, skipped: ${skipped}.`);
}

console.log(`Done. Successful installs: ${installs}, skipped: ${skipped}.`);
