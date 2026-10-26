import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { SceneGestures } from '../touch-gestures.mjs';
import { GameController, SceneTransform } from '../game-controller.mjs';
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-6,`${a} != ${b}`);
function setup(){const events=[];const g=new SceneGestures({look:(...v)=>events.push(['look',...v]),transform:(...v)=>events.push(['transform',...v]),pan:(...v)=>events.push(['pan',...v]),cancelLook:()=>{}});return {g,events}}
test('single finger changes view and stops cleanly on release',()=>{
  const {g,events}=setup();g.down(1,100,100);g.move(1,120,90);g.flush();g.up(1);g.move(1,200,200);g.flush();
  assert.deepEqual(events[0],['look',20,-10]);assert.equal(g.size,0);
});
test('two-finger batch produces one pinch without spurious twist or centroid drift',()=>{
  const {g,events}=setup();g.down(1,100,100);g.down(2,300,100);g.move(1,150,100);g.move(2,250,100);g.flush();
  assert.deepEqual(events,[['transform',0,0,0,.5]]);
});
test('three-finger drag pans once with no rotation/scale callback',()=>{
  const {g,events}=setup();g.down(1,100,100);g.down(2,200,100);g.down(3,150,200);
  g.move(1,130,120);g.move(2,230,120);g.move(3,180,220);g.flush();
  assert.deepEqual(events,[['pan',30,20]]);
});
test('lifting fingers after three-finger pan never turns into pinch or look',()=>{
  const {g,events}=setup();g.down(1,100,100);g.down(2,200,100);g.down(3,150,200);g.up(3);g.move(1,120,120);g.flush();g.up(2);g.move(1,150,150);g.flush();
  assert.equal(events.filter(e=>e[0]!=='pan').length,0);g.up(1);g.down(4,100,100);g.move(4,110,100);g.flush();assert.deepEqual(events.at(-1),['look',10,0]);
});
test('joystick travel controls speed continuously with precise low-speed control and saturation',()=>{
  const speeds=[.05,.2,.5,1,2].map(travel=>{const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.setStick(0,-travel);for(let i=0;i<60;i++)c.update(1/60);return camera.position.length()});
  near(speeds[0],0);assert.ok(speeds[1]>0&&speeds[1]<speeds[2]&&speeds[2]<speeds[3]);near(speeds[3],speeds[4]);assert.ok(speeds[1]<speeds[3]*.1);
});
test('three-finger pan follows screen right/down without changing camera, scale or rotation',()=>{
  const group=new THREE.Group(),mesh=new THREE.Object3D(),center=new THREE.Vector3(0,0,-5),scene=new SceneTransform(group,mesh,center),camera=new THREE.PerspectiveCamera(60);
  camera.rotateZ(.4);scene.setScale(.5);const q=group.quaternion.clone();scene.panGesture(60,30,camera,600);
  const screenDelta=group.position.clone().sub(center).applyQuaternion(camera.quaternion.clone().invert());
  near(screenDelta.x,Math.tan(Math.PI/6));near(screenDelta.y,-Math.tan(Math.PI/6)/2);near(screenDelta.z,0);
  near(camera.position.length(),0);near(group.quaternion.angleTo(q),0);near(scene.scale,.5);
  scene.reset();near(group.position.distanceTo(center),0);near(scene.scale,1);
});
test('touch-only controller has no drift during prolonged idle',()=>{
  const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);for(let i=0;i<7200;i++)c.update(1/60);near(camera.position.length(),0);near(camera.quaternion.w,1);
});
