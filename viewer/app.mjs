import * as THREE from 'three';
import { SparkRenderer, SplatMesh } from 'spark';
import { GameController, orientCapture, SceneTransform } from './game-controller.js';
import { SceneGestures } from './touch-gestures.js';
const $=id=>document.getElementById(id);
function report(data){try{window.RoomScan?.reportState(JSON.stringify(data))}catch{}}
try {
  const view=await(await fetch('/view.json')).json();
  const renderer=new THREE.WebGLRenderer({antialias:false,powerPreference:'high-performance'});
  renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.setSize(innerWidth,innerHeight);renderer.setClearColor(0x101721);document.body.prepend(renderer.domElement);
  const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(65,innerWidth/innerHeight,.01,10000);
  const worldUp=orientCapture(camera,view);
  const extent=new THREE.Vector3(...view.bounds_max).sub(new THREE.Vector3(...view.bounds_min)).length()||5;
  camera.near=Math.max(.001,extent*.0003);camera.far=extent*100;camera.updateProjectionMatrix();
  const baseSpeed=extent*.32;
  const control=new GameController(camera,{worldUp,speed:baseSpeed});
  const spark=new SparkRenderer({renderer});scene.add(spark);const mesh=new SplatMesh({url:'/model.ply'});
  const group=new THREE.Group();scene.add(group);const adjustment=new SceneTransform(group,mesh,new THREE.Vector3(...view.center));await mesh.initialized;
  $('loading').style.display='none';report({ready:true,controller:'floating-stick-touch-only',portrait_rotation:view.display_rotation_degrees||0});
  let lastRender=0,previous=performance.now(),activeUntil=previous+2000,running=false,draws=0;
  function wake(){activeUntil=performance.now()+1200;if(!running&&!document.hidden){running=true;previous=performance.now();renderer.setAnimationLoop(animate)}}
  function animate(){
    const now=performance.now();const active=control.input.forward||control.input.right||control.input.rise||control.input.roll||gestures.size;
    const interval=active?1000/60:1000/30;if(now-lastRender<interval-1)return;
    const dt=Math.min(.05,(now-previous)/1000);previous=now;lastRender=now;
    gestures.flush();control.update(dt);renderer.render(scene,camera);draws++;
    if(now>activeUntil&&!active&&control.velocity.lengthSq()<1e-7&&Math.abs(control.lookYaw)+Math.abs(control.lookPitch)<1e-5){running=false;renderer.setAnimationLoop(null)}
  }
  function updateSpeed(){control.speed=baseSpeed*adjustment.scale*Number($('speed').value)}
  const zone=$('move-zone'),stick=$('stick'),knob=$('knob');let stickPointer=null,stickOrigin=null;
  function stickMove(e){
    if(e.pointerId!==stickPointer)return;
    const radius=stick.clientWidth*.36,dx=e.clientX-stickOrigin.x,dy=e.clientY-stickOrigin.y,length=Math.hypot(dx,dy),limit=Math.max(1,length/radius);
    control.setStick(dx/radius,dy/radius);knob.style.transform=`translate(${dx/limit}px,${dy/limit}px)`;
    stick.classList.toggle('full-speed',length>=radius*.9);wake();
  }
  function releaseStick(){stickPointer=null;stickOrigin=null;control.releaseStick();knob.style.transform='none';stick.classList.remove('engaged','full-speed');stick.style.left='';stick.style.top='';wake()}
  zone.onpointerdown=e=>{
    if(stickPointer!==null)return;
    e.preventDefault();stickPointer=e.pointerId;stickOrigin={x:e.clientX,y:e.clientY};
    zone.setPointerCapture(e.pointerId);stick.style.left=e.clientX+'px';stick.style.top=e.clientY+'px';stick.classList.add('engaged');stickMove(e);
  };
  zone.onpointermove=stickMove;
  zone.onpointerup=zone.onpointercancel=zone.onlostpointercapture=e=>{if(e.pointerId===stickPointer)releaseStick()};
  function bindHold(id,key,value){
    const element=$(id);let held=null;
    element.onpointerdown=e=>{if(held!==null)return;held=e.pointerId;element.setPointerCapture(e.pointerId);element.classList.add('pressed');control.input[key]=value;wake()};
    element.onpointerup=element.onpointercancel=element.onlostpointercapture=e=>{if(e.pointerId!==held)return;held=null;element.classList.remove('pressed');control.input[key]=0;wake()};
  }
  bindHold('up','rise',1);bindHold('down','rise',-1);bindHold('roll-left','roll',-.8);bindHold('roll-right','roll',.8);
  const gestures=new SceneGestures({
    look:(dx,dy)=>control.look(dx,dy),
    cancelLook:()=>{control.lookYaw=control.lookPitch=0},
    transform:(dx,dy,twist,ratio)=>{
      adjustment.setScale(adjustment.scale*ratio);adjustment.rotateGesture(dx,dy,twist,camera,worldUp);
      updateSpeed();$('scene-scale').textContent=adjustment.scale.toFixed(2)+'×';
    },
    pan:(dx,dy)=>adjustment.panGesture(dx,dy,camera,innerHeight)
  });
  const canvas=renderer.domElement;
  canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);gestures.down(e.pointerId,e.clientX,e.clientY);wake()};
  canvas.onpointermove=e=>{gestures.move(e.pointerId,e.clientX,e.clientY);if(gestures.size)wake()};
  canvas.onpointerup=canvas.onpointercancel=canvas.onlostpointercapture=e=>{gestures.up(e.pointerId);wake()};
  function stopInputs(){releaseStick();control.input.rise=control.input.roll=0;gestures.clear();control.lookYaw=control.lookPitch=0;document.querySelectorAll('.pressed').forEach(e=>e.classList.remove('pressed'))}
  $('back').onclick=()=>window.RoomScan?.closeViewer?.();
  $('orientation').onclick=()=>{stopInputs();window.RoomScan?.rotateLayout?.()};
  $('reset').onclick=()=>{stopInputs();control.reset();wake()};
  $('settings-button').onclick=()=>{stopInputs();$('settings').showModal()};
  $('close-settings').onclick=()=>{$('settings').close();control.input.roll=0;wake()};
  $('speed').oninput=updateSpeed;
  $('sensitivity').oninput=e=>control.sensitivity=Number(e.target.value);
  $('mode').onchange=e=>{control.mode=e.target.value;wake()};
  $('scene-reset').onclick=()=>{adjustment.reset();$('scene-scale').textContent='1.00×';updateSpeed();wake()};
  function resize(){
    stopInputs();camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight);
    $('orientation').textContent=innerWidth>innerHeight?'竖屏':'横屏';wake();
  }
  window.addEventListener('resize',resize);
  document.addEventListener('visibilitychange',()=>{stopInputs();if(document.hidden){running=false;renderer.setAnimationLoop(null)}else wake()});
  if(window.RoomScan?.debugEnabled?.())window.roomscanDebug={snapshot:()=>({position:camera.position.toArray(),quaternion:camera.quaternion.toArray(),forward:camera.getWorldDirection(new THREE.Vector3()).toArray(),worldUp:worldUp.toArray(),velocity:control.velocity.toArray(),draws,sceneScale:adjustment.scale,scenePosition:group.position.toArray(),sceneQuaternion:group.quaternion.toArray(),input:{...control.input},stickOrigin,stickRadius:stick.clientWidth*.36,viewport:[innerWidth,innerHeight]}),control,camera,adjustment,wake};
  resize();
}catch(error){window.reportLoadFailure?.(error);report({ready:false,error:String(error)})}
