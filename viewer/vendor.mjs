import { copyFile, mkdir, writeFile } from 'node:fs/promises';
const destination = new URL('../android/app/src/main/assets/viewer/vendor/', import.meta.url);
await mkdir(destination, { recursive: true });
const files = [
  ['node_modules/three/build/three.module.js', 'three.module.js'],
  ['node_modules/three/build/three.core.js', 'three.core.js'],
  ['node_modules/three/examples/jsm/controls/OrbitControls.js', 'OrbitControls.js'],
  ['node_modules/three/examples/jsm/postprocessing/Pass.js', 'Pass.js'],
  ['node_modules/three/LICENSE', 'THREE-LICENSE.txt'],
  ['node_modules/@sparkjsdev/spark/dist/spark.module.js', 'spark.module.js'],
  ['node_modules/@sparkjsdev/spark/LICENSE', 'SPARK-LICENSE.txt'],
  ['game-controller.mjs', 'game-controller.js'],
  ['touch-gestures.mjs', 'touch-gestures.js'],
  ['app.mjs', 'app.js'],
  ['index.html', '../index.html'],
];
for (const [source, name] of files) await copyFile(new URL(source, import.meta.url), new URL(name, destination));
await writeFile(new URL('versions.json', destination), JSON.stringify({ three: '0.180.0', spark: '2.4.0' }));
console.log('Offline viewer assets ready');
