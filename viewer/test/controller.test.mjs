import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { GameController, orientCapture, SceneTransform } from '../game-controller.mjs';
const near = (a,b,t=1e-6) => assert.ok(Math.abs(a-b)<t, `${a} != ${b}`);
function run(c,seconds=1,hz=60){for(let i=0;i<seconds*hz;i++)c.update(1/hz)}
test('stick up advances along camera -Z and down retreats',()=>{
  for(const [y,sign]of[[-1,-1],[1,1]]){const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.setStick(0,y);run(c);assert.ok(camera.position.z*sign>.8);near(camera.position.y,0)}
});
test('stick right strafes without turning or vertical movement',()=>{
  const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.setStick(1,0);run(c);assert.ok(camera.position.x>.8);near(camera.position.y,0);near(camera.position.z,0);near(camera.quaternion.w,1);
});
test('forward follows the view after turning',()=>{
  const camera=new THREE.PerspectiveCamera();camera.rotateY(-Math.PI/2);const c=new GameController(camera);c.setStick(0,-1);run(c);assert.ok(camera.position.x>.8);near(camera.position.z,0);
});
test('fly mode advances in full view direction including pitch',()=>{
  const camera=new THREE.PerspectiveCamera();camera.rotateX(Math.PI/4);const c=new GameController(camera);c.setStick(0,-1);run(c);assert.ok(camera.position.y>.6&&camera.position.z<-.6);
});
test('rise and fall are independent vertical translation',()=>{
  for(const sign of[-1,1]){const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.input.rise=sign;run(c);assert.ok(camera.position.y*sign>.8);near(camera.position.z,0)}
});
test('drag right looks right and drag up looks up',()=>{
  const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.look(80,-60);run(c);const direction=camera.getWorldDirection(new THREE.Vector3());assert.ok(direction.x>0&&direction.y>0);
});
test('roll changes orientation without moving',()=>{
  const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.rotateRoll(Math.PI/2);near(camera.position.length(),0);near(new THREE.Vector3(0,1,0).applyQuaternion(camera.quaternion).x,1);
});
test('dead zone, braking and reset leave no residual input',()=>{
  const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.setStick(.05,.03);run(c);near(camera.position.length(),0);c.setStick(0,-1);run(c);c.releaseStick();run(c,2);near(c.velocity.length(),0);c.reset();run(c);near(camera.position.length(),0);
});
test('translation agrees at 30,60 and120Hz',()=>{
  const values=[30,60,120].map(hz=>{const camera=new THREE.PerspectiveCamera(),c=new GameController(camera);c.setStick(0,-1);run(c,1,hz);return camera.position.z});near(values[0],values[1]);near(values[1],values[2]);
});
test('portrait rotation and gravity are applied once',()=>{
  const camera=new THREE.PerspectiveCamera();const up=orientCapture(camera,{camera_to_world:[[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]],display_rotation_degrees:90,gravity_device:[0,9.8,0]});near(up.x,-1);near(up.y,0);near(camera.getWorldDirection(new THREE.Vector3()).z,-1);
});
test('shrinking a scene doubles model-relative displacement without double compensating camera motion',()=>{
  const group=new THREE.Group(),mesh=new THREE.Object3D(),scene=new SceneTransform(group,mesh,new THREE.Vector3(0,0,-5));
  const start=new THREE.Vector3(0,0,0),end=new THREE.Vector3(0,0,-1);
  const normal=scene.modelDisplacement(start,end).length();scene.setScale(.5);near(scene.modelDisplacement(start,end).length(),2*normal);
  scene.setScale(2);near(scene.modelDisplacement(start,end).length(),normal/2);
});
test('two-finger clockwise twist rotates the scene clockwise while leaving camera pose unchanged',()=>{
  const group=new THREE.Group(),mesh=new THREE.Object3D(),scene=new SceneTransform(group,mesh,new THREE.Vector3());
  const camera=new THREE.PerspectiveCamera();scene.rotateGesture(0,0,Math.PI/2,camera,new THREE.Vector3(0,1,0));
  near(new THREE.Vector3(0,1,0).applyQuaternion(group.quaternion).x,1);near(camera.quaternion.w,1);near(camera.position.length(),0);
});


