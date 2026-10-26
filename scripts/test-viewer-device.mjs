import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
const targets=await(await fetch('http://127.0.0.1:9223/json')).json();
const target=targets.find(t=>t.url==='https://app.roomscan.local/index.html');
assert.ok(target,'Open the model viewer and forward its WebView debugger first');
const ws=new WebSocket(target.webSocketDebuggerUrl),pending=new Map();let next=0;
await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
ws.onmessage=event=>{const data=JSON.parse(event.data);if(data.id){const p=pending.get(data.id);pending.delete(data.id);data.error?p.reject(data.error):p.resolve(data.result)}};
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}))});
async function evaluate(expression){const result=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));return result.result.value}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const snapshot=()=>evaluate('roomscanDebug.snapshot()');
const rect=id=>evaluate(`(()=>{const r=document.getElementById(${JSON.stringify(id)}).getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})()`);
const touch=(type,points)=>call('Input.dispatchTouchEvent',{type,touchPoints:points.map((p,id)=>({id,x:p[0],y:p[1],radiusX:2,radiusY:2,force:1}))});
const distance=(a,b)=>Math.hypot(...a.map((n,i)=>n-b[i]));
const dot=(a,b)=>a.reduce((sum,n,i)=>sum+n*b[i],0);
const results=[];
async function rotateTo(landscape){
  const before=await snapshot();if((before.viewport[0]>before.viewport[1])!==landscape)await evaluate("document.getElementById('orientation').click()");
  for(let i=0;i<40;i++){await delay(100);const current=await snapshot();if((current.viewport[0]>current.viewport[1])===landscape){assert.ok(distance(before.position,current.position)<1e-6);assert.ok(distance(before.quaternion,current.quaternion)<1e-6);return current}}
  throw new Error('Orientation did not change');
}
async function stickDrag(dx,dy,ms=500,anchor=null){
  const z=await rect('move-zone'),start=anchor||[z.x+z.width*.55,z.y+z.height*.52];
  await touch('touchStart',[start]);const state=await snapshot();assert.ok(distance([state.stickOrigin.x,state.stickOrigin.y],start)<1);
  await touch('touchMove',[[start[0]+dx,start[1]+dy]]);await delay(ms);await touch('touchEnd',[]);await delay(600);
}
try{
  assert.equal(await evaluate("typeof window.onRoomPose==='undefined' && typeof window.RoomScan.setFollow==='undefined'"),true);results.push('inertial follow removed from viewer and native bridge');
  await rotateTo(true);results.push('landscape layout without reloading or resetting pose');
  await evaluate("document.getElementById('scene-reset').click();document.getElementById('reset').click()");await delay(200);
  const radius=(await snapshot()).stickRadius;
  for(const [name,dx,dy,expected]of[['forward',0,-radius,1],['backward',0,radius,-1],['strafe-right',radius,0,0]]){
    const before=await snapshot();await stickDrag(dx,dy);const after=await snapshot(),delta=after.position.map((n,i)=>n-before.position[i]);
    assert.ok(distance(before.position,after.position)>.05,name+' moved');
    if(expected)assert.ok(dot(delta,before.forward)*expected>.05,name+' direction');else assert.ok(Math.abs(dot(delta,before.forward))<distance(before.position,after.position)*.1,'strafe perpendicular');results.push(name+' with floating stick');
  }
  const zone=await rect('move-zone');
  const speeds=[];for(const fraction of [.25,1]){const before=await snapshot();await stickDrag(0,-radius*fraction,500,[zone.x+zone.width*.32,zone.y+zone.height*.65]);speeds.push(distance(before.position,(await snapshot()).position))}
  assert.ok(speeds[1]>speeds[0]*4,JSON.stringify(speeds));results.push('floating origin works at different touch points; longer throw moves faster');
  for(const [id,sign]of[['up',1],['down',-1]]){
    const r=await rect(id),before=await snapshot();assert.ok(r.width>=72&&r.height>=72);assert.ok(r.x>before.viewport[0]*.5);
    await touch('touchStart',[[r.x+r.width/2,r.y+r.height/2]]);await delay(450);await touch('touchEnd',[]);await delay(600);const after=await snapshot();assert.ok(dot(after.position.map((n,i)=>n-before.position[i]),before.worldUp)*sign>.03);results.push(id+' with enlarged right-side button');
  }
  const [w,h]=(await snapshot()).viewport,y=h*.30;
  const beforeLook=await snapshot();await touch('touchStart',[[w*.65,y]]);await touch('touchMove',[[w*.65+35,y-15]]);await touch('touchEnd',[]);await delay(400);assert.ok(distance(beforeLook.quaternion,(await snapshot()).quaternion)>.02);results.push('single-finger look');
  const beforePinch=await snapshot();await touch('touchStart',[[w*.35,y],[w*.65,y]]);await touch('touchMove',[[w*.425,y],[w*.575,y]]);await touch('touchEnd',[]);await delay(100);const afterPinch=await snapshot();assert.ok(Math.abs(afterPinch.sceneScale/beforePinch.sceneScale-.5)<.03);results.push('two-finger pinch changes actual model scale to 0.5');
  const beforeTwist=await snapshot();await touch('touchStart',[[w*.4,y],[w*.6,y]]);await touch('touchMove',[[w*.45,y-25],[w*.55,y+25]]);await touch('touchEnd',[]);await delay(100);const afterTwist=await snapshot();assert.ok(distance(beforeTwist.sceneQuaternion,afterTwist.sceneQuaternion)>.1);assert.ok(distance(beforeTwist.quaternion,afterTwist.quaternion)<1e-6);results.push('two-finger twist rotates scene without turning camera');
  const beforePan=await snapshot(),points=[[w*.35,y],[w*.5,y],[w*.65,y+20]];
  await touch('touchStart',points);await touch('touchMove',points.map(p=>[p[0]+30,p[1]+18]));await touch('touchEnd',[]);await delay(100);
  const afterPan=await snapshot();assert.ok(distance(beforePan.scenePosition,afterPan.scenePosition)>.01);assert.ok(distance(beforePan.sceneQuaternion,afterPan.sceneQuaternion)<1e-6);assert.ok(distance(beforePan.quaternion,afterPan.quaternion)<1e-6);assert.ok(Math.abs(beforePan.sceneScale-afterPan.sceneScale)<1e-6);results.push('three-finger pan moves scene with scale, rotation and camera unchanged');
  const anchor=[zone.x+zone.width*.55,zone.y+zone.height*.6],look=[w*.65,y];const beforeCombined=await snapshot();
  await touch('touchStart',[anchor,look]);await touch('touchMove',[[anchor[0],anchor[1]-radius],[look[0]+30,look[1]-15]]);await delay(400);await touch('touchEnd',[]);await delay(600);const afterCombined=await snapshot();
  assert.ok(distance(beforeCombined.position,afterCombined.position)>.03);assert.ok(distance(beforeCombined.quaternion,afterCombined.quaternion)>.02);results.push('left thumb movement and right thumb look work simultaneously');
  await rotateTo(false);const portrait=await snapshot(),up=await rect('up'),down=await rect('down');assert.ok(up.x+up.width<portrait.viewport[0]&&down.x+down.width<portrait.viewport[0]);results.push('portrait layout keeps controls visible and preserves pose');await rotateTo(true);
  await evaluate("document.getElementById('scene-reset').click();document.getElementById('reset').click()");await delay(1800);
  const still=await snapshot();await delay(3500);const final=await snapshot();assert.ok(distance(still.position,final.position)<1e-10);assert.ok(distance(still.quaternion,final.quaternion)<1e-10);assert.equal(final.draws,still.draws);results.push('no idle camera drift or continuous drawing');
  await writeFile('.runtime/viewer-device-test.json',JSON.stringify({passed:true,results,final,recordedAt:new Date().toISOString()},null,2));console.log(JSON.stringify({passed:true,results},null,2));
}finally{ws.close()}
