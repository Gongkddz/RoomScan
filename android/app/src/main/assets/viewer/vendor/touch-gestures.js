/** Consume all pointer moves once per frame, avoiding false pinch/twist in a multi-touch batch. */
export class SceneGestures {
  constructor({look,transform,pan,cancelLook}) {
    Object.assign(this,{look,transform,pan,cancelLook});this.points=new Map();this.previous=null;this.maxFingers=0;
  }
  get size(){return this.points.size}
  snapshot(){
    const p=[...this.points.values()],count=p.length;
    if(!count)return null;
    const result={count,x:p.reduce((sum,v)=>sum+v.x,0)/count,y:p.reduce((sum,v)=>sum+v.y,0)/count};
    if(count===2){result.distance=Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y);result.angle=Math.atan2(p[1].y-p[0].y,p[1].x-p[0].x)}
    return result;
  }
  down(id,x,y){this.points.set(id,{x,y});this.maxFingers=Math.max(this.maxFingers,this.size);this.previous=this.snapshot();if(this.size>1)this.cancelLook()}
  move(id,x,y){if(this.points.has(id))this.points.set(id,{x,y})}
  up(id){this.flush();this.points.delete(id);this.previous=this.snapshot();if(!this.size)this.maxFingers=0}
  clear(){this.points.clear();this.previous=null;this.maxFingers=0}
  flush(){
    const current=this.snapshot(),old=this.previous;this.previous=current;
    if(!old||!current||old.count!==current.count)return;
    const dx=current.x-old.x,dy=current.y-old.y;
    if(current.count===1&&this.maxFingers===1)this.look(dx,dy);
    else if(current.count===2&&this.maxFingers===2){
      let twist=current.angle-old.angle;if(twist>Math.PI)twist-=2*Math.PI;if(twist< -Math.PI)twist+=2*Math.PI;
      this.transform(dx,dy,twist,current.distance/Math.max(1,old.distance));
    }else if(current.count===3)this.pan(dx,dy);
  }
}
